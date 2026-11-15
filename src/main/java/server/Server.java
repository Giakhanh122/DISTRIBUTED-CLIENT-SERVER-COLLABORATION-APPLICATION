package server;

import protocols.type.DiscoveryType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import protocols.Json;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import server.auth.AuthService;
import server.broadcast.BroadcastFileRegistry;
import server.community.CommunityRegistry;
import server.community.CommunityService;
import server.connection.ClientHandler;
import server.security.ServerIdentity;
import server.session.ChatSessionRegistry;
import server.session.UserSessionRegistry;
import server.storage.FileStorageService;

public class Server implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private ServerSocket tcpListener;
    private DatagramSocket udpListener;
    private ServerIdentity identity;
    private final UserSessionRegistry userSessions = new UserSessionRegistry();
    private final ChatSessionRegistry chatSessions = new ChatSessionRegistry();
    private final AuthService authService = new AuthService();
    private final CommunityRegistry communityRegistry = new CommunityRegistry();
    private final CommunityService communityService = new CommunityService(communityRegistry, userSessions);
    private final BroadcastFileRegistry broadcastFileRegistry = new BroadcastFileRegistry();
    private final FileStorageService fileStorageService;
    private final Path identityDirectory;
    private final int discoveryPort;

    private static final int PORT = 8080;
    private static final int DISCOVERY_PORT = 41234;

    public Server() {
        this(Path.of("server_identity"), Path.of("server_storage"), DISCOVERY_PORT);
    }

    public Server(Path identityDirectory, Path storageDirectory, int discoveryPort) {
        this.identityDirectory = identityDirectory;
        this.fileStorageService = new FileStorageService(storageDirectory);
        this.discoveryPort = discoveryPort;
    }

    public int port() {
        synchronized (lifecycleLock) { return tcpListener == null ? -1 : tcpListener.getLocalPort(); }
    }

    public static void main(String[] args) {
        new Server().start(args.length == 0 ? PORT : Integer.parseInt(args[0]));
    }

    public void start(int portNumber) {
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Server already started");
        Thread shutdownHook = Thread.ofPlatform().unstarted(this::close);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            identity = ServerIdentity.loadOrCreate(identityDirectory);
        } catch (IOException | GeneralSecurityException e) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            throw new IllegalStateException("Cannot load server identity", e);
        }

        try (
            ServerSocket listener = new ServerSocket(portNumber);
            DatagramSocket discoverySocket = new DatagramSocket(discoveryPort);
        ) {
            synchronized (lifecycleLock) {
                if (closed) return;
                tcpListener = listener;
                udpListener = discoverySocket;
            }
            Thread.startVirtualThread(() -> listenDiscovery(discoverySocket, listener.getLocalPort()));

            printServerAddresses(listener.getLocalPort());
            System.out.println("Server started on port " + listener.getLocalPort());
            while (!closed) {
                Socket socket = listener.accept();
                Thread worker = Thread.ofVirtual().unstarted(() -> {
                    try (socket) {
                        new ClientHandler(socket, userSessions, chatSessions, authService, communityService,
                                broadcastFileRegistry, fileStorageService, identity).run();
                    } catch (IOException | RuntimeException e) {
                        System.err.println("Client handler failed: " + e.getMessage());
                    } finally {
                        connections.remove(socket);
                        workers.remove(Thread.currentThread());
                    }
                });
                synchronized (lifecycleLock) {
                    if (closed) { socket.close(); break; }
                    connections.add(socket);
                    workers.add(worker);
                    worker.start();
                }
            }
        } catch (IOException e) {
            if (!closed) System.err.println("Server error: " + e.getMessage());
        } finally {
            close();
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            if (udpListener != null) udpListener.close();
            if (tcpListener != null) {
                try { tcpListener.close(); }
                catch (IOException e) { System.err.println("Cannot close server listener: " + e.getMessage()); }
            }
            for (Socket socket : connections) {
                try { socket.close(); }
                catch (IOException e) { System.err.println("Cannot close client connection: " + e.getMessage()); }
            }
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        for (Thread worker : Set.copyOf(workers)) {
            if (worker == Thread.currentThread()) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            try { worker.join(Duration.ofNanos(remaining)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void printServerAddresses(int port) {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                var network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                for (var address : network.getInterfaceAddresses()) {
                    if (address.getAddress() instanceof Inet4Address ip) {
                        System.out.println("Server address (" + network.getDisplayName() + "): "
                                + ip.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Cannot list server addresses: " + e.getMessage());
        }
    }

    private void listenDiscovery(DatagramSocket socket, int tcpPort) {
        byte[] buffer = new byte[4096];

        while (!socket.isClosed()) {
            DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);

            try {
                socket.receive(incoming);
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    System.err.println("Discovery receive error: " + e.getMessage());
                }
                return;
            }

            Packet request;
            try {
                ByteArrayInputStream input = new ByteArrayInputStream(
                    incoming.getData(),
                    incoming.getOffset(),
                    incoming.getLength()
                );

                request = PacketCodec.read(input);
                PacketValidator.validateRequest(request, DiscoveryType.REQUEST, 0, 0);
                if (request.sequence() != 1 || input.available() != 0) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }

            try {
                Packet response = Packet.reply(DiscoveryType.RESPONSE, request.sequence(), Json.object("status", "OK", "tcpPort", tcpPort));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                PacketCodec.write(output, response, 1);
                byte[] bytes = output.toByteArray();
                DatagramPacket outgoing = new DatagramPacket(bytes, bytes.length, incoming.getAddress(), incoming.getPort());

                socket.send(outgoing);
            } catch (IOException e) {
                if (socket.isClosed()) return;
                System.err.println("Discovery respone error: " + e.getMessage());
            }
        }
    }
}

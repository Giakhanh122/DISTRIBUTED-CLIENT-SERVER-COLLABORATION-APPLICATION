package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;

/** Discovers and verifies a reachable server through each active IPv4 LAN. */
final class ServerDiscovery {
    private static final int DISCOVERY_PORT = 41234;
    private static final int ATTEMPTS = 5;
    private static final int RESPONSE_TIMEOUT_MS = 3000;
    private static final int PROBE_TIMEOUT_MS = 1500;

    private record Network(InetAddress local, InetAddress broadcast) {}
    private record Endpoint(Network network, DatagramSocket socket) {}

    Client.ServerInfo discover(int requestId) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        PacketCodec.write(encoded, new Packet(OpCode.DISCOVER_REQUEST, requestId, new byte[0]));
        byte[] request = encoded.toByteArray();
        List<Endpoint> endpoints = new ArrayList<>();
        List<Future<Client.ServerInfo>> workers = new ArrayList<>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            for (Network network : networks()) {
                DatagramSocket socket = new DatagramSocket(null);
                try {
                    socket.bind(new InetSocketAddress(network.local(), 0));
                    socket.setBroadcast(true);
                    endpoints.add(new Endpoint(network, socket));
                } catch (IOException e) {
                    socket.close();
                    // A disconnected adapter must not prevent other LANs from working.
                }
            }
            if (endpoints.isEmpty()) throw new IOException("No IPv4 LAN interface available for discovery");

            System.out.println("Finding server: scanning " + endpoints.size() + " network interface(s)");
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos((long) ATTEMPTS * RESPONSE_TIMEOUT_MS);
            var results = new ExecutorCompletionService<Client.ServerInfo>(executor);
            for (Endpoint endpoint : endpoints)
                workers.add(results.submit(() -> search(endpoint, request, requestId, deadline)));

            for (int remaining = workers.size(); remaining > 0; remaining--) {
                long wait = deadline - System.nanoTime();
                if (wait <= 0) break;
                Future<Client.ServerInfo> finished = results.poll(wait, TimeUnit.NANOSECONDS);
                if (finished == null) break;
                Client.ServerInfo server = finished.get();
                if (server != null) return server;
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Discovery interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Discovery failed", e.getCause());
        } finally {
            // Close UDP reads and interrupt TCP probes before waiting for workers to exit.
            endpoints.forEach(endpoint -> endpoint.socket().close());
            workers.forEach(worker -> worker.cancel(true));
            executor.shutdownNow();
            executor.close();
        }
    }

    private List<Network> networks() throws IOException {
        var networks = new LinkedHashSet<Network>();
        var interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            var network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (var address : network.getInterfaceAddresses()) {
                if (address.getAddress() instanceof Inet4Address && address.getBroadcast() != null)
                    networks.add(new Network(address.getAddress(), address.getBroadcast()));
            }
        }
        return List.copyOf(networks);
    }

    private Client.ServerInfo search(Endpoint endpoint, byte[] request, int requestId, long deadline) {
        DatagramSocket socket = endpoint.socket();
        byte[] buffer = new byte[4096];
        try {
            for (int attempt = 0; attempt < ATTEMPTS && System.nanoTime() < deadline; attempt++) {
                socket.send(new DatagramPacket(request, request.length,
                        endpoint.network().broadcast(), DISCOVERY_PORT));
                long responseDeadline = Math.min(deadline,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESPONSE_TIMEOUT_MS));
                while (System.nanoTime() < responseDeadline) {
                    long remaining = responseDeadline - System.nanoTime();
                    socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                    DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(incoming);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    Client.ServerInfo candidate = decode(incoming, requestId);
                    if (candidate != null && verify(candidate, endpoint.network().local(), requestId))
                        return candidate;
                }
            }
        } catch (IOException e) {
            // Other workers continue if this interface disappears or cannot broadcast.
        }
        return null;
    }

    private Client.ServerInfo decode(DatagramPacket incoming, int requestId) {
        if (incoming.getPort() != DISCOVERY_PORT) return null;
        try {
            var input = new ByteArrayInputStream(incoming.getData(), incoming.getOffset(), incoming.getLength());
            Packet response = PacketValidator.validateResponse(PacketCodec.read(input),
                    OpCode.DISCOVER_RESPONSE, requestId);
            if (input.available() != 0) return null;
            int port = Integer.parseInt(response.text());
            if (port < 1 || port > 65535) return null;
            return new Client.ServerInfo(incoming.getAddress().getHostAddress(), port);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private boolean verify(Client.ServerInfo server, InetAddress local, int requestId) {
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(local, 0));
            socket.connect(new InetSocketAddress(server.address(), server.port()), PROBE_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_TIMEOUT_MS);
            PacketCodec.write(socket.getOutputStream(), new Packet(OpCode.HELLO, requestId, new byte[0]));
            Packet ack = PacketValidator.validateResponse(PacketCodec.read(socket.getInputStream()),
                    OpCode.HELLO_ACK, requestId);
            return ack.payloadLength() == 0;
        } catch (IOException e) {
            return false;
        }
    }
}

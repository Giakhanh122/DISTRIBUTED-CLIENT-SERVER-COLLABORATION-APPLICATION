package client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class PeerListener implements Runnable, AutoCloseable {
    private final ServerSocket listener;
    private final BiConsumer<String, PeerConnection> onConnected;
    private final BiConsumer<Socket, Packet> onFileConnection;
    private final Set<Socket> pendingConnections = ConcurrentHashMap.newKeySet();

    public PeerListener(
        BiConsumer<String, PeerConnection> onConnected,
        BiConsumer<Socket, Packet> onFileConnection
    ) throws IOException {
        listener = new ServerSocket(0);
        this.onConnected = onConnected;
        this.onFileConnection = onFileConnection;
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    @Override 
    public void run() {
        System.out.println("Peer listener started on port " + getPort());

        while (!listener.isClosed()) {
            try {
                Socket peerSocket = listener.accept();
                pendingConnections.add(peerSocket);
                Thread.startVirtualThread(() -> handlePeer(peerSocket));
            } catch (IOException e) {
                if (!listener.isClosed()) {
                    System.err.println("Peer listener error: " + e.getMessage());
                }
                break;
            }
        }
    }

    private void handlePeer(Socket socket) {
        try {
            if (listener.isClosed()) {
                socket.close();
                return;
            }
            socket.setSoTimeout(10000);
            // Read only the first packet here; exactly one handler owns subsequent reads.
            Packet first = PacketCodec.read(socket.getInputStream());
            if (first != null && first.op() == OpCode.PEER_HELLO) {
                PeerConnection connection = new PeerConnection(socket);
                String peerUsername = connection.acceptHandshake(first);
                onConnected.accept(peerUsername, connection);
                System.out.println("Peer handshake successful with " + peerUsername);
            } else if (first != null && first.op() == OpCode.FILE_OFFER && first.requestId() == 0) {
                try (socket) {
                    onFileConnection.accept(socket, first);
                }
            } else {
                throw new IOException("Expected PEER_HELLO or file connection identification");
            }
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }

            System.err.println(
                "Peer connection failed: " + e.getMessage()
            );
        } finally {
            pendingConnections.remove(socket);
        }
    }

    @Override 
    public void close() throws IOException {
        listener.close();
        for (Socket socket : pendingConnections) {
            socket.close();
        }
    }
}

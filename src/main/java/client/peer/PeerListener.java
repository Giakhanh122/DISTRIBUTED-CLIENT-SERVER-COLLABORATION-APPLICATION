package client.peer;

import protocols.type.ConnectionType;
import protocols.type.FileType;

import client.security.ServerKeyPinning;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import protocols.Packet;
import network.PacketChannel;

public class PeerListener implements Runnable, AutoCloseable {
    private final ServerSocket listener;
    private final ServerKeyPinning serverKeyPinning;
    private final PeerConnection.KeyProofProvider keyProofProvider;
    private final BiConsumer<String, PeerConnection> onConnected;
    private final BiConsumer<PacketChannel, Packet> onFileConnection;
    private final Set<Socket> pendingConnections = ConcurrentHashMap.newKeySet();

    public PeerListener(
        ServerKeyPinning serverKeyPinning,
        PeerConnection.KeyProofProvider keyProofProvider,
        BiConsumer<String, PeerConnection> onConnected,
        BiConsumer<PacketChannel, Packet> onFileConnection
    ) throws IOException {
        listener = new ServerSocket(0);
        this.serverKeyPinning = serverKeyPinning;
        this.keyProofProvider = keyProofProvider;
        this.onConnected = onConnected;
        this.onFileConnection = onFileConnection;
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    @Override 
    public void run() {
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
            PacketChannel channel = new PacketChannel(socket);
            Packet first = channel.read();
            if (first != null && first.type() == ConnectionType.PEER_HELLO) {
                PeerConnection connection = new PeerConnection(channel);
                String peerUsername = connection.acceptHandshake(first, serverKeyPinning, keyProofProvider);
                onConnected.accept(peerUsername, connection);
            } else if (first != null && first.type() == FileType.FILE_TRANSFER_START) {
                try (socket) {
                    onFileConnection.accept(channel, first);
                }
            } else {
                throw new IOException("Expected PEER_HELLO or FILE_TRANSFER_START");
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

package client;

import server.Server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.BiConsumer;

public class PeerListener implements Runnable, AutoCloseable {
    private final ServerSocket listener;
    private final BiConsumer<String, PeerConnection> onConnected;

    public PeerListener(
        BiConsumer<String, PeerConnection> onConnected
    ) throws IOException {
        listener = new ServerSocket(0);
        this.onConnected = onConnected;
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
        PeerConnection connection = new PeerConnection(socket);

        try {
            String peerUsername = connection.acceptHandshake();
            onConnected.accept(peerUsername, connection);

            System.out.println(
                "Peer handshake successful with " + peerUsername
            );
        } catch (IOException | RuntimeException e) {
            try {
                connection.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }

            System.err.println(
                "Peer handshake failed: " + e.getMessage()
            );
        }
    }

    @Override 
    public void close() throws IOException {
        listener.close();
    }
}

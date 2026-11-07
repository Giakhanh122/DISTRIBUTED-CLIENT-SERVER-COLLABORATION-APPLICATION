package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import server.auth.AuthService;
import server.connection.CilentHandler;
import server.session.SessionRegistry;

public class Server {
    private final SessionRegistry sessions = new SessionRegistry();
    private final AuthService authService = new AuthService();

    // private String ipAddress;
    // private int portNumber;

    public void start(int portNumber) {
        try (ServerSocket listener = new ServerSocket(portNumber)) {
            System.out.println("Server started on port " + portNumber);

            while (true) {
                Socket socket = listener.accept();
                CilentHandler handler = new CilentHandler(socket, sessions, authService);
                Thread.startVirtualThread(handler);
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
}

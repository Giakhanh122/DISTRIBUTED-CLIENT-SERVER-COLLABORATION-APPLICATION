package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {

    public void start(int portNumber) {
        try (var serverSocket = new ServerSocket(portNumber);) {

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                while (true) {
                    var socket = serverSocket.accept();

                    executor.submit(() -> {
                        String clientIP = socket.getInetAddress().getHostAddress();
                        int clientPort = socket.getPort();
                        System.out.printf("Client (%s:%d) connected%n", clientIP, clientPort);

                        try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));) {
                            for (String content; (content = input.readLine()) != null;) {
                                System.out.printf("Client (%s:%d): %s%n", clientIP, clientPort, content);
                            }
                        } catch (Exception e) {
                            System.out.println(e.getMessage());
                        }

                    });
                }

            }

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

}

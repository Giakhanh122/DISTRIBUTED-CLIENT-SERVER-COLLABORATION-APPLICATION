package client;

import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class Client {

    public void start(String host, int portNumber) {
        try (var socket = new Socket(host, portNumber); PrintWriter output = new PrintWriter(socket.getOutputStream(), true); var scanner = new Scanner(System.in)) {

            System.out.println("connected to server !");

            for (String message; !(message = scanner.nextLine()).isEmpty();) {
                output.println(message);
                output.flush();
            }

            output.println("log out !");

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}

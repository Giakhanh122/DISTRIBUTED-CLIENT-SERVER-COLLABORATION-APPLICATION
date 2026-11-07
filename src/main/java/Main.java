import client.Client;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import protocols.*;
import server.Server;

public class Main {
    private static final int PORT = 8080;
    private static final String ServerIP = "localhost";

    public static void main(String[] args) {
        // run();
        test_broadcast();
        // test_packet();
    }

    private static void runLocal() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Choose service to run, server(s) or client(c): ");
            if (!scanner.hasNextLine()) {
                return;
            }

            String choice = scanner.nextLine().trim();
            if (choice.equalsIgnoreCase("s")) {
                new Server().start(PORT);
            } else if (choice.equalsIgnoreCase("c")) {
                new Client().connectToServer(ServerIP, PORT, scanner);
            } else {
                System.out.println("Invalid choice!");
            }
        }
    }

    // private static void runLan() {
    // try (Scanner scanner = new Scanner(System.in)) {
    // System.out.print("Choose service to run, server(s) or client(c): ");
    // if (!scanner.hasNextLine()) {
    // return;
    // }
    //
    // String choice = scanner.nextLine().trim();
    // if (choice.equalsIgnoreCase("s")) {
    // new Server().start(PORT);
    // } else if (choice.equalsIgnoreCase("c")) {
    // new Client().connectToServer(ServerIP, PORT, scanner);
    // } else {
    // System.out.println("Invalid choice!");
    // }
    // }
    // }

    // all device in the same wifi network, using DHCP
    private static void test_broadcast() {
        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Choose service to run, server(s) or client(c): ");
            if (!scanner.hasNextLine()) {
                return;
            }

            String choice = scanner.nextLine().trim();
            if (choice.equalsIgnoreCase("s")) {
                new Server().listenBroadcast();
            } else if (choice.equalsIgnoreCase("c")) {
                new Client().discover();

            } else {
                System.out.println("Invalid choice!");
            }
        }
    }

    private static void test_packet() {
        byte[] message = "Who's server ?".getBytes(StandardCharsets.UTF_8);
        byte[] data = new Packet(OpCode.DISCOVER_REQUEST, 1, message).toBytes();
        System.out.println(data);
    }
}

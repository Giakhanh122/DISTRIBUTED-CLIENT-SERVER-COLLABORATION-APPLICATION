import client.Client;
import java.util.Scanner;
import server.Server;
public class Main {
    private static final int PORT = 8080;
    private static final String ServerIP = "localhost";

    public static void main(String[] args) {
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

    void localRun() {
    }

    // all device in the same wifi network, using DHCP
    void lanRun() {
    }
}

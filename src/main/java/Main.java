import client.Client;
import java.util.Scanner;
import server.Server;

public class Main {
    private static final int PORT = 8080;

    public static void main(String[] args) {
        String serverHost = args.length > 0 ? args[0] : null;

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.print("Run server(s) or client(c): ");

            if (!scanner.hasNextLine()) {
                return;
            }

            String choice = scanner.nextLine().trim();

            if (choice.equalsIgnoreCase("s")) {
                new Server().start(PORT);
            } else if (choice.equalsIgnoreCase("c")) {
                new Client().start(serverHost, PORT, scanner);
            } else {
                System.out.println("Invalid choice!");
            }
        }
    }
}
import client.Client;
import java.util.Scanner;
import server.Server;
public class Main {
   public static void main(String[] args) {
        try (var scanner = new Scanner(System.in)){
            System.out.println("Choose service to run, server(s) or client(c):");          
            if(scanner.nextLine().equalsIgnoreCase("s")){
                // start server
                new Server().start(80);
            }else{
                new Client().start("localhost", 80);
                // start client
            } 
        } 
   } 
}


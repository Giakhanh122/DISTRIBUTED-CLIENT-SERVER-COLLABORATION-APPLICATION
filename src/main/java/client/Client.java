package client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class Client {
    private static final int MAX_CHAT_BYTES = 64 * 1024;

    public void start(String host, int portNumber, Scanner scanner) {
        try (Socket socket = new Socket(host, portNumber)) {
            socket.setSoTimeout(5000); 
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            int requestId = 1;
            PacketCodec.write(output, new Packet(OpCode.HELLO, requestId, new byte[0]));

            Packet response = PacketCodec.read(input);
            if (response == null
                || response.op() != OpCode.HELLO_ACK
                || response.requestId() != requestId
                || response.payloadLength() != 0) {
                System.err.println("Invalid or missing HELLO_ACK");
                return;
            }

            System.out.println("Handshake successful!");
            System.out.println("Type a message to send to the server, or logout to disconnect.");

            while (scanner.hasNextLine()) {
                String message = scanner.nextLine();
                if (message.trim().equalsIgnoreCase("logout")) {
                    break;
                }
                if (message.isBlank()) {
                    continue;
                }

                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                if (data.length > MAX_CHAT_BYTES) {
                    System.err.println("Message exceeds 64 KiB");
                    continue;
                }

                PacketCodec.write(output, new Packet(OpCode.CHAT_MESSAGE, ++requestId, data));
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }
}

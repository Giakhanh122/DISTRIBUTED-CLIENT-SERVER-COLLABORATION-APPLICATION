package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;

final class ServerDiscovery {
    private static final int DISCOVERY_PORT = 41234;
    private static final int MAX_ATTEMPTS = 5;
    private static final int TIMEOUT_MS = 3000;

    Client.ServerInfo discover() throws IOException {
        InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
        Packet request = Packet.empty(OpCode.DISCOVER_REQUEST);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        PacketCodec.write(encoded, request, 1);
        byte[] requestBytes = encoded.toByteArray();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(TIMEOUT_MS);

            DatagramPacket outgoing = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    broadcastAddress,
                    DISCOVERY_PORT);

            byte[] buffer = new byte[4096];
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                socket.send(outgoing);
                System.out.println("Finding server: " + attempt + "/" + MAX_ATTEMPTS);

                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(incoming);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                try {
                    ByteArrayInputStream input = new ByteArrayInputStream(
                            incoming.getData(),
                            incoming.getOffset(),
                            incoming.getLength());

                    Packet response = PacketCodec.read(input);
                    PacketValidator.validateResponse(response, OpCode.DISCOVER_RESPONSE, 1);
                    PacketValidator.requireSuccess(response);
                    if (response.sequence() != 1 || input.available() != 0 || incoming.getPort() != DISCOVERY_PORT) {
                        continue;
                    }

                    int tcpPort = response.integer("tcpPort");
                    if (tcpPort < 1 || tcpPort > 65535)
                        continue;

                    return new Client.ServerInfo(incoming.getAddress().getHostAddress(), tcpPort);
                } catch (IOException | NumberFormatException e) {
                    continue;
                }
            }
        }
        return null;
    }

}

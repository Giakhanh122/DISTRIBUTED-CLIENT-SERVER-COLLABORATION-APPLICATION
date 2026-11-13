package client;

import java.io.IOException;
import java.net.Socket;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import network.PacketChannel;
import protocols.PacketValidator;

/** Shared discovery and authentication API for console and graphical clients. */
public class Client {
    public record ServerInfo(String address, int port) {}

    public ClientSession login(String host, int port, String username, String password, ClientEvents events)
            throws IOException {
        Socket socket = new Socket();
        ClientSession session = null;
        try {
            socket.connect(new java.net.InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(10000);
            PacketChannel channel = new PacketChannel(socket);
            handshake(channel);
            session = new ClientSession(username, host, port, channel, events);
            authenticate(channel, username, password, session.peerPort());
            socket.setSoTimeout(0);
            return session;
        } catch (IOException | RuntimeException e) {
            if (session != null) session.close();
            try { socket.close(); } catch (IOException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    public ServerInfo discover() throws IOException {
        return new ServerDiscovery().discover();
    }

    private void handshake(PacketChannel channel) throws IOException {
        long sequence = channel.send(Packet.json(OpCode.HELLO,
                Json.object("protocolVersion", Packet.PROTOCOL_VERSION)));
        Packet response = channel.read();
        PacketValidator.validateResponse(response, OpCode.HELLO_ACK, sequence);
        PacketValidator.requireSuccess(response);
        if (response.integer("protocolVersion") != Packet.PROTOCOL_VERSION) throw new IOException("Unsupported protocol version");
    }

    private void authenticate(PacketChannel channel, String username, String password, int peerPort) throws IOException {
        long sequence = channel.send(Packet.json(OpCode.AUTH_REQUEST,
                Json.object("username", username, "password", password, "peerPort", peerPort)));
        Packet response = PacketValidator.validateResponse(channel.read(), OpCode.AUTH_RESPONSE, sequence);
        if (!response.status().equals("OK")) throw new IOException("Login failed: " + response.status());
    }

    /** Reauthenticate an existing session's listening port without creating another peer listener. */
    PacketChannel reconnect(Socket socket, String host, int port, String username, String password, int peerPort)
            throws IOException {
        socket.connect(new java.net.InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(10000);
        PacketChannel channel = new PacketChannel(socket);
        handshake(channel);
        authenticate(channel, username, password, peerPort);
        socket.setSoTimeout(0);
        return channel;
    }
}

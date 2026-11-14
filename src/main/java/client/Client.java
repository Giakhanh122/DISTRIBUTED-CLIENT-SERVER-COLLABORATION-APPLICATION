package client;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Base64;

import client.connection.ServerDiscovery;
import client.security.ServerKeyPinning;
import network.PacketChannel;
import protocols.Json;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketValidator;
import security.SessionKeys;
import security.X25519;

/** Shared discovery and authentication API for console and graphical clients. */
public class Client {
    private static final Path SERVER_PUBLIC_KEY_PATH = Path.of("config/server-public.key");
    private ServerKeyPinning serverKeyPinning;

    public record ServerInfo(String address, int port) {}

    public ClientSession login(String host, int port, String username, String password, ClientEvents events)
            throws IOException {
        loadServerKey();
        Socket socket = new Socket();
        ClientSession session = null;
        try {
            socket.connect(new java.net.InetSocketAddress(host, port), 10000);
            socket.setSoTimeout(10000);
            PacketChannel channel = secureChannel(socket);
            session = new ClientSession(username, host, port, channel, events, serverKeyPinning);
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

    /** Secures a connected server socket; the caller owns and closes the socket. */
    public PacketChannel secureChannel(Socket socket) throws IOException {
        loadServerKey();
        int readTimeout = socket.getSoTimeout();
        socket.setSoTimeout(10000);
        PacketChannel channel = new PacketChannel(socket);
        SessionKeys sessionKeys = handshake(channel);
        channel.confirmEncryption(sessionKeys, PacketChannel.Role.INITIATOR);
        socket.setSoTimeout(readTimeout);
        return channel;
    }

    private void loadServerKey() throws IOException {
        if (serverKeyPinning != null) return;
        try {
            serverKeyPinning = new ServerKeyPinning(SERVER_PUBLIC_KEY_PATH);
        } catch (IOException | GeneralSecurityException e) {
            throw new IOException("Cannot load pinned server key: " + SERVER_PUBLIC_KEY_PATH, e);
        }
    }

    private SessionKeys handshake(PacketChannel channel) throws IOException {
        KeyPair clientKeyPair = createKeyPair();
        String encodedPublicKey = Base64.getEncoder()
                .encodeToString(clientKeyPair.getPublic().getEncoded());

        byte[] serverVerificationChallenge = new byte[32];
        new SecureRandom().nextBytes(serverVerificationChallenge);
        String encodedChallenge = Base64.getEncoder().encodeToString(serverVerificationChallenge);

        Packet hello = Packet.json(OpCode.HELLO,
                Json.object("protocolVersion", Packet.PROTOCOL_VERSION,
                        "serverVerificationChallenge", encodedChallenge,
                        "publicKey", encodedPublicKey));
        long sequence = channel.send(hello);
        Packet response = channel.read();
        PacketValidator.validateResponse(response, OpCode.HELLO_ACK, sequence);
        PacketValidator.requireSuccess(response);
        if (response.integer("protocolVersion") != Packet.PROTOCOL_VERSION) throw new IOException("Unsupported protocol version");

        try {
            String encodedServerPublicKey = response.string("publicKey");
            byte[] signedData = Json.bytes(Json.object("purpose", "server-handshake",
                    "hello", hello.jsonBody(), "serverPublicKey", encodedServerPublicKey));
            byte[] serverSignature = Base64.getDecoder().decode(response.string("serverSignature"));
            if (serverSignature.length != 64) throw new IOException("Invalid server signature length");
            if (!serverKeyPinning.verify(signedData, serverSignature)) {
                throw new IOException("Server signature verification failed");
            }
            PublicKey serverPublicKey = X25519.decodePublicKey(encodedServerPublicKey);
            return X25519.deriveSessionKeys(clientKeyPair, serverPublicKey, signedData);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Invalid server handshake", e);
        }
    }

    private KeyPair createKeyPair() throws IOException {
        try {
            return X25519.createKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot create client X25519 key pair", e);
        }
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
        loadServerKey();
        socket.connect(new java.net.InetSocketAddress(host, port), 10000);
        socket.setSoTimeout(10000);
        PacketChannel channel = secureChannel(socket);
        authenticate(channel, username, password, peerPort);
        socket.setSoTimeout(0);
        return channel;
    }
}

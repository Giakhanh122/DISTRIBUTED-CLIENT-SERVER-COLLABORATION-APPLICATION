package protocols;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Wire format: OP:u8 | RequestID:i32 | PayloadLength:i32 | Data:N bytes. All
 * multibyte numbers use
 * big-endian byte order.
 */
public final class PacketCodec {
    public static final int HEADER_LENGTH = 9;
    public static final int MAX_PAYLOAD_LENGTH = 1024 * 1024;

    private PacketCodec() {
    }

    public static Packet read(InputStream input) throws IOException {
        DataInputStream reader = new DataInputStream(input);
        int code = reader.read();
        if (code == -1) {
            return null;
        }

        OpCode op = OpCode.fromCode(code);
        int requestId = reader.readInt();
        int payloadLength = reader.readInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Invalid payload length: " + payloadLength);
        }

        byte[] data = new byte[payloadLength];
        reader.readFully(data);
        return new Packet(op, requestId, data);
    }

    public static void write(OutputStream output, Packet packet) throws IOException {
        if (packet.payloadLength() > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Invalid payload length: " + packet.payloadLength());
        }
        DataOutputStream writer = new DataOutputStream(output);
        // writer.writeByte(packet.op().getCode());
        // writer.writeInt(packet.requestId());
        // writer.writeInt(packet.payloadLength());
        // writer.write(packet.data());

        writer.write(packet.toBytes());

        writer.flush();
    }

    public static Packet fromBytes(byte[] bytes) throws IOException {
        // int MAX_PAYLOAD_LENGTH = 1024 * 1024;
        ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);

        DataInputStream reader = new DataInputStream(byteStream);

        int code = reader.readUnsignedByte();

        OpCode op = OpCode.fromCode(code);

        int requestId = reader.readInt();

        int payloadLength = reader.readInt();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new IOException("Invalid payload length");
        }
        if (byteStream.available() != payloadLength) {
            throw new IOException("Invalid packet length");
        }

        byte[] payload = new byte[payloadLength];

        reader.readFully(payload);

        return new Packet(op, requestId, payload);
    }

    public static void sendUDP(DatagramSocket socket, InetAddress address, int port, Packet packet) {
        byte[] message = packet.toBytes();
        // DatagramPacket UDPPacket = new DatagramPacket(message, message.length,
        // address, port);
        //
        DatagramPacket UDPPacket = new DatagramPacket(message, message.length, address, port);

        try {
            socket.send(UDPPacket);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }
}

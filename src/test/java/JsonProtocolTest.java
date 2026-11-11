import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import protocols.*;
import server.auth.AuthService;
import server.broadcast.BroadcastFileRegistry;
import server.community.*;
import server.connection.ClientHandler;
import server.session.*;
import server.storage.FileStorageService;
import transfer.*;

public class JsonProtocolTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static byte[] frame(String json) throws IOException {
        var out = new ByteArrayOutputStream(); var writer = new DataOutputStream(out);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        writer.writeInt(bytes.length); writer.write(bytes); return out.toByteArray();
    }
    static void invalid(byte[] bytes) throws Exception {
        try { var input = new ByteArrayInputStream(bytes); while (PacketCodec.read(input) != null) { } throw new AssertionError("Accepted invalid frame"); }
        catch (IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        var out = new ByteArrayOutputStream();
        String content = "Chào 😀\n\t\"\\";
        PacketCodec.write(out, Packet.json(OpCode.CHAT_MESSAGE, 7, Json.object("content", content)));
        byte[] binary = new byte[65536]; new Random(9).nextBytes(binary);
        PacketCodec.write(out, new Packet(OpCode.FILE_CHUNK, 0, Json.object("chunkIndex", 0), binary));
        var input = new FilterInputStream(new ByteArrayInputStream(out.toByteArray())) {
            public int read(byte[] b, int offset, int length) throws IOException { return super.read(b, offset, Math.min(length, 1)); }
        };
        check(PacketCodec.read(input).string("content").equals(content));
        check(Arrays.equals(PacketCodec.read(input).binary(), binary)); check(PacketCodec.read(input) == null);
        for (String json : List.of("{}", "{bad}",
                "{\"opcode\":1,\"opcode\":2,\"requestId\":1,\"dataLength\":0,\"payload\":{}}",
                "{\"opcode\":1,\"requestId\":1.0,\"dataLength\":0,\"payload\":{}}",
                "{\"opcode\":1,\"requestId\":-1,\"dataLength\":0,\"payload\":{}}",
                "{\"opcode\":1,\"requestId\":1,\"dataLength\":1,\"payload\":{}}",
                "{\"opcode\":66,\"requestId\":0,\"dataLength\":65537,\"payload\":{}}",
                "{\"opcode\":255,\"requestId\":1,\"dataLength\":0,\"payload\":{}}")) invalid(frame(json));
        invalid(new byte[]{0,0}); invalid(new byte[]{127,-1,-1,-1});
        invalid(Arrays.copyOf(out.toByteArray(), out.size()-1));
        System.out.println("PASS: strict JSON, Unicode, fragmented/coalesced frames, raw binary, malformed/truncated frames");
        var users = new UserSessionRegistry(); var chats = new ChatSessionRegistry();
        var communities = new CommunityService(new CommunityRegistry(), users);
        var files = new BroadcastFileRegistry(); var storage = new FileStorageService(Path.of("storage"));
        try (ServerSocket listener = new ServerSocket(0)) {
            Thread.startVirtualThread(() -> {
                try { while (!listener.isClosed()) {
                    Socket socket = listener.accept();
                    Thread.startVirtualThread(new ClientHandler(socket, users, chats, new AuthService(), communities, files, storage));
                } } catch (IOException ignored) { }
            });
            try (Wire alice = new Wire(listener.getLocalPort(), "alice", "alice123");
                 Wire bob = new Wire(listener.getLocalPort(), "bob", "bob123")) {
                String name = "Nhóm\t\"JSON\"";
                Packet created = alice.request(OpCode.COMMUNITY_CREATE_REQUEST, Json.object("name", name));
                String id = created.string("communityId"); check(created.string("name").equals(name));
                check(bob.request(OpCode.COMMUNITY_JOIN_REQUEST, Json.object("communityId", id)).status().equals("OK"));
                check(Json.array(alice.request(OpCode.COMMUNITY_MEMBERS_REQUEST, Json.object("communityId", id)).payload(), "members").size()==2);
                alice.request(OpCode.COMMUNITY_LIST_REQUEST, Json.object());
                alice.request(OpCode.COMMUNITY_INFO_REQUEST, Json.object("communityId", id));
                PacketCodec.write(alice.socket.getOutputStream(), Packet.json(OpCode.CHAT_MESSAGE_BROADCAST, 99, Json.object("communityId", id, "content", content)));
                check(PacketCodec.read(bob.socket.getInputStream()).string("content").equals(content));
                for (byte[] data : List.of(binary, new byte[0])) {
                    Path source = Path.of("source.bin"); Files.write(source, data);
                    String checksum = FileSender.checksum(source.toFile());
                    Packet offer = alice.request(OpCode.FILE_BROADCAST_UPLOAD_REQUEST, Json.object("communityId", id,
                            "fileName", "tệp\tfile.bin", "fileSize", data.length, "checksum", checksum));
                    String fileId = offer.string("fileId");
                    try (Socket upload = new Socket("127.0.0.1", listener.getLocalPort())) {
                        upload.setSoTimeout(5000);
                        PacketCodec.write(upload.getOutputStream(), Packet.json(OpCode.FILE_BROADCAST_UPLOAD_START, 0,
                                Json.object("fileId", fileId, "uploadToken", offer.string("uploadToken"))));
                        FileSender sender = new FileSender(source.toFile(), fileId, upload.getOutputStream());
                        sender.send(); sender.awaitResult(upload.getInputStream());
                    }
                    Packet event = PacketCodec.read(bob.socket.getInputStream()); check(event.string("fileId").equals(fileId));
                    Packet decision = bob.request(OpCode.FILE_BROADCAST_DECISION, Json.object("fileId", fileId, "decision", "ACCEPT"));
                    try (Socket download = new Socket("127.0.0.1", listener.getLocalPort())) {
                        download.setSoTimeout(5000);
                        PacketCodec.write(download.getOutputStream(), Packet.json(OpCode.FILE_BROADCAST_DOWNLOAD_START, 0,
                                Json.object("fileId", fileId, "downloadToken", decision.string("downloadToken"))));
                        var meta = new FileTransferMeta(fileId, "alice", "bob", "tệp\tfile.bin", data.length, checksum);
                        File saved = new FileReceiver(download.getInputStream(), download.getOutputStream(), Path.of("received").toFile(), meta).receive();
                        check(Arrays.equals(data, Files.readAllBytes(saved.toPath())));
                    }
                }
                check(bob.request(OpCode.COMMUNITY_LEAVE_REQUEST, Json.object("communityId", id)).status().equals("OK"));
            }
        }
        System.out.println("PASS: community, broadcast chat, binary/empty upload and download over real TCP");
    }
    static class Wire implements AutoCloseable {
        final Socket socket; int sequence;
        Wire(int port, String username, String password) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(5000);
            request(OpCode.HELLO, Json.object());
            check(request(OpCode.AUTH_REQUEST, Json.object("username", username, "password", password, "peerPort", 12345)).status().equals("OK"));
        }
        Packet request(OpCode op, com.google.gson.JsonObject payload) throws IOException {
            int id = ++sequence;
            PacketCodec.write(socket.getOutputStream(), Packet.json(op, id, payload));
            Packet response = PacketCodec.read(socket.getInputStream()); check(response.requestId() == id); return response;
        }
        public void close() throws IOException { socket.close(); }
    }
}

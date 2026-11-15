package protocols;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProtocolV2Test {
    private Packet read(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return PacketCodec.read(new ByteArrayInputStream(ByteBuffer.allocate(4 + bytes.length)
                .putInt(bytes.length).put(bytes).array()));
    }

    private Packet roundTrip(Packet packet, long sequence) throws IOException {
        var output = new ByteArrayOutputStream();
        PacketCodec.write(output, packet, sequence);
        return PacketCodec.read(new ByteArrayInputStream(output.toByteArray()));
    }

    @Test void loginUsesAuthGroupAndRequiredType() throws Exception {
        Packet login = roundTrip(Packet.json(AuthType.LOGIN_REQUEST,
                Json.object("username", "alice", "password", "secret", "peerPort", 9000)), 3);
        assertEquals(2, login.op().getCode());
        assertEquals(AuthType.LOGIN_REQUEST, login.type());
        assertEquals("LOGIN_REQUEST", login.string("type"));
        assertEquals(3, login.sequence());
        assertEquals("alice", login.string("username"));
    }

    @Test void rejectsUnknownMissingAndWrongGroupTypes() {
        for (String json : new String[]{
                "{\"opcode\":2,\"sequence\":1,\"body\":{}}",
                "{\"opcode\":2,\"sequence\":1,\"body\":{\"type\":7}}",
                "{\"opcode\":2,\"sequence\":1,\"body\":{\"type\":\"MESSAGE\"}}",
                "{\"opcode\":4,\"sequence\":1,\"body\":{\"type\":\"UNKNOWN\"}}",
                "{\"opcode\":99,\"sequence\":1,\"body\":{\"type\":\"PING\"}}",
                "{\"opcode\":1,\"sequence\":1,\"body\":{\"protocolVersion\":1}}"}) {
            assertThrows(IOException.class, () -> read(json), json);
        }
    }

    @Test void responseMustMatchGroupTypeAndRequestSequence() throws Exception {
        Packet users = roundTrip(Packet.status(UserType.LIST_RESPONSE, 7, "OK"), 4);
        assertSame(users, PacketValidator.validateResponse(users, UserType.LIST_RESPONSE, 7));
        assertThrows(IOException.class, () -> PacketValidator.validateResponse(users, CommunityType.LIST_RESPONSE, 7));
        assertThrows(IOException.class, () -> PacketValidator.validateResponse(users, UserType.PEER_LOOKUP_RESPONSE, 7));
        assertThrows(IOException.class, () -> PacketValidator.validateResponse(users, UserType.LIST_RESPONSE, 8));
    }

    @Test void emptyRequestsAllowTypeButRejectExtraPayload() throws Exception {
        Packet request = roundTrip(Packet.empty(UserType.LIST_REQUEST), 1);
        PacketValidator.validateRequest(request, UserType.LIST_REQUEST, 0, 0);
        Packet extra = roundTrip(Packet.json(UserType.LIST_REQUEST, Json.object("extra", true)), 1);
        assertThrows(IOException.class, () -> PacketValidator.validateRequest(extra, UserType.LIST_REQUEST, 0, 0));
    }

    @Test void systemResponsesRequireStatusAndPositiveReplyTo() {
        assertThrows(IOException.class, () -> roundTrip(Packet.json(SystemType.PONG, Json.object("replyTo", 1)), 1));
        assertThrows(IOException.class, () -> roundTrip(Packet.status(SystemType.KEY_CONFIRM_ACK, 0, "OK"), 1));
        assertThrows(IOException.class, () -> roundTrip(Packet.status(SystemType.PONG, 1, " "), 1));
        assertThrows(IOException.class, () -> roundTrip(Packet.empty(SystemType.ERROR), 1));
    }

    @Test void fileDecisionRemainsCorrelatedAndEarlyFailureAllowsZero() throws Exception {
        Packet decision = roundTrip(Packet.reply(FileType.DECISION, 9,
                Json.object("transferId", "id", "decision", "ACCEPT")), 2);
        assertEquals(9, decision.replyTo());
        assertThrows(IOException.class, () -> roundTrip(Packet.empty(FileType.DECISION), 2));
        assertEquals(0, roundTrip(Packet.failure(0, "early failure"), 1).replyTo());
    }

    @Test void packetFactoriesOwnTypeAndCopyCallerPayload() throws Exception {
        var body = Json.object("content", "hello");
        Packet message = Packet.json(ChatType.MESSAGE, body);
        assertFalse(body.has("type"));
        body.addProperty("content", "changed");
        assertEquals("hello", message.string("content"));
        assertThrows(IllegalArgumentException.class,
                () -> Packet.json(AuthType.LOGIN_REQUEST, Json.object("type", "MESSAGE")));
    }
}

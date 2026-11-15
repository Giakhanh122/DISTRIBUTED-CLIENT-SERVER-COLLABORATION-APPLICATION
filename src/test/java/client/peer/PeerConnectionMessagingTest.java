package client.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import network.PacketChannel;
import org.junit.jupiter.api.Test;
import protocols.ChatType;
import protocols.Json;
import protocols.Packet;

class PeerConnectionMessagingTest {
    @Test
    void exchangesMessageReceiptsAndTypingEvents() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Socket clientSocket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            Socket serverSocket = listener.accept();
            clientSocket.setSoTimeout(2000);
            serverSocket.setSoTimeout(2000);

            try (var peer = new PeerConnection(new PacketChannel(clientSocket));
                    var remote = new PacketChannel(serverSocket)) {
                BlockingQueue<String> messages = new LinkedBlockingQueue<>();
                BlockingQueue<String> receipts = new LinkedBlockingQueue<>();
                BlockingQueue<Boolean> typing = new LinkedBlockingQueue<>();

                Future<?> reader = executor.submit(() -> {
                    try {
                        peer.readMessages("alice", (id, content) -> messages.add(id + ":" + content),
                                packet -> fail("Unexpected file packet"), receipts::add, typing::add);
                    } catch (IOException expectedOnClose) {
                        return;
                    }
                });

                String incomingId = "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011";
                remote.send(Packet.json(ChatType.MESSAGE,
                        Json.object("messageId", incomingId, "content", "hello")));
                Packet delivered = remote.read();
                assertEquals(ChatType.RECEIPT, delivered.type());
                assertEquals(incomingId, delivered.string("messageId"));
                assertEquals("DELIVERED", delivered.string("receipt"));
                assertEquals(incomingId + ":hello", messages.poll(2, TimeUnit.SECONDS));

                remote.send(Packet.json(ChatType.TYPING, Json.object("typing", true)));
                assertEquals(Boolean.TRUE, typing.poll(2, TimeUnit.SECONDS));

                String outgoingId = "dfba9424-53a1-4f90-b2ef-90df341b8621";
                peer.sendMessage(outgoingId, "reply");
                Packet message = remote.read();
                assertEquals(ChatType.MESSAGE, message.type());
                assertEquals(outgoingId, message.string("messageId"));
                assertEquals("reply", message.string("content"));

                peer.sendTyping(false);
                Packet stoppedTyping = remote.read();
                assertEquals(ChatType.TYPING, stoppedTyping.type());
                assertFalse(stoppedTyping.bool("typing"));

                remote.send(Packet.json(ChatType.RECEIPT,
                        Json.object("messageId", outgoingId, "receipt", "DELIVERED")));
                assertEquals(outgoingId, receipts.poll(2, TimeUnit.SECONDS));

                remote.close();
                reader.get(2, TimeUnit.SECONDS);
            }
        }
    }
}

package transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import network.PacketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import protocols.FileTransferMeta;
import protocols.Json;
import protocols.Packet;
import protocols.type.FileType;

@Timeout(10)
class TransferIntegrityTest {
    @TempDir Path temp;

    @Test
    void interruptedStreamDeletesPartialOutput() throws Exception {
        var meta = FileTransferMeta.create("alice", "bob", "interrupted.bin", 1024, "0".repeat(64));
        Path output = temp.resolve("interrupted");
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                var remote = new PacketChannel(listener.accept()); var local = new PacketChannel(socket);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            socket.setSoTimeout(2000); remote.socket().setSoTimeout(2000);
            var result = executor.submit(() -> assertThrows(IOException.class,
                    () -> new FileReceiver(local, output.toFile(), meta).receive()));
            remote.writeRaw(new byte[]{1, 2, 3}, 0, 3);
            remote.socket().shutdownOutput();
            assertNull(remote.read());
            result.get(3, TimeUnit.SECONDS);
            try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        }
    }

    @Test
    void corruptFileIsRejectedAndPartialOutputIsDeleted() throws Exception {
        Path expected = Files.writeString(temp.resolve("original"), "ok!");
        FileTransferMeta meta = FileTransferMeta.create("alice", "bob", "../../payload.bin", 3,
                FileSender.checksum(expected.toFile()));
        Path output = temp.resolve("received");
        try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                var socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                var remote = new PacketChannel(listener.accept()); var local = new PacketChannel(socket);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            socket.setSoTimeout(2000); remote.socket().setSoTimeout(2000);
            var result = executor.submit(() -> assertThrows(IOException.class,
                    () -> new FileReceiver(local, output.toFile(), meta).receive()));
            remote.writeRaw(new byte[]{1, 2, 3}, 0, 3);
            remote.send(Packet.json(FileType.END, Json.object("transferId", meta.transferId(),
                    "checksum", meta.checksum())));
            assertEquals("FAIL", remote.read().status());
            assertTrue(result.get(3, TimeUnit.SECONDS).getMessage().contains("Checksum mismatch"));
            try (var files = Files.list(output)) { assertEquals(0, files.count()); }
            assertFalse(Files.exists(temp.resolve("payload.bin")));
        }
    }
}

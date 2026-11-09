package transfer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class FileSender {
    public static final int CHUNK_SIZE = 64 * 1024;

    private final File file;
    private final String transferId;
    private final OutputStream output;

    public FileSender(File file, String transferId, OutputStream output) {
        this.file = file;
        this.transferId = transferId;
        this.output = output;
    }

    public String send() throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int chunkIndex = 0;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
                byte[] chunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                PacketCodec.write(output, new Packet(OpCode.FILE_CHUNK, chunkIndex++, chunk));
            }
        }

        String checksum = HexFormat.of().formatHex(digest.digest());
        PacketCodec.write(output, new Packet(OpCode.FILE_END, chunkIndex, checksum.getBytes(StandardCharsets.UTF_8)));
        return checksum;
    }
}

package transfer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;

public class FileReceiver {
    private final InputStream input;
    private final OutputStream output;
    private final File saveDir;
    private final FileTransferMeta meta;

    public FileReceiver(InputStream input, OutputStream output, File saveDir, FileTransferMeta meta) {
        this.input = input;
        this.output = output;
        this.saveDir = saveDir;
        this.meta = meta;
    }

    public File receive() throws IOException {
        if (!saveDir.exists()) saveDir.mkdirs();
        String safeName = new File(meta.fileName()).getName();
        File targetFile = new File(saveDir, safeName);
        File tempFile = new File(saveDir, "." + meta.transferId() + ".tmp");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String remoteChecksum = null;
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            while (true) {
                Packet packet = PacketCodec.read(input);
                if (packet == null) throw new IOException("Connection closed");

                if (packet.op() == OpCode.FILE_CHUNK) {
                    fos.write(packet.data());
                    digest.update(packet.data());
                } else if (packet.op() == OpCode.FILE_END) {
                    remoteChecksum = new String(packet.data(), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (Exception e) {
            if (tempFile.exists()) tempFile.delete();
            throw e;
        }

        String localChecksum = HexFormat.of().formatHex(digest.digest());
        if (remoteChecksum != null && !localChecksum.equalsIgnoreCase(remoteChecksum)) {
            tempFile.delete();
            throw new IOException("Checksum mismatch");
        }

        if (targetFile.exists()) {
            targetFile = new File(saveDir, System.currentTimeMillis() + "_" + safeName);
        }
        tempFile.renameTo(targetFile);

        if (output != null) {
            PacketCodec.write(output, new Packet(OpCode.FILE_RESULT, 0, "OK".getBytes(StandardCharsets.UTF_8)));
        }
        return targetFile;
    }
}

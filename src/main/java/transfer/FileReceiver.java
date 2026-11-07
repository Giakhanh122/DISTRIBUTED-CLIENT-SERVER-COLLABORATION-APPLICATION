package filetransfer;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;

public class FileReceiver {
    private final String transferId;
    private final String expectedFileName;
    private final long expectedFileSize;
    private final String expecteChecksum;
    private final File tempFile;
    private FileOutputStream fileOutput;
    private MessageDigest digest;
    private int chunksReceived;
}
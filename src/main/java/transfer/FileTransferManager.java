package transfer;

import jav.util.concurrent.ConcurrentHashMap;

public class FileTransferManager {
    private final ConcurrentHashMap<string, FiletransferSession> activeSessions = new ConcurrentHashMap<>();
}
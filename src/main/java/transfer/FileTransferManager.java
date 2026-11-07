package transfer;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FileTransferManager {
    private final ConcurrentHashMap<String, FileTransferSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, File> outboundFiles = new ConcurrentHashMap<>();

    public void registerSession(FileTransferSession session) {
        if (session != null) {
            activeSessions.put(session.transferId(), session);
        }
    }

    public void registerOutboundFile(String transferId, File file) {
        if (transferId != null && file != null) {
            outboundFiles.put(transferId, file);
        }
    }

    public File getOutboundFile(String transferId) {
        return outboundFiles.get(transferId);
    }

    public Optional<FileTransferSession> getSession(String transferId) {
        if (transferId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSessions.get(transferId));
    }

    public void removeSession(String transferId) {
        if (transferId != null) {
            activeSessions.remove(transferId);
            outboundFiles.remove(transferId);
        }
    }

    public Collection<FileTransferSession> getActiveSessions() {
        return Collections.unmodifiableCollection(activeSessions.values());
    }
}

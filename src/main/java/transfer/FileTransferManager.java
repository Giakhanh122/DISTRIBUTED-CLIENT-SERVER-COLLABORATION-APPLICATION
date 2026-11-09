package transfer;

import java.io.File;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FileTransferManager {
    private final ConcurrentHashMap<String, FileTransferSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, File> outboundFiles = new ConcurrentHashMap<>();

    public void registerSession(FileTransferSession session) {
        sessions.put(session.transferId(), session);
    }

    public void registerOutboundFile(String transferId, File file) {
        outboundFiles.put(transferId, file);
    }

    public File getOutboundFile(String transferId) {
        return outboundFiles.get(transferId);
    }

    public Optional<FileTransferSession> getSession(String transferId) {
        return Optional.ofNullable(sessions.get(transferId));
    }

    public Optional<FileTransferSession> findPending(String query) {
        if (query == null || query.isBlank()) {
            return sessions.values().stream().filter(s -> s.state() == FileTransferSession.State.OFFERED).findFirst();
        }
        String q = query.trim().toLowerCase();
        for (FileTransferSession s : sessions.values()) {
            if (s.state() == FileTransferSession.State.OFFERED) {
                if (s.transferId().toLowerCase().startsWith(q) || s.meta().fileName().toLowerCase().contains(q)) {
                    return Optional.of(s);
                }
            }
        }
        return Optional.empty();
    }

    public boolean hasOngoingTransfer() {
        return sessions.values().stream().anyMatch(s ->
                s.state() == FileTransferSession.State.ACCEPTED || s.state() == FileTransferSession.State.TRANSFERRING);
    }

    public Collection<FileTransferSession> getSessions() {
        return sessions.values();
    }
}

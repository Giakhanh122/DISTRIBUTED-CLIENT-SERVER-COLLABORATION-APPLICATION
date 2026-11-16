package client.transfer;

import protocols.FileTransferMeta;

/** Local offer handle and wire metadata; localPath is never included in packets. */
public record FileOffer(String id, String number, FileTransferMeta meta, String communityId,
                        boolean incoming, String localPath) {
    public FileOffer(String id, String number, FileTransferMeta meta, String communityId, boolean incoming) {
        this(id, number, meta, communityId, incoming, "");
    }
}

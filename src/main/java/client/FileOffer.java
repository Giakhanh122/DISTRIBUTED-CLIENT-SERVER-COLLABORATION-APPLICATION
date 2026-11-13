package client;

import protocols.FileTransferMeta;

/** Local offer handle and wire metadata; the short number is shared by P2P and broadcast. */
public record FileOffer(String id, String number, FileTransferMeta meta, String communityId, boolean incoming) {}

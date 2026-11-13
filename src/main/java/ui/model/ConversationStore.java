package ui.model;

import java.util.HashMap;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/** Conversation history owned by the JavaFX thread; keys always use explicit peer/community IDs. */
public final class ConversationStore {
    public record Entry(String key, ChatMessageModel message) {}
    private final Map<String, ObservableList<ChatMessageModel>> histories = new HashMap<>();

    public static String directKey(String peer) { return "peer-" + peer; }
    public static String communityKey(String id) { return "comm-" + id; }

    public ObservableList<ChatMessageModel> history(String key) {
        return histories.computeIfAbsent(key, ignored -> FXCollections.observableArrayList());
    }

    public void add(String key, ChatMessageModel message) { history(key).add(message); }

    public static Entry fileOffer(FileOfferModel offer) {
        String key;
        if (offer.isBroadcast()) {
            String id = offer.getCommunityId();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Broadcast file is missing community ID");
            key = communityKey(id);
            if (offer.isIncoming()) offer.setStatusText("Community file shared. Accept to download or reject.");
        } else key = directKey(offer.isIncoming() ? offer.getSender() : offer.getReceiver());
        return new Entry(key, ChatMessageModel.file(offer.getSender(), offer, !offer.isIncoming()));
    }
}

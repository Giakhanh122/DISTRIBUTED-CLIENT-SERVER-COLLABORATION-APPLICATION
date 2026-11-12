package ui.model;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class CommunityModel {
    private final StringProperty id = new SimpleStringProperty();
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty owner = new SimpleStringProperty();
    private final IntegerProperty memberCount = new SimpleIntegerProperty(0);
    private final BooleanProperty joined = new SimpleBooleanProperty(false);
    private final IntegerProperty unreadCount = new SimpleIntegerProperty(0);

    public CommunityModel(String id, String name, String owner, int memberCount, boolean joined) {
        this.id.set(id);
        this.name.set(name);
        this.owner.set(owner);
        this.memberCount.set(memberCount);
        this.joined.set(joined);
    }

    public String getId() { return id.get(); }
    public StringProperty idProperty() { return id; }

    public String getName() { return name.get(); }
    public StringProperty nameProperty() { return name; }

    public String getOwner() { return owner.get(); }
    public StringProperty ownerProperty() { return owner; }

    public int getMemberCount() { return memberCount.get(); }
    public IntegerProperty memberCountProperty() { return memberCount; }
    public void setMemberCount(int count) { this.memberCount.set(count); }

    public boolean isJoined() { return joined.get(); }
    public BooleanProperty joinedProperty() { return joined; }
    public void setJoined(boolean joined) { this.joined.set(joined); }

    public int getUnreadCount() { return unreadCount.get(); }
    public IntegerProperty unreadCountProperty() { return unreadCount; }
    public void incrementUnread() { this.unreadCount.set(this.unreadCount.get() + 1); }
    public void clearUnread() { this.unreadCount.set(0); }

    public String getInitials() {
        String n = getName();
        if (n == null || n.isBlank()) return "#";
        return n.length() <= 2 ? n.toUpperCase() : n.substring(0, 2).toUpperCase();
    }

    public void updateFrom(CommunityModel latest) {
        name.set(latest.getName());
        owner.set(latest.getOwner());
        memberCount.set(latest.getMemberCount());
        joined.set(latest.isJoined());
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof CommunityModel community
                && java.util.Objects.equals(getId(), community.getId());
    }

    @Override
    public int hashCode() { return java.util.Objects.hashCode(getId()); }

    @Override
    public String toString() {
        return getName();
    }
}

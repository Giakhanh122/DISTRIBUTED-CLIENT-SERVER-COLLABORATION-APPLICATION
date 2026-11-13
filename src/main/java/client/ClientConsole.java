package client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.Scanner;
import protocols.Json;

/** Console input, command parsing, and presentation; networking lives in the session. */
public final class ClientConsole {
    record Credentials(String username, String password) {}
    private final ClientSession session;
    private final CommunityClient communities;

    ClientConsole(ClientSession session) {
        this.session = session;
        this.communities = session.communities();
    }

    public static void start(String host, int port, Scanner scanner) {
        Client client = new Client();
        try {
            if (host == null || host.isBlank()) {
                Client.ServerInfo info = client.discover();
                if (info == null) { System.out.println("No server found"); return; }
                host = info.address();
                port = info.port();
                System.out.println("Server found: " + host + ":" + port);
            }
            Credentials credentials = readCredentials(scanner);
            if (credentials == null) return;
            try (ClientSession session = client.login(host, port, credentials.username(), credentials.password(), new ConsoleEvents())) {
                session.start();
                new ClientConsole(session).run(scanner);
            }
        } catch (IOException e) { System.err.println("Client error: " + e.getMessage()); }
    }

    static Credentials readCredentials(Scanner scanner) {
        System.out.print("Username: ");
        if (!scanner.hasNextLine()) return null;
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        if (!scanner.hasNextLine()) return null;
        String password = scanner.nextLine();
        if (username.isEmpty() || password.isEmpty()) {
            System.err.println("Username and password must not be empty");
            return null;
        }
        return new Credentials(username, password);
    }

    void run(Scanner scanner) {
        printHelp();
        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            if (message.isBlank()) continue;
            String[] parts = message.trim().split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            String argument = parts.length == 2 ? parts[1] : "";
            if (command.equals("/logout")) return;
            try {
                switch (command) {
                    case "/help" -> printHelp();
                    case "/reconnect" -> {
                        if (session.isServerConnected()) System.out.println("Server is already connected");
                        else {
                            System.out.print("Password for " + session.username() + ": ");
                            if (!scanner.hasNextLine()) return;
                            session.reconnect(scanner.nextLine());
                        }
                    }
                    case "/users" -> printUsers(session.users());
                    case "/chats" -> printChats(session.chats());
                    case "/communities" -> printCommunities();
                    case "/community" -> communityCommand(argument);
                    case "/lookup" -> lookup(argument);
                    case "/chat" -> selectChat(argument);
                    case "/sendfile" -> sendFile(argument);
                    case "/accept" -> session.accept(required(argument, "Usage: /accept <offer_number>"));
                    case "/reject" -> session.reject(required(argument, "Usage: /reject <offer_number>"));
                    case "/broadcast" -> broadcast(argument);
                    default -> {
                        if (!session.sendMessage(message)) System.out.println("Connect to a peer before sending messages.");
                    }
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                System.err.println(errorPrefix(command) + e.getMessage());
            }
        }
    }

    private void lookup(String target) throws IOException {
        target = required(target.trim(), "Usage: /lookup <username>");
        if (target.equals(session.username())) {
            System.out.println("Cannot connect to yourself");
            return;
        }
        // Reuse an established P2P connection even if the server is unavailable.
        if (session.selectPeer(target)) {
            System.out.println("Now chatting with " + target);
            return;
        }
        try {
            PeerLookupResult result = session.lookup(target);
            switch (result.status()) {
                case USER_OFFLINE -> {
                    session.clearPeerSelection();
                    System.out.println(target + " is offline");
                }
                case ALREADY_CONNECTED -> {
                    if (session.selectPeer(target)) System.out.println("Now chatting with " + target);
                    else System.out.println(target + " is already connected, but no local connection is available");
                }
                case SUCCESS -> {
                    try {
                        session.connectPeer(target, result.address());
                        if (session.selectPeer(target)) System.out.println("Now chatting with " + target);
                    } catch (IOException e) {
                        session.clearPeerSelection();
                        System.err.println("Cannot connect to " + target + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            session.clearPeerSelection();
            throw e;
        }
    }

    private void selectChat(String target) {
        target = target.trim();
        if (target.isEmpty()) {
            String selected = session.selectedPeer();
            System.out.println("Current peer: " + (selected == null ? "(none)" : selected));
            System.out.println("Connected peers:");
            var peers = session.connectedPeers();
            if (peers.isEmpty()) System.out.println("(none)");
            for (String peer : peers) {
                System.out.println("- " + peer + (peer.equals(selected) ? " [SELECTED]" : ""));
            }
        } else if (session.selectPeer(target)) {
            System.out.println("Now chatting with " + target);
        } else {
            System.out.println("Not connected to " + target + ". Use /lookup " + target + " first.");
        }
    }

    private void sendFile(String argument) {
        String[] parts = argument.split("\\s+", 2);
        if (parts.length != 2 || parts[0].isEmpty()) {
            System.out.println("Usage: /sendfile <user> <path>");
            return;
        }
        session.sendFile(parts[0], new File(parts[1]));
    }

    private void broadcast(String argument) throws IOException {
        String[] parts = argument.split("\\s+", 2);
        String option = parts[0].toLowerCase(Locale.ROOT);
        if (option.equals("-h")) {
            System.out.println("""
                    Broadcast commands:
                      /broadcast -m <message>   Send a text message to active community
                      /broadcast -f <filepath>  Send a file to active community
                      /broadcast -h             Show this help
                    """);
            return;
        }
        if (parts.length != 2 || (!option.equals("-m") && !option.equals("-f"))) {
            System.out.println("Usage: /broadcast <-m|-f> <message/filepath>");
            return;
        }
        if (option.equals("-m")) session.broadcastMessage(parts[1].trim());
        else session.broadcastFile(new File(parts[1].trim()));
    }

    private void communityCommand(String argument) throws IOException {
        if (argument.isEmpty()) {
            printActiveCommunity();
            return;
        }
        String[] parts = argument.split("\\s+", 2);
        String value = parts.length == 2 ? parts[1].trim() : "";
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                CommunityClient.Selection selected = communities.create(required(value, "Usage: /community create <name>"));
                System.out.println("Community '" + selected.name() + "' created! ID: " + selected.id());
                printSelection(selected);
            }
            case "join" -> {
                CommunityClient.Selection selected = communities.join(required(value, "Usage: /community join <communityId>"));
                System.out.println("Joined community '" + selected.name() + "'!");
                printSelection(selected);
            }
            case "select" -> printSelection(communities.select(required(value, "Usage: /community select <id>")));
            case "leave" -> System.out.println("Left community '" + communities.leave().name() + "'.");
            case "members" -> printMembers();
            case "info" -> printCommunityInfo();
            default -> System.out.println("Unknown community command. Use /help for available commands.");
        }
    }

    private void printCommunities() throws IOException {
        List<CommunityInfo> entries = communities.list();
        if (entries.isEmpty()) {
            System.out.println("No communities available. Create one with /community create <name>");
            return;
        }
        System.out.println("Communities:");
        for (CommunityInfo community : entries) {
            String marker = community.member() ? " [JOINED]" : "";
            System.out.println("  " + community.name() + " | ID: " + community.id()
                    + " | Owner: " + community.owner() + " | Members: " + community.memberCount() + marker);
        }
    }

    private void printMembers() throws IOException {
        CommunityClient.Selection selected = communities.requireSelection();
        JsonArray members = communities.members();
        System.out.println("Members of '" + selected.name() + "':");
        if (members.isEmpty()) System.out.println("  (none)");
        for (JsonElement entry : members) {
            JsonObject member = Json.asObject(entry);
            String status = Json.bool(member, "online") ? "online" : "offline";
            System.out.println("  - " + Json.string(member, "username") + " (" + status + ")");
        }
    }

    private void printCommunityInfo() throws IOException {
        JsonObject community = communities.info();
        System.out.println("Community Info:");
        System.out.println("  ID: " + Json.string(community, "communityId"));
        System.out.println("  Name: " + Json.string(community, "name"));
        System.out.println("  Owner: " + Json.string(community, "owner"));
        System.out.println("  Members: " + Json.integer(community, "memberCount"));
        System.out.println("  You are a member: " + Json.bool(community, "isMember"));
        System.out.println("  Created: " + Json.string(community, "createdAt"));
    }

    private void printActiveCommunity() {
        CommunityClient.Selection selected = communities.selected();
        if (selected == null) {
            System.out.println("No active community. Use /community create <name>, /community join <id>, or /community select <id>.");
        } else {
            System.out.println("[Community: " + selected.name() + "] (ID: " + selected.id() + ")");
        }
    }

    private void printSelection(CommunityClient.Selection selected) {
        System.out.println("[Community: " + selected.name() + "] selected.");
    }

    private void printUsers(List<String> users) {
        System.out.println("Online users:");
        if (users.isEmpty()) System.out.println("(none)");
        users.forEach(user -> System.out.println("- " + user));
    }

    private void printChats(JsonArray sessions) throws IOException {
        System.out.println("Chat sessions:");
        if (sessions.isEmpty()) System.out.println("(none)");
        for (JsonElement entry : sessions) {
            JsonObject chat = Json.asObject(entry);
            System.out.println("- " + Json.string(chat, "userA") + " <-> " + Json.string(chat, "userB")
                    + ": " + Json.string(chat, "state") + " | id=" + Json.string(chat, "sessionId"));
        }
    }

    private static String required(String value, String usage) {
        if (value.isBlank()) throw new IllegalArgumentException(usage);
        return value;
    }

    private static String errorPrefix(String command) {
        return switch (command) {
            case "/users", "/chats", "/lookup", "/chat" -> "Server command: ";
            case "/sendfile", "/accept", "/reject" -> "File command: ";
            case "/community", "/communities" -> "Community command: ";
            case "/broadcast" -> "Broadcast failed: ";
            default -> "Cannot send message: ";
        };
    }

    private void printHelp() {
        CommunityClient.Selection selected = communities.selected();
        String communityPrompt = selected == null ? "" : "\n[Community: " + selected.name() + "]";
        System.out.println("""
                Commands:
                  COMMUNITY
                    /communities                    List discoverable communities
                    /community create <name>        Create a community
                    /community join <id>            Join a community
                    /community leave                Leave active community
                    /community members              List community members
                    /community select <id>          Select active community
                    /community info                 Show active community information
                    /community                      Show current community

                  MESSAGING
                    /broadcast -m <message>         Broadcast text in active community
                    /broadcast -f <filepath>        Broadcast a file in active community
                    /lookup <username>              Connect to or select a user for P2P chat
                    /chat [username]                Show peers or switch the active P2P chat
                    /sendfile <username> <filepath>  Send a private file through P2P

                  GENERAL
                    /reconnect                      Reauthenticate server; keep existing P2P connections
                    /users                          List online users
                    /chats                          List P2P chat sessions
                    /accept <offer_number>          Accept a file offer
                    /reject <offer_number>          Reject a file offer
                    /help                           Show this help
                    /logout                         Log out"""
                + communityPrompt);
    }
}

package client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import logic.PeerLookupResult;
import protocols.FileTransferMeta;
import protocols.OpCode;
import protocols.Packet;
import protocols.PacketCodec;
import protocols.PacketValidator;
import transfer.FileReceiver;
import transfer.FileSender;

public class Client {

    private static final int MAX_CHAT_BYTES = 64 * 1024;
    private String username;
    private int peerPort;
    private final PeerConnectionRegistry peerRegistry = new PeerConnectionRegistry();
    private final ConcurrentHashMap<String, PeerConnection> peers = peerRegistry.asMap();
    private final java.util.Set<String> pendingLookups = ConcurrentHashMap.newKeySet();
    private volatile java.util.function.Consumer<String> chatListener;
    private boolean closingPeers;
    private final AtomicInteger requestSequence = new AtomicInteger();
    private final Object serverWriteLock = new Object();
    private volatile OutputStream serverOutput;
    private final ConcurrentHashMap<Integer, CompletableFuture<Packet>> pendingResponses = new ConcurrentHashMap<>();

    private FileTransferController fileTransfers;

    // Active community context
    private volatile String activeCommunityId;
    private volatile String activeCommunityName;

    // Broadcast file offer tracking (short IDs for /accept /reject)
    private final ConcurrentHashMap<String, BroadcastOffer> broadcastOffers = new ConcurrentHashMap<>();
    private final AtomicInteger broadcastOfferSeq = new AtomicInteger();

    private record BroadcastOffer(String fileId, String sender, String fileName, long fileSize, String checksum) {}

    // Server host/port for data connections
    private String serverHost;
    private int serverPort;

    public record ServerInfo(String address, int port) {
    }

    private int nextRequestId() {
        return requestSequence.incrementAndGet();
    }

    public void start(String serverHost, int serverPort, Scanner scanner) {
        try {
            if (serverHost == null || serverHost.isBlank()) {
                ServerInfo info = discover();

                if (info == null) {
                    System.out.println("No server found");
                    return;
                }

                serverHost = info.address();
                serverPort = info.port();

                System.out.println("Server found: " + serverHost + ":" + serverPort);
            }
            connectToServer(serverHost, serverPort, scanner);
        } catch (IOException e) {
            System.err.println("Dicovery failed: " + e.getMessage());
        }
    }

    public void connectToServer(String host, int portNumber, Scanner scanner) {
        this.serverHost = host;
        this.serverPort = portNumber;
        try (
                Socket socket = new Socket(host, portNumber);
                PeerListener peerListener = new PeerListener(this::registerPeer,
                        (fileSocket, hello) -> fileTransfers.receiveFileConnection(fileSocket, hello));) {
            socket.setSoTimeout(10000);

            peerPort = peerListener.getPort();
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            if (!handshake(input, output, nextRequestId())) {
                return;
            }

            if (!login(input, output, scanner, nextRequestId(), peerPort)) {
                return;
            }

            fileTransfers = new FileTransferController(username, peerPort, new File("downloads/" + username), peers);
            serverOutput = output;
            Thread.startVirtualThread(peerListener);
            socket.setSoTimeout(0);
            Thread.startVirtualThread(() -> receivePackets(input));
            try {
                readConsole(output, scanner);
            } finally {
                // Report closed P2P connections before the management socket is closed.
                fileTransfers.close();
                closePeers();
                if (serverOutput != null) {
                    try {
                        Packet request = Packet.text(OpCode.LOGOUT_REQUEST, nextRequestId(), "");
                        awaitResponse(output, request, OpCode.LOGOUT_RESPONSE);
                    } catch (IOException e) {
                        System.err.println("Logout: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } finally {
            serverOutput = null;
            activeCommunityId = null;
            activeCommunityName = null;
            if (fileTransfers != null) {
                fileTransfers.close();
            }
            closePeers();
        }
    }

    private boolean handshake(InputStream input, OutputStream output, int requestId) throws IOException {
        Packet hello = new Packet(OpCode.HELLO, requestId, new byte[0]);
        PacketCodec.write(output, hello);

        Packet response = PacketCodec.read(input);
        try {
            PacketValidator.validateResponse(response, OpCode.HELLO_ACK, requestId);
            if (response.payloadLength() != 0) {
                throw new IOException("HELLO_ACK payload must be empty");
            }
        } catch (IOException e) {
            System.err.println("Invalid or missing HELLO_ACK");
            return false;
        }

        System.out.println("Handshake successful!");
        return true;
    }

    private void readConsole(OutputStream output, Scanner scanner) {
        printHelp();

        while (scanner.hasNextLine()) {
            String message = scanner.nextLine();
            String command = message.trim();
            if (command.equalsIgnoreCase("/logout"))
                return;
            if (message.isBlank())
                continue;

            if (command.equalsIgnoreCase("/help")) {
                printHelp();
                continue;
            }

            if (command.equalsIgnoreCase("/users")) {
                try {
                    requestUserList(output, nextRequestId());
                } catch (IOException e) {
                    System.err.println("Server command: " + e.getMessage());
                }
                continue;
            }

            if (command.equalsIgnoreCase("/chats")) {
                try {
                    Packet request = Packet.text(OpCode.CHAT_SESSION_LIST_REQUEST, nextRequestId(), "");
                    Packet response = awaitResponse(output, request, OpCode.CHAT_SESSION_LIST_RESPONSE);
                    printChatSessions(response.text());
                } catch (IOException e) {
                    System.err.println("Server command: " + e.getMessage());
                }
                continue;
            }

            // ==================== Community Commands ====================

            if (command.equalsIgnoreCase("/communities")) {
                handleCommunities(output);
                continue;
            }

            if (command.equalsIgnoreCase("/community")) {
                printActiveCommunity();
                continue;
            }

            if (command.startsWith("/community ")) {
                handleCommunityCommand(output, command.substring(11).trim());
                continue;
            }

            // ==================== Peer & Messaging Commands ====================

            if (command.equalsIgnoreCase("/peers")) {
                printPeers();
                continue;
            }

            if (command.equalsIgnoreCase("/peer")) {
                handlePeerCommand(null);
                continue;
            }

            if (command.startsWith("/peer ")) {
                handlePeerCommand(command.substring(6).trim());
                continue;
            }

            if (command.equalsIgnoreCase("/msg")) {
                System.out.println("Usage: /msg [<username>] <message>");
                continue;
            }

            if (command.startsWith("/msg ")) {
                handleMsgCommand(command.substring(5).trim());
                continue;
            }

            // ==================== File Commands ====================

            try {
                if (command.startsWith("/sendfile ") || command.equalsIgnoreCase("/sendfile")) {
                    String rawArgs = command.length() > 9 ? command.substring(10).trim() : "";
                    handleSendFileCommand(rawArgs, nextRequestId());
                    continue;
                }
                if (command.startsWith("/accept ")) {
                    String offerNum = command.substring(8).trim();
                    if (!tryAcceptBroadcastOffer(output, offerNum)) {
                        fileTransfers.sendDecision(offerNum, "ACCEPT", nextRequestId());
                    }
                    continue;
                }
                if (command.startsWith("/reject ")) {
                    String offerNum = command.substring(8).trim();
                    if (!tryRejectBroadcastOffer(output, offerNum)) {
                        fileTransfers.sendDecision(offerNum, "REJECT", nextRequestId());
                    }
                    continue;
                }
            } catch (IOException | IllegalArgumentException | IllegalStateException e) {
                System.err.println("File command: " + e.getMessage());
                continue;
            }

            String[] arguments = command.split("\\s+", 2);
            if (arguments[0].equalsIgnoreCase("/lookup")) {
                if (arguments.length != 2 || arguments[1].isBlank()) {
                    System.out.println("Usage: /lookup <username>");
                    continue;
                }

                String targetUsername = arguments[1].trim();
                if (targetUsername.equals(username)) {
                    System.out.println("Cannot connect to yourself");
                    continue;
                }

                if (peerRegistry.isConnected(targetUsername)) {
                    System.out.println(targetUsername + " is already connected");
                    continue;
                }

                if (!pendingLookups.add(targetUsername)) {
                    System.out.println(targetUsername + " connection attempt already in progress");
                    continue;
                }

                try {
                    PeerLookupResult result = requestPeerLookup(
                            output,
                            targetUsername,
                            nextRequestId());

                    InetSocketAddress address = result.address();

                    if (result.status() == null || result.status() == PeerLookupResult.Status.USER_OFFLINE) {
                        System.out.println(targetUsername + " is offline");
                    } else if (result.status() == PeerLookupResult.Status.ALREADY_CONNECTED) {
                        System.out.println(targetUsername + " is already connected");
                    } else if (result.status() == PeerLookupResult.Status.SUCCESS) {
                        connectToPeer(targetUsername, address);
                    }
                } catch (IOException e) {
                    System.err.println("Server command: " + e.getMessage());
                } finally {
                    pendingLookups.remove(targetUsername);
                }
                continue;
            }

            // ==================== Broadcast ====================

            if (arguments[0].equalsIgnoreCase("/broadcast")) {
                if (arguments.length < 2) {
                    System.out.println("Usage: /broadcast <-m|-f> <message/filepath>");
                    continue;
                }

                // Split into option and content (support spaces in filepath/message)
                String rest = arguments[1];
                String option;
                String content;
                if (rest.startsWith("-m ") || rest.startsWith("-M ")) {
                    option = "-m";
                    content = rest.substring(3).trim();
                } else if (rest.startsWith("-f ") || rest.startsWith("-F ")) {
                    option = "-f";
                    content = rest.substring(3).trim();
                } else if (rest.equals("-h") || rest.equals("-H")) {
                    option = "-h";
                    content = "";
                } else {
                    System.out.println("Usage: /broadcast <-m|-f> <message/filepath>");
                    continue;
                }

                switch (option) {
                    case "-m" -> {
                        if (activeCommunityId == null) {
                            System.err.println("No active community. Use /community select <id> first.");
                            continue;
                        }
                        byte[] data = content.getBytes(StandardCharsets.UTF_8);
                        if (data.length > MAX_CHAT_BYTES) {
                            System.err.println("Message exceeds limit: " + MAX_CHAT_BYTES);
                            continue;
                        }
                        try {
                            broadcastMessage(serverOutput,
                                    activeCommunityId + "\n" + String.format("(%s:broadcast) ", activeCommunityName) + username + " : " + content);
                        } catch (Exception e) {
                            System.err.println("Broadcast failed: " + e.getMessage());
                        }
                    }

                    case "-f" -> {
                        if (activeCommunityId == null) {
                            System.err.println("No active community. Use /community select <id> first.");
                            continue;
                        }
                        try {
                            broadcastFile(serverOutput, content);
                        } catch (Exception e) {
                            System.err.println("Broadcast file failed: " + e.getMessage());
                        }
                    }

                    case "-h" -> {
                        System.out.println("""
                                Broadcast commands:
                                  /broadcast -m <message>   Send a text message to active community
                                  /broadcast -f <filepath>  Send a file to active community
                                  /broadcast -h             Show this help
                                """);
                    }

                    default -> System.out.println("Unknown option: " + option);
                }
                continue;
            }

            // ==================== Direct P2P Chat ====================

            String activePeer = peerRegistry.getActivePeer();
            if (activePeer == null) {
                System.out.println("Connect to a peer before sending messages.");
                continue;
            }

            sendMessageToPeer(activePeer, message);
        }
    }

    // ==================== Community Command Handlers ====================

    private void handleCommunities(OutputStream output) {
        try {
            Packet request = new Packet(OpCode.COMMUNITY_LIST_REQUEST, nextRequestId(), new byte[0]);
            Packet response = awaitResponse(output, request, OpCode.COMMUNITY_LIST_RESPONSE);
            String payload = response.text();
            if (payload.isEmpty()) {
                System.out.println("No communities available. Create one with /community create <name>");
            } else {
                System.out.println("Communities:");
                for (String line : payload.split("\n")) {
                    String[] fields = line.split("\t", -1);
                    if (fields.length >= 5) {
                        String marker = fields[4].equals("true") ? " [JOINED]" : "";
                        System.out.println("  " + fields[1] + " | ID: " + fields[0]
                                + " | Owner: " + fields[2] + " | Members: " + fields[3] + marker);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Community list failed: " + e.getMessage());
        }
    }

    private void handleCommunityCommand(OutputStream output, String subcommand) {
        if (subcommand.startsWith("create ")) {
            String name = subcommand.substring(7).trim();
            if (name.isEmpty()) {
                System.out.println("Usage: /community create <name>");
                return;
            }
            try {
                Packet request = Packet.text(OpCode.COMMUNITY_CREATE_REQUEST, nextRequestId(), name);
                Packet response = awaitResponse(output, request, OpCode.COMMUNITY_CREATE_RESPONSE);
                String result = response.text();
                if (result.startsWith("OK\n")) {
                    String[] parts = result.split("\n", -1);
                    activeCommunityId = parts[1];
                    activeCommunityName = parts[2];
                    System.out.println("Community '" + activeCommunityName + "' created! ID: " + activeCommunityId);
                    System.out.println("[Community: " + activeCommunityName + "] selected.");
                } else {
                    System.err.println("Create failed: " + result);
                }
            } catch (IOException e) {
                System.err.println("Create community failed: " + e.getMessage());
            }
            return;
        }

        if (subcommand.startsWith("join ")) {
            String communityId = subcommand.substring(5).trim();
            if (communityId.isEmpty()) {
                System.out.println("Usage: /community join <communityId>");
                return;
            }
            try {
                Packet request = Packet.text(OpCode.COMMUNITY_JOIN_REQUEST, nextRequestId(), communityId);
                Packet response = awaitResponse(output, request, OpCode.COMMUNITY_JOIN_RESPONSE);
                String result = response.text();
                if (result.startsWith("OK\n")) {
                    String[] parts = result.split("\n", -1);
                    activeCommunityId = parts[1];
                    activeCommunityName = parts[2];
                    System.out.println("Joined community '" + activeCommunityName + "'!");
                    System.out.println("[Community: " + activeCommunityName + "] selected.");
                } else {
                    System.err.println("Join failed: " + result);
                }
            } catch (IOException e) {
                System.err.println("Join community failed: " + e.getMessage());
            }
            return;
        }

        if (subcommand.equalsIgnoreCase("leave")) {
            if (activeCommunityId == null) {
                System.err.println("No active community selected.");
                return;
            }
            try {
                Packet request = Packet.text(OpCode.COMMUNITY_LEAVE_REQUEST, nextRequestId(), activeCommunityId);
                Packet response = awaitResponse(output, request, OpCode.COMMUNITY_LEAVE_RESPONSE);
                String result = response.text();
                if (result.equals("OK")) {
                    System.out.println("Left community '" + activeCommunityName + "'.");
                    activeCommunityId = null;
                    activeCommunityName = null;
                } else {
                    System.err.println("Leave failed: " + result);
                }
            } catch (IOException e) {
                System.err.println("Leave community failed: " + e.getMessage());
            }
            return;
        }

        if (subcommand.equalsIgnoreCase("members")) {
            if (activeCommunityId == null) {
                System.err.println("No active community selected.");
                return;
            }
            try {
                Packet request = Packet.text(OpCode.COMMUNITY_MEMBERS_REQUEST, nextRequestId(), activeCommunityId);
                Packet response = awaitResponse(output, request, OpCode.COMMUNITY_MEMBERS_RESPONSE);
                String result = response.text();
                if (result.startsWith("OK\n")) {
                    String memberData = result.substring(3);
                    System.out.println("Members of '" + activeCommunityName + "':");
                    if (memberData.isEmpty()) {
                        System.out.println("  (none)");
                    } else {
                        for (String line : memberData.split("\n")) {
                            String[] fields = line.split("\t", -1);
                            if (fields.length >= 2) {
                                String status = fields[1].equals("true") ? "online" : "offline";
                                System.out.println("  - " + fields[0] + " (" + status + ")");
                            }
                        }
                    }
                } else {
                    System.err.println("Members failed: " + result);
                }
            } catch (IOException e) {
                System.err.println("List members failed: " + e.getMessage());
            }
            return;
        }

        if (subcommand.startsWith("select ")) {
            String communityId = subcommand.substring(7).trim();
            if (communityId.isEmpty()) {
                System.out.println("Usage: /community select <communityId>");
                return;
            }
            try {
                Packet request = Packet.text(OpCode.COMMUNITY_SELECT_REQUEST, nextRequestId(), communityId);
                Packet response = awaitResponse(output, request, OpCode.COMMUNITY_SELECT_RESPONSE);
                String result = response.text();
                if (result.startsWith("OK\n")) {
                    String[] parts = result.split("\n", -1);
                    activeCommunityId = parts[1];
                    activeCommunityName = parts[2];
                    System.out.println("[Community: " + activeCommunityName + "] selected.");
                } else {
                    System.err.println("Select failed: " + result);
                }
            } catch (IOException e) {
                System.err.println("Select community failed: " + e.getMessage());
            }
            return;
        }

        if (subcommand.equalsIgnoreCase("info")) {
            if (activeCommunityId == null) {
                System.err.println("No active community selected.");
                return;
            }
            try {
                Packet request = Packet.text(OpCode.COMMUNITY_INFO_REQUEST, nextRequestId(), activeCommunityId);
                Packet response = awaitResponse(output, request, OpCode.COMMUNITY_INFO_RESPONSE);
                String result = response.text();
                if (result.startsWith("OK\n")) {
                    String info = result.substring(3);
                    String[] fields = info.split("\t", -1);
                    if (fields.length >= 6) {
                        System.out.println("Community Info:");
                        System.out.println("  ID: " + fields[0]);
                        System.out.println("  Name: " + fields[1]);
                        System.out.println("  Owner: " + fields[2]);
                        System.out.println("  Members: " + fields[3]);
                        System.out.println("  You are a member: " + fields[4]);
                        System.out.println("  Created: " + fields[5]);
                    }
                } else {
                    System.err.println("Info failed: " + result);
                }
            } catch (IOException e) {
                System.err.println("Community info failed: " + e.getMessage());
            }
            return;
        }

        System.out.println("Unknown community command. Use /help for available commands.");
    }

    private void printActiveCommunity() {
        if (activeCommunityId == null) {
            System.out.println("No active community. Use /community create <name>, /community join <id>, or /community select <id>.");
        } else {
            System.out.println("[Community: " + activeCommunityName + "] (ID: " + activeCommunityId + ")");
        }
    }

    private void printHelp() {
        String communityPrompt = activeCommunityName != null
                ? "\n[Community: " + activeCommunityName + "]"
                : "";
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
                    /lookup <username>              Connect to a user for P2P chat
                    /peers                          List connected P2P peers
                    /peer [<username>]              View or set active peer
                    /msg [<username>] <message>     Send private message
                    /sendfile [<user>] <filepath>   Send a private file through P2P

                  GENERAL
                    /users                          List online users
                    /chats                          List P2P chat sessions
                    /accept <offer_number>          Accept a file offer
                    /reject <offer_number>          Reject a file offer
                    /help                           Show this help
                    /logout                         Log out"""
                + communityPrompt);
    }

    // ==================== Broadcast File ====================

    private void broadcastFile(OutputStream serverOutputStream, String filepath)
            throws IOException {
        File file = new File(filepath);
        if (!file.exists() || !file.isFile()) {
            throw new IOException("File not found: " + filepath);
        }
        if (!file.canRead()) {
            throw new IOException("Cannot read file: " + filepath);
        }
        if (file.length() > FileTransferMeta.MAX_FILE_SIZE) {
            throw new IOException("File size exceeds maximum: " + file.length() + " > " + FileTransferMeta.MAX_FILE_SIZE);
        }
        if (file.length() == 0) {
            throw new IOException("File is empty");
        }

        // Compute checksum
        System.out.println("[BROADCAST] Computing checksum for '" + file.getName() + "'...");
        String checksum = FileSender.checksum(file);

        // Step 1: Send upload request on control connection
        String uploadPayload = activeCommunityId + "\n" + file.getName() + "\n" + file.length() + "\n" + checksum;
        Packet uploadReq = Packet.text(OpCode.FILE_BROADCAST_UPLOAD_REQUEST, nextRequestId(), uploadPayload);
        Packet uploadResp = awaitResponse(serverOutputStream, uploadReq, OpCode.FILE_BROADCAST_UPLOAD_RESPONSE);

        String respText = uploadResp.text();
        if (!respText.startsWith("OK\n")) {
            throw new IOException("Upload request rejected: " + respText);
        }
        String[] respParts = respText.split("\n", -1);
        String fileId = respParts[1];
        String uploadToken = respParts[2];

        // Step 2: Open dedicated data connection for upload
        System.out.println("[BROADCAST] Uploading '" + file.getName() + "' (" + file.length() + " bytes)...");
        try (Socket dataSocket = new Socket(serverHost, serverPort)) {
            dataSocket.setSoTimeout(60000);
            OutputStream dataOut = dataSocket.getOutputStream();
            InputStream dataIn = dataSocket.getInputStream();

            // Send FILE_BROADCAST_UPLOAD_START as the first packet
            Packet startPkt = Packet.text(OpCode.FILE_BROADCAST_UPLOAD_START, 0, uploadToken + "\n" + fileId);
            PacketCodec.write(dataOut, startPkt);

            // Stream file chunks
            FileSender sender = new FileSender(file, fileId, dataOut);
            sender.send();

            // Wait for FILE_RESULT
            Packet result = PacketCodec.read(dataIn);
            if (result == null || result.op() != OpCode.FILE_RESULT) {
                throw new IOException("Missing file result from server");
            }
            if (!result.text().equals("OK")) {
                throw new IOException("Server rejected upload: " + result.text());
            }
        }

        System.out.println("[BROADCAST] File '" + file.getName() + "' uploaded and broadcast to community '"
                + activeCommunityName + "'. SHA-256: " + checksum);
    }

    // ==================== Broadcast Offer Accept/Reject ====================

    private boolean tryAcceptBroadcastOffer(OutputStream output, String offerNum) {
        String key = "bc-" + offerNum;
        BroadcastOffer offer = broadcastOffers.get(key);
        if (offer == null) return false;

        Thread.startVirtualThread(() -> {
            try {
                // Send decision to server
                Packet decision = Packet.text(OpCode.FILE_BROADCAST_DECISION, nextRequestId(),
                        offer.fileId() + "\nACCEPT");
                Packet resp = awaitResponse(output, decision, OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE);
                String respText = resp.text();
                if (!respText.startsWith("OK\n")) {
                    System.err.println("[BROADCAST] Accept failed: " + respText);
                    return;
                }
                String downloadToken = respText.split("\n", -1)[1];

                // Download via dedicated data connection
                System.out.println("[BROADCAST] Downloading '" + offer.fileName() + "' from server...");
                try (Socket dataSocket = new Socket(serverHost, serverPort)) {
                    dataSocket.setSoTimeout(60000);
                    OutputStream dataOut = dataSocket.getOutputStream();
                    InputStream dataIn = dataSocket.getInputStream();

                    Packet startPkt = Packet.text(OpCode.FILE_BROADCAST_DOWNLOAD_START, 0,
                            downloadToken + "\n" + offer.fileId());
                    PacketCodec.write(dataOut, startPkt);

                    // Receive file using FileReceiver protocol
                    FileTransferMeta meta = new FileTransferMeta(
                            offer.fileId(), offer.sender(), username,
                            offer.fileName(), offer.fileSize(), offer.checksum());
                    FileReceiver receiver = new FileReceiver(dataIn, dataOut, new File("downloads/" + username), meta);
                    File saved = receiver.receive();
                    System.out.println("[BROADCAST RECEIVED] Saved to: " + saved.getAbsolutePath());
                }
            } catch (IOException e) {
                System.err.println("[BROADCAST DOWNLOAD ERROR] " + e.getMessage());
            }
        });

        broadcastOffers.remove(key);
        return true;
    }

    private boolean tryRejectBroadcastOffer(OutputStream output, String offerNum) {
        String key = "bc-" + offerNum;
        BroadcastOffer offer = broadcastOffers.get(key);
        if (offer == null) return false;

        try {
            Packet decision = Packet.text(OpCode.FILE_BROADCAST_DECISION, nextRequestId(),
                    offer.fileId() + "\nREJECT");
            awaitResponse(output, decision, OpCode.FILE_BROADCAST_DOWNLOAD_RESPONSE);
            System.out.println("[BROADCAST] Rejected broadcast file offer #" + offerNum);
        } catch (IOException e) {
            System.err.println("[BROADCAST] Reject failed: " + e.getMessage());
        }

        broadcastOffers.remove(key);
        return true;
    }

    // ==================== Existing methods (unchanged logic) ====================

    private void requestUserList(OutputStream output, int requestId)
            throws IOException {
        Packet request = new Packet(OpCode.USER_LIST_REQUEST, requestId, new byte[0]);
        Packet response = awaitResponse(output, request, OpCode.USER_LIST_RESPONSE);
        printUserList(response.text());
    }

    private boolean login(InputStream input, OutputStream output, Scanner scanner, int requestId, int peerPort)
            throws IOException {
        System.out.print("Username: ");
        if (!scanner.hasNextLine())
            return false;
        String username = scanner.nextLine().trim();

        System.out.print("Password: ");
        if (!scanner.hasNextLine())
            return false;
        String password = scanner.nextLine();

        if (username.isEmpty() || password.isEmpty()) {
            System.err.println("Username and password must not be empty");
            return false;
        }

        Packet auth_request = Packet.text(
                OpCode.AUTH_REQUEST, requestId, username + "\n" + password + "\n" + peerPort);
        PacketCodec.write(output, auth_request);

        Packet authResponse = PacketCodec.read(input);
        try {
            PacketValidator.validateResponse(authResponse, OpCode.AUTH_RESPONSE, requestId);
        } catch (IOException e) {
            System.err.println("Invalid or missing AUTH_RESPONSE");
            return false;
        }

        String status = authResponse.text();
        if (!status.equals("OK")) {
            System.err.println("Login failed: " + status);
            return false;
        }

        System.out.println("Login successful!");
        this.username = username;
        return true;
    }

    private PeerLookupResult requestPeerLookup(OutputStream output, String targetUsername, int requestId)
            throws IOException {
        Packet request = Packet.text(OpCode.PEER_LOOKUP_REQUEST, requestId, targetUsername + "\n" + this.username);
        Packet response = awaitResponse(output, request, OpCode.PEER_LOOKUP_RESPONSE);

        String payload = response.text();
        if (payload.equals("USER_OFFLINE")) {
            return new PeerLookupResult(
                    PeerLookupResult.Status.USER_OFFLINE,
                    null);
        }

        if (payload.equals("ALREADY_CONNECTED")) {
            return new PeerLookupResult(
                    PeerLookupResult.Status.ALREADY_CONNECTED,
                    null);
        }

        return new PeerLookupResult(
                PeerLookupResult.Status.SUCCESS,
                parsePeerAddress(payload));
    }

    private InetSocketAddress parsePeerAddress(String payload) throws IOException {
        String[] parts = payload.split("\n", -1);
        if (parts.length != 3
                || !parts[0].equals("OK")
                || parts[1].isBlank()) {
            throw new IOException("Invalid peer address payload");
        }

        int peerPort;
        try {
            peerPort = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid peer port", e);
        }

        if (peerPort < 1 || peerPort > 65535) {
            throw new IOException("Peer port is out of range");
        }

        return new InetSocketAddress(parts[1], peerPort);
    }

    private void connectToPeer(String targetUsername, InetSocketAddress address) {
        if (peerRegistry.isConnected(targetUsername)) {
            System.out.println(targetUsername + " is already connected");
            return;
        }

        Socket peerSocket = new Socket();
        PeerConnection connection = new PeerConnection(peerSocket);
        connection.setPeerUsername(targetUsername);

        try {
            peerSocket.connect(address, 10000);
            connection.initiateHandshake(username, 1, peerPort);

            if (!registerPeer(targetUsername, connection)) {
                System.out.println(targetUsername + " is already connected");
                return;
            }

            peerRegistry.setActivePeer(targetUsername);
            System.out.println("Now chatting with " + targetUsername);
        } catch (IOException e) {
            closePeerOnly(connection);

            System.err.println(
                    "Cannot connect to " + targetUsername
                            + ": " + e.getMessage());
        }
    }

    private synchronized boolean registerPeer(String peerUsername, PeerConnection connection) {
        if (closingPeers || peerRegistry.isClosing()) {
            closePeerOnly(connection);
            return false;
        }

        if (!peerRegistry.register(peerUsername, connection)) {
            closePeerOnly(connection);
            return false;
        }

        connection.setPeerUsername(peerUsername);
        reportChatSession(peerUsername, connection, "CONNECTED");

        Thread.startVirtualThread(() -> {
            try {
                connection.readMessages(
                        peerUsername,
                        text -> {
                            System.out.println(peerUsername + ": " + text);
                            java.util.function.Consumer<String> l = chatListener;
                            if (l != null) {
                                l.accept(peerUsername + ": " + text);
                            }
                        },
                        packet -> {
                            try {
                                if (packet.op() == OpCode.FILE_OFFER) {
                                    fileTransfers.handleIncomingOffer(peerUsername, connection, packet);
                                } else {
                                    fileTransfers.handleIncomingDecision(connection, packet);
                                }
                            } catch (IOException | RuntimeException e) {
                                System.err.println("Invalid peer file packet: " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                System.err.println("Peer " + peerUsername + ": " + e.getMessage());
            } finally {
                fileTransfers.closeForPeer(connection);
                synchronized (Client.this) {
                    reportChatSession(peerUsername, connection, "DISCONNECTED");
                    peerRegistry.unregister(peerUsername, connection);
                }

                closePeerOnly(connection);
            }
        });
        return true;
    }

    private void closePeerOnly(PeerConnection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (IOException e) {
            System.err.println("Cannot close peer: " + e.getMessage());
        }
    }

    private void closePeer(String peerUsername, PeerConnection connection) {
        if (connection == null) return;
        fileTransfers.closeForPeer(connection);
        synchronized (this) {
            reportChatSession(peerUsername, connection, "DISCONNECTED");
            peerRegistry.unregister(peerUsername, connection);
        }
        closePeerOnly(connection);
    }

    private synchronized void closePeers() {
        closingPeers = true;
        peerRegistry.setClosing(true);

        for (var entry : peerRegistry.asMap().entrySet()) {
            PeerConnection connection = entry.getValue();
            reportChatSession(entry.getKey(), connection, "DISCONNECTED");
            closePeerOnly(connection);
        }

        peerRegistry.clear();
    }

    private void reportChatSession(String peerUsername, PeerConnection connection, String state) {
        OutputStream output = serverOutput;
        if (output == null)
            return;
        if (state.equals("DISCONNECTED") && !connection.markDisconnectReported())
            return;
        try {
            sendServerPacket(output, Packet.text(OpCode.CHAT_SESSION_REPORT, nextRequestId(),
                    connection.sessionId() + "\n" + peerUsername + "\n" + state));
        } catch (IOException e) {
            System.err.println("Cannot report chat session: " + e.getMessage());
        }
    }

    private void sendServerPacket(OutputStream output, Packet packet) throws IOException {
        synchronized (serverWriteLock) {
            PacketCodec.write(output, packet);
        }
    }

    public ServerInfo discover() throws IOException {
        final int DISCOVERY_PORT = 41234;
        final int maxAttemps = 5;

        InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
        int requestId = nextRequestId();
        Packet request = new Packet(OpCode.DISCOVER_REQUEST, requestId, new byte[0]);

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        PacketCodec.write(encoded, request);
        byte[] requestBytes = encoded.toByteArray();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(3000);

            DatagramPacket outgoing = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    broadcastAddress,
                    DISCOVERY_PORT);

            byte[] buffer = new byte[4096];
            for (int attemp = 1; attemp <= maxAttemps; attemp++) {
                socket.send(outgoing);
                System.out.println("Finding server: " + attemp + "/" + maxAttemps);

                DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(incoming);
                } catch (SocketTimeoutException e) {
                    continue;
                }

                try {
                    ByteArrayInputStream input = new ByteArrayInputStream(
                            incoming.getData(),
                            incoming.getOffset(),
                            incoming.getLength());

                    Packet response = PacketCodec.read(input);
                    PacketValidator.validateResponse(response, OpCode.DISCOVER_RESPONSE, requestId);
                    if (input.available() != 0 || incoming.getPort() != DISCOVERY_PORT) {
                        continue;
                    }

                    int tcpPort = Integer.parseInt(response.text());
                    if (tcpPort < 1 || tcpPort > 65535)
                        continue;

                    return new ServerInfo(incoming.getAddress().getHostAddress(), tcpPort);
                } catch (IOException | NumberFormatException e) {
                    continue;
                }
            }
        }
        return null;
    }

    private Packet awaitResponse(OutputStream output, Packet request, OpCode expectedOp) throws IOException {
        CompletableFuture<Packet> responseFuture = new CompletableFuture<>();
        pendingResponses.put(request.requestId(), responseFuture);
        try {
            sendServerPacket(output, request);
            Packet response = responseFuture.get(10, TimeUnit.SECONDS);
            return PacketValidator.validateResponse(response, expectedOp, request.requestId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + expectedOp, e);
        } catch (TimeoutException e) {
            throw new IOException("Timed out waiting for " + expectedOp, e);
        } catch (ExecutionException e) {
            throw new IOException("Failed while waiting for " + expectedOp, e.getCause());
        } finally {
            pendingResponses.remove(request.requestId(), responseFuture);
        }
    }

    private void receivePackets(InputStream input) {
        try {
            Packet packet;
            while ((packet = PacketCodec.read(input)) != null) {
                switch (packet.op()) {
                    case CHAT_MESSAGE -> System.out.println(packet.text());
                    case USER_LIST_RESPONSE, PEER_LOOKUP_RESPONSE, CHAT_SESSION_LIST_RESPONSE, LOGOUT_RESPONSE,
                         COMMUNITY_LIST_RESPONSE, COMMUNITY_CREATE_RESPONSE, COMMUNITY_JOIN_RESPONSE,
                         COMMUNITY_LEAVE_RESPONSE, COMMUNITY_MEMBERS_RESPONSE, COMMUNITY_SELECT_RESPONSE,
                         COMMUNITY_INFO_RESPONSE, FILE_BROADCAST_UPLOAD_RESPONSE,
                         FILE_BROADCAST_DOWNLOAD_RESPONSE -> {
                        CompletableFuture<Packet> responseFuture = pendingResponses.get(packet.requestId());
                        if (responseFuture != null) {
                            responseFuture.complete(packet);
                        }
                    }
                    case CHAT_SESSION_REPORT_RESPONSE -> {
                        if (!packet.text().equals("OK")) {
                            System.err.println("Chat session report rejected: " + packet.text());
                        }
                    }
                    case CHAT_MESSAGE_BROADCAST -> {
                        System.out.println(packet.text());
                    }
                    case FILE_BROADCAST_OFFER -> {
                        handleBroadcastOfferReceived(packet);
                    }
                    default -> {
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Server receive error: " + e.getMessage());
        } finally {
            serverOutput = null;
            IOException closed = new IOException("Server connection closed");
            pendingResponses.values().forEach(future -> future.completeExceptionally(closed));
        }
    }

    private void handleBroadcastOfferReceived(Packet packet) {
        // Payload: fileId\tsender\tfilename\tfileSize\tchecksum
        String[] fields = packet.text().split("\t", -1);
        if (fields.length < 5) return;

        String fileId = fields[0];
        String sender = fields[1];
        String fileName = fields[2];
        long fileSize;
        try { fileSize = Long.parseLong(fields[3]); } catch (NumberFormatException e) { return; }
        String checksum = fields[4];

        int num = broadcastOfferSeq.incrementAndGet();
        String key = "bc-" + num;
        broadcastOffers.put(key, new BroadcastOffer(fileId, sender, fileName, fileSize, checksum));

        System.out.println("\n[BROADCAST FILE OFFER #" + num + "] " + sender + " shared '" + fileName
                + "' (" + fileSize + " bytes) in community.");
        System.out.println("SHA-256: " + checksum);
        System.out.println("Type '/accept " + num + "' or '/reject " + num + "'");
    }

    private void printUserList(String usernames) {
        System.out.println("Online users:");
        if (usernames.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (String username : usernames.split("\n")) {
                System.out.println("- " + username);
            }
        }
    }

    private void printChatSessions(String payload) throws IOException {
        System.out.println("Chat sessions:");
        if (payload.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        for (String line : payload.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4)
                throw new IOException("Invalid chat session list");
            System.out.println("- " + fields[1] + " <-> " + fields[2] + ": " + fields[3]
                    + " | id=" + fields[0]);
        }
    }

    public boolean sendMessageToPeer(String targetUsername, String message) {
        if (targetUsername == null || targetUsername.isBlank()) {
            System.out.println("No target peer specified.");
            return false;
        }

        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_CHAT_BYTES) {
            System.err.println("Message exceeds limit: " + MAX_CHAT_BYTES);
            return false;
        }

        PeerConnection connection = peerRegistry.get(targetUsername);
        if (connection == null || connection.isClosed()) {
            System.out.println("Peer " + targetUsername + " is not connected.");
            return false;
        }

        try {
            connection.sendMessage(message);
            return true;
        } catch (IOException e) {
            System.err.println("Cannot send message to " + targetUsername + ": " + e.getMessage());
            closePeer(targetUsername, connection);
            return false;
        }
    }

    private void printPeers() {
        System.out.println("Connected peers:");
        List<String> connected = peerRegistry.getConnectedPeers();
        if (connected.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        String active = peerRegistry.getActivePeer();
        for (String peerName : connected) {
            boolean isActive = peerName.equals(active);
            System.out.println("- " + peerName + " (CONNECTED)" + (isActive ? " [ACTIVE]" : ""));
        }
    }

    private void handlePeerCommand(String target) {
        if (target == null || target.isBlank()) {
            String active = peerRegistry.getActivePeer();
            if (active == null) {
                System.out.println("No active peer selected.");
            } else {
                System.out.println("Active peer: " + active);
            }
            return;
        }

        if (peerRegistry.isConnected(target)) {
            peerRegistry.setActivePeer(target);
            System.out.println("Active peer set to " + target);
        } else {
            System.out.println("Peer " + target + " is not connected. Use /lookup " + target + " to connect.");
        }
    }

    private void handleMsgCommand(String content) {
        if (content == null || content.isBlank()) {
            System.out.println("Usage: /msg [<username>] <message>");
            return;
        }

        String[] parts = content.split("\\s+", 2);
        String firstToken = parts[0];

        if (peerRegistry.isConnected(firstToken)) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("Message cannot be empty.");
                return;
            }
            sendMessageToPeer(firstToken, parts[1]);
            return;
        }

        String active = peerRegistry.getActivePeer();
        if (active == null) {
            if (parts.length >= 2 && !peerRegistry.isConnected(firstToken)) {
                System.out.println("Peer " + firstToken + " is not connected. Use /lookup " + firstToken + " first, or select an active peer.");
            } else {
                System.out.println("No active peer selected. Use /peer <username> to select an active peer, or /msg <username> <message>.");
            }
            return;
        }

        sendMessageToPeer(active, content);
    }

    private void handleSendFileCommand(String rawArgs, int requestId) {
        if (rawArgs == null || rawArgs.isBlank()) {
            System.out.println("Usage: /sendfile [<username>] <filepath>");
            return;
        }

        String args = rawArgs.trim();
        String[] parts = args.split("\\s+", 2);
        String firstToken = parts[0];

        String targetPeer;
        String filepath;

        if (peerRegistry.isConnected(firstToken)) {
            if (parts.length < 2 || parts[1].isBlank()) {
                System.out.println("Usage: /sendfile " + firstToken + " <filepath>");
                return;
            }
            targetPeer = firstToken;
            filepath = stripQuotes(parts[1].trim());
        } else {
            if (parts.length >= 2) {
                File possibleFileDirect = new File(stripQuotes(args));
                File possibleFileRest = new File(stripQuotes(parts[1].trim()));
                if (!possibleFileDirect.exists() && possibleFileRest.exists()) {
                    System.out.println("Peer " + firstToken + " is not connected. Use /lookup " + firstToken + " first.");
                    return;
                }
            }

            String active = peerRegistry.getActivePeer();
            if (active == null) {
                System.out.println("No active peer selected. Use /sendfile <username> <filepath> or set active peer with /peer <username>.");
                return;
            }
            targetPeer = active;
            filepath = stripQuotes(args);
        }

        PeerConnection peer = peerRegistry.get(targetPeer);
        if (peer == null || peer.isClosed()) {
            System.out.println("Peer " + targetPeer + " is not connected. Connect with /lookup " + targetPeer + " first.");
            return;
        }

        File file = new File(filepath);
        if (!file.exists() || !file.isFile()) {
            System.err.println("File not found: " + filepath);
            return;
        }

        fileTransfers.offer(peer, targetPeer, file, requestId);
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public PeerConnectionRegistry getPeerRegistry() {
        return peerRegistry;
    }

    public void setChatListener(java.util.function.Consumer<String> chatListener) {
        this.chatListener = chatListener;
    }

    private void broadcastMessage(OutputStream serverOutputStream, String message) throws IOException {
        Packet chatMessage = Packet.text(OpCode.CHAT_MESSAGE_BROADCAST, nextRequestId(), message);
        if (chatMessage.payloadLength() > MAX_CHAT_BYTES) {
            throw new IOException("Message exceeds limit: " + MAX_CHAT_BYTES);
        }
        sendServerPacket(serverOutputStream, chatMessage);
    }

}

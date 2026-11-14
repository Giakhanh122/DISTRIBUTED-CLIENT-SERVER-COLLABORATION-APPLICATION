# Refactor security — thay đổi và kiểm chứng

## Kết quả

Đã xử lý các code smell đã review. Build thành công với
`mvn -o package -DskipTests`; bộ regression Java tạm đạt **149 assertion** trên
TCP loopback. Không giữ harness, khóa hoặc dữ liệu test tạm sau khi hoàn tất.

Dòng `x90` đã không còn khi bắt đầu sửa; không ghi đè thêm vào `AesGcm.java`.

## Các thay đổi

| File | Thay đổi |
| --- | --- |
| `security/X25519.java` | Gom tạo key pair, đọc public key, tính shared secret, kiểm tra zero và xóa secret sau khi dẫn xuất. |
| `security/SessionKeys.java` | Đổi tên hai chiều theo initiator/responder; thêm dẫn xuất traffic key riêng cho mỗi lần mở socket file. |
| `network/PacketChannel.java` | Thay boolean vai trò bằng enum Role; gom trao đổi KEY_CONFIRM vào confirmEncryption. |
| `client/Client.java`, `client/peer/PeerConnection.java`, `server/connection/ClientHandler.java` | Dùng helper X25519 và xác nhận khóa dùng chung; giữ transcript và xác thực đúng từng loại handshake. |
| `client/security/ServerKeyPinning.java`, `client/connection/ServerDiscovery.java` | Dùng chung thao tác đọc/tạo public key X25519. |
| `client/transfer/FileTransferController.java` | Một Transfer chứa session, peer, file, khóa, socket và timer; một map quản lý các Transfer. Chỉ claim file sau xác nhận khóa. |
| `client/ClientSession.java` | Bỏ đối số peerPort không còn dùng trong controller file. |
| `docs/protocol.md`, `README.md` | Cập nhật FILE_READY có connectionNonce, vai trò khóa và luồng file mới. |

Các chuỗi HKDF cũ chứa `client-to-server`/`server-to-client` được giữ nguyên để
không đổi khóa của handshake hiện có chỉ vì đổi tên accessor. API Java dùng
`initiatorToResponder` và `responderToInitiator`; socket file dùng thêm ngữ cảnh
`DLP-v1 peer-file-connection` và nonce mới để dẫn xuất khóa của từng lần kết nối.

## Sửa việc claim file trước xác thực

```text
Kênh chat đã mã hóa:
  FILE_OFFER / FILE_DECISION trao đổi public key X25519 của transfer
  Hai bên dẫn xuất bộ khóa gốc theo metadata và public key sender/receiver

Mỗi socket file mới:
  Sender   → FILE_TRANSFER_START {transferId}
  Receiver → FILE_READY {transferId, connectionNonce ngẫu nhiên 32 byte}
  Hai bên  → dẫn xuất hai traffic key từ khóa gốc + nonce mới
  Hai bên  → confirmEncryption với vai trò INITIATOR / RESPONDER
  Receiver → kiểm tra lại session, peer và trạng thái; claim socket một lần
  Sender   → file mã hóa + FILE_END
  Receiver → kiểm tra dữ liệu, lưu file, gửi FILE_RESULT mã hóa
```

Socket không có khóa hợp lệ không claim được transfer, không xóa khóa gốc và
không làm lời mời hợp lệ báo FAILED. Mỗi lần thử có nonce/traffic key mới nên
không khởi động lại counter AES-GCM với cùng traffic key. Nonce được truyền rõ;
thay nonce dẫn tới xác nhận khóa thất bại.

Các socket chưa xác thực vẫn do PeerListener sở hữu và chịu read timeout.
Lời mời đã ACCEPT giữ hạn chờ 30 giây; lời mời chưa trả lời giữ hạn 2 phút.
Socket dữ liệu đã xác thực mà bị sửa/replay/cắt vẫn làm lượt truyền FAILED.

Khi đóng peer, kiểm tra COMPLETED và chuyển CANCEL được bảo vệ bằng cùng khóa
session với việc công bố file, tránh báo hủy nhầm khi receiver vừa lưu xong.

## Kiểm chứng trên bản cuối

- AES-GCM vector chuẩn, HKDF RFC 5869, hai phía X25519 tạo cùng khóa.
- Hai chiều có khóa khác nhau; hai nonce kết nối dẫn xuất khóa khác nhau.
- Record của một lần kết nối không giải mã được bằng khóa của lần khác.
- Nonce sai độ dài, tag sửa và username peer giả đều bị từ chối.
- Login, quản lý, chat P2P, broadcast file và TCP probe discovery hoạt động.
- File 0, 1, 65535, 65536, 65537 và 2097165 byte khớp dữ liệu gốc/đích.
- Giữ bên gửi thật tại proxy, cho bốn socket giả cùng IP thử vào cùng transferId:
  nonce khác nhau, khóa gốc/lời mời còn nguyên, không socket giả nào được claim;
  giải phóng bên gửi thật và truyền thành công sau đó.
- Tamper/replay/cắt ở cả KEY_CONFIRM và record dữ liệu file; chat không bị đóng.
- Tám lượt truyền đồng thời, đường dẫn file đích không trùng.
- File P2P khi server offline; sai mật khẩu reconnect bị từ chối; đúng mật khẩu
  truy vấn được trạng thái server.
- Offer hết hạn, đóng peer, dọn context/timer, dừng executor và không còn file
  `.transfer-*`. Các ca expiry rút thời gian xuống 100 ms qua cùng hàm timeout.

Môi trường: OpenJDK 25.0.4.1, Maven target release 21. Chưa thử GUI, runtime JDK
21, WAN, file 500 MiB hoặc crash tiến trình trong lượt này. Đây là regression
cho các sửa đổi, không thay cho kiểm toán bảo mật toàn bộ ứng dụng.

SHA-256 JAR được kiểm thử:

```text
fa340a2e9fb45939166566af19cc9b0dd5b34e0b8d78d6359c486943c0d7d77f
```

## Code chính để review

<details>
<summary>security/X25519.java</summary>

```java
package security;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.KeyAgreement;

/** Shared X25519 operations; callers supply the authenticated peer key and handshake transcript. */
public final class X25519 {
    private static final String ALGORITHM = "X25519";

    private X25519() {}

    public static KeyPair createKeyPair() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    }

    public static PublicKey decodePublicKey(String publicKey) throws GeneralSecurityException {
        byte[] encoded = Base64.getDecoder().decode(publicKey);
        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static SessionKeys deriveSessionKeys(KeyPair keyPair, PublicKey peerPublicKey, byte[] handshakeData)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance(ALGORITHM);
        agreement.init(keyPair.getPrivate());
        agreement.doPhase(peerPublicKey, true);
        byte[] sharedSecret = agreement.generateSecret();
        try {
            if (MessageDigest.isEqual(sharedSecret, new byte[32])) {
                throw new GeneralSecurityException("Invalid X25519 shared secret");
            }
            return SessionKeys.derive(sharedSecret, handshakeData);
        } finally {
            Arrays.fill(sharedSecret, (byte) 0);
        }
    }
}
```

</details>

<details>
<summary>security/SessionKeys.java</summary>

```java
package security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public record SessionKeys(SecretKey initiatorToResponder, SecretKey responderToInitiator) {
    public static SessionKeys derive(byte[] sharedSecret, byte[] handshakeData)
            throws GeneralSecurityException {
        byte[] salt = MessageDigest.getInstance("SHA-256").digest(handshakeData);
        return deriveDirections(sharedSecret, sharedSecret, salt);
    }

    /** Fresh keys for a file socket attempt, so failed attempts need not consume the accepted offer. */
    public SessionKeys forFileConnection(byte[] nonce) throws GeneralSecurityException {
        if (nonce.length != 32) throw new GeneralSecurityException("File connection nonce must be 32 bytes");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("DLP-v1 peer-file-connection".getBytes(StandardCharsets.US_ASCII));
        byte[] salt = digest.digest(nonce);
        byte[] forwardSecret = initiatorToResponder.getEncoded();
        byte[] reverseSecret = responderToInitiator.getEncoded();
        try {
            return deriveDirections(forwardSecret, reverseSecret, salt);
        } finally {
            Arrays.fill(forwardSecret, (byte) 0);
            Arrays.fill(reverseSecret, (byte) 0);
        }
    }

    private static SessionKeys deriveDirections(byte[] forwardSecret, byte[] reverseSecret, byte[] salt)
            throws GeneralSecurityException {
        // Keep the existing wire labels; role names also apply to P2P initiators/responders.
        byte[] forward = Hkdf.derive(forwardSecret, salt,
                "DLP-v1 client-to-server AES-256-GCM".getBytes(StandardCharsets.US_ASCII), 32);
        byte[] reverse = null;
        try {
            reverse = Hkdf.derive(reverseSecret, salt,
                    "DLP-v1 server-to-client AES-256-GCM".getBytes(StandardCharsets.US_ASCII), 32);
            return new SessionKeys(new SecretKeySpec(forward, "AES"), new SecretKeySpec(reverse, "AES"));
        } finally {
            Arrays.fill(forward, (byte) 0);
            if (reverse != null) Arrays.fill(reverse, (byte) 0);
        }
    }
}
```

</details>

<details>
<summary>PacketChannel: vai trò và xác nhận khóa</summary>

```java
public enum Role { INITIATOR, RESPONDER }

    /** Enable encryption and prove possession of both directional keys before application traffic. */
    public void confirmEncryption(SessionKeys keys, Role role) throws IOException {
        enableEncryption(keys, role);
        if (role == Role.INITIATOR) {
            long sequence = send(Packet.empty(OpCode.KEY_CONFIRM_REQUEST));
            Packet response = PacketValidator.validateResponse(read(), OpCode.KEY_CONFIRM_RESPONSE, sequence);
            PacketValidator.requireSuccess(response);
        } else {
            Packet request = PacketValidator.validateRequest(read(), OpCode.KEY_CONFIRM_REQUEST, 0, 0);
            send(Packet.status(OpCode.KEY_CONFIRM_RESPONSE, request.sequence(), "OK"));
        }
    }
```

</details>

<details>
<summary>FileTransferController: trạng thái của một lượt truyền</summary>

```java
/** Mutable fields belong to this controller's lock; the session has its own state lock. */
    private static final class Transfer {
        final FileTransferSession session;
        final PeerConnection peer;
        final File source;
        String offerNumber;
        long offerSequence;
        KeyPair keyPair;
        PublicKey peerPublicKey;
        SessionKeys keys;
        Socket socket;
        ScheduledFuture<?> timeout;

        Transfer(FileTransferMeta meta, PeerConnection peer, File source) {
            session = new FileTransferSession(meta);
            this.peer = peer;
            this.source = source;
        }

        String id() { return session.transferId(); }
    }
```

</details>

<details>
<summary>FileTransferController: xác thực trước khi claim socket</summary>

```java
public void receiveFileConnection(PacketChannel channel, Packet hello) {
        Transfer transfer = null;
        boolean claimed = false;
        try {
            Socket socket = channel.socket();
            socket.setSoTimeout(TRANSFER_TIMEOUT_MS);
            PacketValidator.validateRequest(hello, OpCode.FILE_TRANSFER_START, 1, 256);
            SessionKeys baseKeys;
            synchronized (this) {
                transfer = transfers.get(hello.string("transferId"));
                if (transfer == null || !transfer.session.meta().receiver().equals(username)) {
                    throw new IOException("Unknown incoming file transfer");
                }
                requireActive(transfer, FileTransferSession.State.ACCEPTED);
                if (transfer.socket != null) throw new IOException("File transfer already claimed");
                baseKeys = transfer.keys;
            }
            // Each attempt gets fresh traffic keys. Failed authentication leaves the offer untouched.
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            SessionKeys connectionKeys = connectionKeys(baseKeys, Base64.getEncoder().encodeToString(nonce));
            channel.send(Packet.reply(OpCode.FILE_READY, hello.sequence(),
                    Json.object("status", "OK", "transferId", transfer.id(),
                            "connectionNonce", Base64.getEncoder().encodeToString(nonce))));
            channel.confirmEncryption(connectionKeys, PacketChannel.Role.RESPONDER);
            synchronized (this) {
                requireActive(transfer, FileTransferSession.State.ACCEPTED);
                if (transfer.socket != null) throw new IOException("File transfer already claimed");
                transfer.socket = socket;
                transfer.keys = null;
                transfer.session.startTransfer();
                cancelTimeout(transfer);
                claimed = true;
            }
            File saved = new FileReceiver(channel, downloadDir, transfer.session.meta(), transfer.session, null).receive();
            report(transfer, ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
        } catch (IOException | RuntimeException e) {
            if (claimed) fail(transfer, e);
            else {
                // No mutation of a pending transfer for an unauthenticated or duplicate socket.
                try { channel.send(Packet.failure(channel.lastReceivedSequence(), message(e))); }
                catch (IOException ignored) { }
            }
        } finally {
            if (claimed) finish(transfer);
            try { channel.close(); }
            catch (IOException e) { System.err.println("Cannot close file socket: " + message(e)); }
        }
    }

    private static SessionKeys connectionKeys(SessionKeys baseKeys, String encodedNonce) throws IOException {
        if (baseKeys == null) throw new IOException("Missing file transfer keys");
        try {
            return baseKeys.forFileConnection(Base64.getDecoder().decode(encodedNonce));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Invalid file connection nonce or keys", e);
        }
    }
```

</details>

<details>
<summary>FileTransferController: dọn tài nguyên và đóng peer</summary>

```java
private synchronized void finish(Transfer transfer) {
        if (!transfers.remove(transfer.id(), transfer)) return;
        cancelTimeout(transfer);
        transfer.keyPair = null;
        transfer.peerPublicKey = null;
        transfer.keys = null;
        if (transfer.socket != null) {
            try { transfer.socket.close(); }
            catch (IOException e) { System.err.println("Cannot close file transfer: " + message(e)); }
            transfer.socket = null;
        }
    }

    public synchronized void closeForPeer(PeerConnection peer) {
        for (Transfer transfer : List.copyOf(transfers.values())) {
            if (peer != null && transfer.peer != peer) continue;
            try {
                boolean cancelled;
                synchronized (transfer.session) {
                    cancelled = transfer.session.state() != FileTransferSession.State.COMPLETED;
                    if (cancelled) transfer.session.cancel();
                }
                if (cancelled) {
                    report(transfer, ClientEvents.FileState.FAILED, "Peer connection closed");
                }
            } finally { finish(transfer); }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        try { closeForPeer(null); }
        finally { timeoutExecutor.shutdownNow(); }
    }
}
```

</details>


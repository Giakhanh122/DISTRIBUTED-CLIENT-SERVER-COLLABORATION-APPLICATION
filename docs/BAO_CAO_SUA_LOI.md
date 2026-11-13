# Báo cáo kiểm tra và sửa lỗi nhánh `refactor`

Ngày kiểm tra: **13/11/2026**. Mốc gốc: commit `f48fb7a`.

> **Cập nhật sau kiểm thử:** Theo yêu cầu, đã xóa source test, dependency/cấu hình
> test tạm, class test, report máy sinh và dữ liệu download test. Các kết quả bên
> dưới là lịch sử kiểm chứng trước khi dọn, không phải bộ test còn có thể chạy
> trong repository hiện tại. Các lần kiểm thử tiếp theo sẽ dọn tài nguyên test
> sau khi chạy. Phần sửa lỗi của app được giữ nguyên.

## 1. Phạm vi và trạng thái Git

- Ban đầu workspace sạch, nhánh hiện tại là `refactor/client-cleanup`.
- Không có nhánh `refactor` trong local hoặc các remote ref đã lưu. Đã đổi tên nhánh local hiện tại thành `refactor`, không thay lịch sử commit. Không fetch, commit hoặc push.
- Giữ mô hình client–server kết hợp P2P, framing `[length][JSON]`, opcode và schema packet hiện có. Lookup online trả trạng thái `OK` hiện hữu thay vì chặn bởi metadata `CONNECTED` có thể đã cũ.
- Không khôi phục nguyên đợt nâng cấp đã revert; chỉ sửa lỗi có căn cứ trong phiên kiểm tra này. Không thêm tính năng tiến độ file, tự retry, hay xác thực mới.

## 2. Các lỗi xác nhận

| Mức độ | Lỗi, nguyên nhân và cách tái hiện | Khắc phục |
| --- | --- | --- |
| Cao | **Hủy sát lúc công bố file:** receiver nhận đủ dữ liệu, callback hoặc cleanup hủy session; `FileReceiver` vẫn chuyển file tạm sang đích rồi `complete()` ném `IllegalStateException`. Có thể để lại file hoàn chỉnh nhưng báo lỗi. Test hủy ngay sau chunk cuối tái hiện trên code gốc. | Khóa session cho cả kiểm tra trạng thái, công bố file và đánh dấu hoàn tất. Session đã hủy không được công bố. Broadcast download cũng được gắn session để áp dụng cùng quy tắc. |
| Cao | **Ghi TCP không có giới hạn:** `SO_TIMEOUT` chỉ áp dụng đọc. Bên nhận ngừng đọc có thể giữ writer và hàng đợi lệnh vô hạn. | Deadline riêng 30 giây cho mỗi packet/chunk, tính cả chờ khóa ghi. Đóng socket giải phóng writer và reader; timer được hủy và gỡ khỏi scheduler sau mỗi thao tác. Không giới hạn tổng thời gian truyền file. |
| Cao | **P2P phụ thuộc phản hồi metadata:** `registerPeer()` chờ report server trước khi mở reader; `readPeer()` chờ report trước khi gỡ peer và đóng socket. Server im lặng khiến peer còn bị xem là sống hoặc tin đến chậm. | Xếp report FIFO ở executor riêng. CONNECTED được enqueue khi đăng ký peer, trước khi reader enqueue DISCONNECTED. Không giữ khóa registry trong I/O server; reader đóng và dọn peer ngay. |
| Cao | **Kết quả transfer lặp:** `closeForPeer()` báo FAILED, sau đó worker bị đánh thức bởi socket đóng lại báo FAILED. | Session có cơ chế nhận quyền phát kết quả cuối một lần. Cleanup không chuyển session đã hoàn tất sang lỗi; GUI cũng không ghi đè trạng thái cuối bởi callback đến muộn. |
| Trung bình | **Đóng session có thể bị giữ:** logout chờ lần lượt report và LOGOUT_RESPONSE; socket outgoing đang connect/handshake chưa được quản lý. GUI còn enqueue `close()` sau lệnh mạng đang kẹt. | Đóng socket peer đang handshake và peer hoạt động trước; thử report DISCONNECTED/LOGOUT trong ngân sách 500 ms rồi đóng TCP quản lý để server dọn user trên EOF nếu không xác nhận được. GUI chạy close ở virtual thread riêng. Report worker được shutdown, broadcast worker có thời gian chờ kết thúc hữu hạn. |
| Trung bình | **Request trước khi reader chạy:** tạo `ServerConnection` rồi gọi `request()` mà chưa `start()` sẽ chờ đến timeout thay vì trả lỗi trạng thái. Regression test gốc vượt deadline 500 ms. | Từ chối request khi reader chưa chạy hoặc connection đã đóng. |
| Trung bình | **Race đăng ký pending với close:** close có thể đọc `pending == null` sau kiểm tra trạng thái đầu request nhưng trước callback `beforeWrite`, làm bỏ lỡ future cần giải phóng. | Đăng ký pending và đánh dấu closed dùng chung khóa vòng đời. Không giữ khóa này trong socket close hoặc toàn bộ thời gian chờ phản hồi. Giữ cơ chế request tuần tự đang hoạt động. |
| Trung bình | **Tin quá dài làm rớt peer khỏe ở CLI:** `PeerConnection.sendMessage()` từ chối trên 64 KiB; `ClientSession.sendMessage(String)` coi mọi IOException là lỗi mạng và đóng socket. | Giữ socket khi chỉ thất bại kiểm tra dữ liệu tại chỗ. PacketChannel vẫn đóng socket khi I/O thật bị lỗi. Regression gốc thất bại ở thao tác chọn lại peer. |
| Trung bình | **Metadata cũ ngăn nối lại:** server trả ALREADY_CONNECTED không có địa chỉ dù client đã mất socket cục bộ. Client không có gì để kết nối lại. | Online lookup trả OK kèm địa chỉ hiện có. Client vẫn tái sử dụng peer cục bộ trước khi lookup. Không thêm trường hoặc opcode vào giao thức. |
| Trung bình | **GUI ghi lịch sử như đã gửi dù tác vụ lỗi:** gửi chạy bất đồng bộ nhưng bubble được thêm ngay, không liên kết kết quả với message ban đầu. | Bubble có Sending, Sent (unconfirmed), Failed với lý do. Callback giữ đúng model và target ban đầu, không dùng cuộc trò chuyện đang mở lúc callback trở về. Bảo toàn các overload public cũ, thêm overload callback cho GUI. |
| Trung bình | **Polling và logout chung hàng đợi lệnh:** refresh bị chờ mạng giữ cả gửi P2P hoặc close; callback lỗi đăng nhập có thể đua với logout/new login. | Tách polling khỏi executor lệnh; close không enqueue phía sau lệnh. Kiểm tra generation và thay đổi trạng thái login dưới cùng monitor. Ngừng polling khi server đóng, bỏ snapshot sau disconnect. |
| Trung bình | **Leave community xóa selection mới:** `leave(id)` chụp selection trước request rồi dùng bản chụp để gán null, có thể xóa community B được caller khác chọn trong lúc chờ leave A. | Chỉ xóa selection hiện tại nếu ID vẫn là community vừa rời; cập nhật selection và kiểm tra/xóa dùng cùng monitor ngắn, không khóa I/O toàn cục. |
| Trung bình | **Lưu xong nhưng ACK lỗi làm receiver báo thất bại:** file đã được xác minh và công bố, việc gửi FILE_RESULT thất bại đi vào nhánh lỗi chung. | Receiver giữ kết quả lưu hợp lệ và ghi log việc không gửi được ACK. Sender vẫn phải nhận đúng ACK mới xác nhận thành công. Không nuốt lỗi dữ liệu hoặc tự retry. |
| Trung bình | **Broadcast upload không ràng buộc nguồn với offer khi gửi:** checksum tính lúc tạo request; lúc stream dùng FileSender không có session nên chỉ kiểm tra nguồn ở thời điểm gửi, server sẽ phát hiện muộn hơn. | Truyền session chứa kích thước/checksum đã offer vào FileSender để lỗi nguồn thay đổi được nhận diện ở client; giữ kiểm tra integrity phía server. |
| Thấp | **Trạng thái GUI offline không nhất quán:** chỉ mở dialog nhưng vẫn hiện online; các thao tác community có thể mở input lại; list server có thể loại peer còn P2P sống. | Hiện Server offline, khóa input community khi cần server, giữ peer trực tiếp còn sống trong danh sách. |
| Thấp | **Hạn mức tài liệu không khớp:** README/protocol ghi 100 MiB, metadata và server đang dùng 500 MiB, GUI hardcode riêng. | Giữ hạn mức runtime hiện có 500 MiB, GUI kiểm tra bằng `FileTransferMeta.MAX_FILE_SIZE`, sửa tài liệu tương ứng. |

Broadcast GUI đã loại bỏ fallback dùng community đang chọn khi thiếu ID. Packet hợp lệ đã được server/client gắn và đọc `communityId`; đây là việc loại bỏ đường suy đoán sai ngữ cảnh, không khẳng định đã quan sát packet hợp lệ thiếu ID.

## 3. File và hàm đã sửa

| File | Hàm/phần chính |
| --- | --- |
| `client/Client.java` | `connectToServer()` dùng API login có connect timeout sẵn có. Đọc credentials trước khi handshake, tránh thời gian nhập tay tiêu thụ timeout chờ AUTH của server. |
| `client/ClientSession.java` | `start`, `connectPeer`, `sendMessage(String)`, `registerPeer`, `readPeer`, `close`; thêm worker report và truy vấn trạng thái cho GUI. |
| `client/ServerConnection.java` | `start`, `request`, `close`; đồng bộ đăng ký pending với đóng connection. |
| `client/FileTransferController.java` | Các nhánh kết thúc, `closeForPeer`, helper `reportResult`; thống nhất phát kết quả cuối. |
| `client/BroadcastFileController.java` | `uploadFile`, `downloadFile`, `trackSession`, `close`; theo dõi session riêng và hủy trước công bố. |
| `client/CommunityClient.java` | `leave(String)`, `selectFrom`; giữ selection mới khi request leave cũ kết thúc. |
| `protocols/PacketChannel.java` | `send`, `writeRaw`, helper `WriteDeadline`; timer ghi theo socket. |
| `server/connection/ClientHandler.java` | `handlePeerLookup`; metadata không chặn trả địa chỉ online. |
| `transfer/FileReceiver.java` | `receive`; đồng bộ hủy/công bố, tách kết quả lưu khỏi lỗi gửi ACK. |
| `transfer/FileTransferSession.java` | `markResultReported`; quyền phát kết quả cuối gắn với session, không cần set ID tồn tại mãi. |
| `ui/adapter/ChatClientAdapter.java` | Polling executor riêng, `submitMessage`, overload gửi callback, `login`, `logout`, xử lý server close và trạng thái file. |
| `ui/controller/ChatController.java` | Gửi đúng target/model, phân loại broadcast bằng ID nguồn, trạng thái offline và danh sách peer còn sống. |
| `ui/model/ChatMessageModel.java` | Property trạng thái gửi, cập nhật quan sát được trên FX thread. |
| `ui/view/ChatMessageView.java` | Bind trạng thái bubble; Reject hiển thị đang xử lý thay vì khẳng định thành công trước phản hồi. |
| `pom.xml` | Dependency JUnit và cấu hình Surefire tạm đã được gỡ sau khi dọn test. |
| `README.md`, `docs/protocol.md` | Hạn mức, cách chạy test, timeout, lookup và semantics ACK/logout. |

Không đổi tên hoặc bỏ public method đang có. Constructor điều chỉnh timeout của PacketChannel dành cho test chỉ có package access. Không thêm dependency runtime.

## 4. Những phần đã kiểm tra nhưng không xác nhận lỗi

- **Định danh người nhận:** GUI đã gửi bằng `sendMessage(target, text)`, không phụ thuộc selectedPeer toàn cục. Map giữ nhiều peer; chỉ entry đúng connection bị gỡ khi disconnect. Test ba client kiểm tra từng nội dung B/C và peer còn lại sau khi một peer đóng.
- **Khóa ghi:** PacketChannel đã có writeLock per socket; không có hai writer tự ý dùng OutputStream ở luồng chat/file thông thường. Thử 8 writer gửi 160 frame kiểm tra không thiếu/trùng/xen frame. Không thay bằng global writer lock.
- **TCP stream:** PacketCodec/PacketChannel dùng đọc đủ byte, kiểm tra length/sequence. Test packet bị chia byte và EOF giữa header đạt. Không coi ranh giới read là ranh giới packet.
- **Offer ID:** P2P và broadcast đã dùng chung AtomicLong trong một login; UUID dùng trên wire. Không cần thiết kế lại ID. Test giữ hai offer pending, reject số 1, accept số 2, đồng thời truyền ngược chiều và kiểm tra đúng ID/checksum/kết quả từng transfer.
- **Community:** server đã kiểm tra thành viên cho join/select/leave và broadcast. Client kiểm tra status trước cập nhật Join/Leave. Test một client thuộc hai community, hai sender broadcast đồng thời, người ngoài không nhận tin community khác; thao tác select không có quyền bị từ chối mà phiên vẫn hoạt động.
- **Structured events:** ClientEvents và ClientEventListener đã được dùng, GUI không parse System.out/System.err. JavaFX model/control được cập nhật qua dispatch về FX Application Thread.
- **Tương quan response:** request server hiện được serialize và kiểm tra opcode/replyTo. 480 request trộn users/community từ 8 caller trả đúng nội dung. Không đổi thành multiplex để tránh refactor không cần thiết.
- **Integrity:** dữ liệu file đọc đúng fileSize, kiểm tra SHA-256, FILE_END và ACK. Test EOF giữa file, checksum sai, hủy sát công bố và ACK lỗi dùng socket thật. Không bỏ cơ chế integrity đã có.

## 5. Build và kiểm thử

### Cách chạy

```bash
mvn test
mvn -Dtest.javafx=true test
mvn -Dtest.javafx=true package
```

Trong phiên này dùng thêm `-o` vì dependency đã có trong Maven cache. Test socket được chạy ngoài sandbox sau khi bản chạy sandbox trả `SocketException: Operation not permitted`. Đây là hạn chế môi trường, không tính là lỗi ứng dụng. Không truy cập dịch vụ bên ngoài.

JavaFX cần `DISPLAY` và `test.javafx=true`; thiếu điều kiện thì test UI được bỏ qua có chủ đích. Trên Linux headless có Xvfb: `xvfb-run -a mvn -Dtest.javafx=true test`.

### Bằng chứng trước sửa

- `mvn -o clean test` trên code ban đầu: BUILD SUCCESS, **No tests to run**.
- Bản code gốc được trích từ Git vào thư mục riêng `/tmp/refactor-before`, chỉ thêm test và cấu hình test; ba regression đều thất bại:
  1. request trước start vượt deadline 500 ms;
  2. tin quá dài làm chọn lại peer thất bại;
  3. hủy sau chunk cuối gây IllegalStateException thay vì lỗi hủy có kiểm soát, bước công bố file đã đi qua.
- Không đổi expectation của ba regression này để hợp thức hóa lỗi sản phẩm.

### Bộ test sau sửa

Lệnh cuối: `mvn -o -Dtest.javafx=true package` — **BUILD SUCCESS**, **20 test**, **0 failures**, **0 errors**, **0 skipped**. Có test JavaFX thực trên màn hình X. Chạy trên OpenJDK 25.0.4.1, biên dịch `release 21`; thời gian build cuối 50,540 giây. `git diff --check` đạt. Không commit hoặc push.

| Bộ test | Số test | Nội dung |
| --- | ---: | --- |
| `client.ConnectionRegressionTest` | 4 | Trước start, dữ liệu chat quá dài, logout giữa handshake, giải phóng caller đang chờ/đợi request lock. |
| `client.ApplicationTest` | 7 | Ba client chuyển peer, hai community broadcast đồng thời, 480 request, offer P2P/broadcast và chat hai chiều, server tắt, metadata cũ, logout giữ DISCONNECTED. |
| `client.FileDisconnectTest` | 1 | Accept thật qua socket chat, data socket ngắt giữa file, dọn file tạm và chỉ một kết quả kể cả cleanup từ callback lỗi. |
| `protocols.PacketChannelTest` | 3 | Nhiều writer, TCP fragmentation/EOF, receiver không đọc gây write timeout. |
| `transfer.FileReceiverTest` | 4 | Hủy sát công bố, ngắt giữa dữ liệu, checksum sai, lưu thành công khi ACK không gửi được. |
| `ui.ChatUiTest` | 1 | FXML/control thật, chọn peer, gửi Unicode, bubble lỗi, server offline và tiếp tục P2P. |
| **Tổng** | **20** | Test dùng TCP loopback thật và handler production. |

Các test đồng bộ bằng latch, future và queue với deadline; không sleep/busy-wait. Không dùng kiểm tra chuỗi log làm đáp án cho routing hoặc integrity. Bộ request concurrency kiểm tra tính đúng đắn, không đặt kỳ vọng throughput của cơ chế tuần tự. Kết quả receiver và sender được kiểm tra theo đúng transfer ID, kể cả khi một client đồng thời ở hai vai trò.

Test không dùng thư mục download/storage của người dùng: working directory là target, nguồn và storage server ở thư mục tạm.

## 6. Giới hạn còn lại

- Chưa có delivery/read receipt cho chat. `Sent (unconfirmed)` chỉ xác nhận ghi ra kết nối; không chứng minh bên kia đã nhận hoặc đọc. Không tự retry để tránh tạo tin trùng.
- Không tự reconnect server hoặc lưu credentials. Đăng xuất/đăng nhập lại khi server sẵn sàng; lookup peer không còn bị metadata cũ chặn. Chưa chứng minh mọi lịch xen kẽ của hai peer khởi tạo kết nối chéo.
- Không có heartbeat cho peer idle. Khi đường mạng mất mà không có FIN/RST và không phát sinh I/O, không thể khẳng định trạng thái offline ngay lập tức; write deadline xử lý khi có gửi dữ liệu.
- Server broadcast vẫn gửi từng người nhận theo thứ tự. Ghi có deadline nhưng nhiều người nhận chậm vẫn có thể gây trễ; chưa có benchmark tải nhiều máy.
- Kiểm thử file dùng kích thước vài MiB; chưa benchmark hoặc kiểm thử biên 500 MiB, file rỗng và mọi hệ thống file trong đợt này. Cơ chế thêm hậu tố file trùng tên có sẵn được đọc mã, chưa có test riêng cho mọi lịch hoàn tất đồng thời trong bộ test mới.
- Test JavaFX chứng minh các thao tác được tự động hóa, không thay thế kiểm tra toàn bộ layout, chuyển cửa sổ, dialog và mở file bằng ứng dụng OS.
- Chưa kiểm chứng UDP discovery nhiều NIC, NAT/firewall, nhiều máy thật, đóng gói Windows hoặc mọi kịch bản reconnect sau server restart. Không khẳng định đã xử lý toàn bộ lỗi của hệ phân tán.
- Trường hợp receiver đã lưu xong nhưng ACK mất có thể khiến hai phía có kết quả khác nhau. Đây là trạng thái chưa xác nhận bên sender, không được suy thành file hỏng hay bảo đảm exactly-once.
- Các cảnh báo native access/Unsafe từ JDK/JavaFX/Maven vẫn có thể xuất hiện; không được xem là lỗi test nếu build/test vẫn thành công.

## 7. Code thay đổi để đối chiếu

Các mục có thể mở rộng dưới đây là diff code ứng dụng so với commit gốc. Code test tạm đã được dọn.

<details>
<summary><strong>src/main/java/client/BroadcastFileController.java</strong></summary>

```diff
diff --git a/src/main/java/client/BroadcastFileController.java b/src/main/java/client/BroadcastFileController.java
index 68be62d..5151d25 100644
--- a/src/main/java/client/BroadcastFileController.java
+++ b/src/main/java/client/BroadcastFileController.java
@@ -17,6 +17,7 @@ import protocols.PacketChannel;
 import protocols.PacketValidator;
 import transfer.FileReceiver;
 import transfer.FileSender;
+import transfer.FileTransferSession;
 
 final class BroadcastFileController implements AutoCloseable {
     private static final int CONNECT_TIMEOUT_MS = 10000;
@@ -38,6 +39,7 @@ final class BroadcastFileController implements AutoCloseable {
     private final Map<String, Offer> offers = new HashMap<>();
     private final Map<String, String> offerIds = new HashMap<>();
     private final Set<Socket> sockets = new HashSet<>();
+    private final Map<String, FileTransferSession> sessions = new HashMap<>();
     private final Set<Thread> workers = new HashSet<>();
     private volatile boolean closed;
 
@@ -128,13 +130,15 @@ final class BroadcastFileController implements AutoCloseable {
             PacketChannel channel = new PacketChannel(socket);
             channel.send(Packet.json(OpCode.FILE_BROADCAST_UPLOAD_START,
                     Json.object("fileId", fileId, "uploadToken", response.string("uploadToken"))));
-            FileSender sender = new FileSender(file, fileId, channel);
+            FileTransferSession upload = trackSession(new FileTransferMeta(fileId, username, community.name(),
+                    file.getName(), size, checksum));
+            FileSender sender = new FileSender(file, fileId, channel, upload, null);
             sender.send();
             sender.awaitResult();
         } finally {
             forgetSocket(socket);
+            synchronized (lifecycleLock) { sessions.remove(fileId); }
         }
-        requireOpen();
         events.onFileState(id, ClientEvents.FileState.COMPLETED, file.getAbsolutePath());
         System.out.println("[BROADCAST] File '" + file.getName() + "' uploaded and broadcast to community '"
                 + community.name() + "'. SHA-256: " + checksum);
@@ -151,12 +155,23 @@ final class BroadcastFileController implements AutoCloseable {
                     Json.object("fileId", offer.fileId(), "downloadToken", response.string("downloadToken"))));
             FileTransferMeta meta = new FileTransferMeta(offer.fileId(), offer.sender(), username,
                     offer.fileName(), offer.fileSize(), offer.checksum());
-            File saved = new FileReceiver(channel, downloadDir, meta).receive();
-            requireOpen();
+            FileTransferSession download = trackSession(meta);
+            File saved = new FileReceiver(channel, downloadDir, meta, download, null).receive();
             events.onFileState(offer.fileId(), ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
             System.out.println("[BROADCAST RECEIVED] Saved to: " + saved.getAbsolutePath());
         } finally {
             forgetSocket(socket);
+            synchronized (lifecycleLock) { sessions.remove(offer.fileId()); }
+        }
+    }
+
+    private FileTransferSession trackSession(FileTransferMeta meta) throws IOException {
+        synchronized (lifecycleLock) {
+            requireOpen();
+            FileTransferSession session = new FileTransferSession(meta);
+            session.accept();
+            sessions.put(meta.transferId(), session);
+            return session;
         }
     }
 
@@ -240,6 +255,7 @@ final class BroadcastFileController implements AutoCloseable {
         Set<Socket> closing;
         synchronized (lifecycleLock) {
             closed = true;
+            sessions.values().forEach(FileTransferSession::cancel);
             offers.clear();
             offerIds.clear();
             closing = Set.copyOf(sockets);
```

</details>

<details>
<summary><strong>src/main/java/client/Client.java</strong></summary>

```diff
diff --git a/src/main/java/client/Client.java b/src/main/java/client/Client.java
index 47930c5..bb8dce0 100644
--- a/src/main/java/client/Client.java
+++ b/src/main/java/client/Client.java
@@ -32,18 +32,11 @@ public class Client {
     }
 
     public void connectToServer(String host, int port, Scanner scanner) {
-        try (Socket socket = new Socket(host, port)) {
-            socket.setSoTimeout(10000);
-            PacketChannel channel = new PacketChannel(socket);
-            if (!handshake(channel)) return;
-            ClientConsole.Credentials credentials = ClientConsole.readCredentials(scanner);
-            if (credentials == null) return;
-            try (ClientSession session = new ClientSession(credentials.username(), host, port, channel)) {
-                if (!authenticate(channel, credentials.username(), credentials.password(), session.peerPort())) return;
-                socket.setSoTimeout(0);
-                session.start();
-                new ClientConsole(session).run(scanner);
-            }
+        ClientConsole.Credentials credentials = ClientConsole.readCredentials(scanner);
+        if (credentials == null) return;
+        try (ClientSession session = login(host, port, credentials.username(), credentials.password(), ClientEvents.NONE)) {
+            session.start();
+            new ClientConsole(session).run(scanner);
         } catch (IOException e) {
             System.err.println("Client error: " + e.getMessage());
         }
```

</details>

<details>
<summary><strong>src/main/java/client/ClientSession.java</strong></summary>

```diff
diff --git a/src/main/java/client/ClientSession.java b/src/main/java/client/ClientSession.java
index d6b14a3..5181265 100644
--- a/src/main/java/client/ClientSession.java
+++ b/src/main/java/client/ClientSession.java
@@ -8,6 +8,9 @@ import java.net.Socket;
 import java.nio.charset.StandardCharsets;
 import java.util.Map;
 import java.util.List;
+import java.util.Set;
+import java.util.concurrent.ExecutorService;
+import java.util.concurrent.Executors;
 import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.atomic.AtomicBoolean;
 import java.util.concurrent.atomic.AtomicLong;
@@ -28,6 +31,9 @@ public final class ClientSession implements AutoCloseable {
     private final BroadcastFileController broadcasts;
     private final FileTransferController files;
     private final ConcurrentHashMap<String, PeerConnection> peers = new ConcurrentHashMap<>();
+    private final Set<Socket> connectingSockets = ConcurrentHashMap.newKeySet();
+    private final ExecutorService reports = Executors.newSingleThreadExecutor(
+            Thread.ofVirtual().name("peer-reports-", 0).factory());
     private final Object peerLock = new Object();
     private final AtomicBoolean closing = new AtomicBoolean();
     private volatile String selectedPeer;
@@ -53,12 +59,20 @@ public final class ClientSession implements AutoCloseable {
 
     String username() { return username; }
     int peerPort() { return listener.getPort(); }
+    public boolean isServerConnected() { return started && server.isOpen() && !closing.get(); }
+    public boolean isPeerConnected(String target) {
+        PeerConnection peer = peers.get(target);
+        return !closing.get() && peer != null && !peer.isClosed();
+    }
     public CommunityClient communities() { return communities; }
 
     public void start() {
-        started = true;
-        server.start();
-        Thread.startVirtualThread(listener);
+        synchronized (peerLock) {
+            if (closing.get() || started) throw new IllegalStateException("Session already started or closed");
+            started = true;
+            server.start();
+            Thread.startVirtualThread(listener);
+        }
     }
 
     public JsonArray users() throws IOException {
@@ -115,6 +129,10 @@ public final class ClientSession implements AutoCloseable {
     void connectPeer(String target, InetSocketAddress address) throws IOException {
         Socket socket = new Socket();
         try {
+            synchronized (peerLock) {
+                if (closing.get()) throw new IOException("Session closed");
+                connectingSockets.add(socket);
+            }
             socket.connect(address, 10000);
             PeerConnection connection = new PeerConnection(new PacketChannel(socket));
             connection.initiateHandshake(username, peerPort());
@@ -128,7 +146,7 @@ public final class ClientSession implements AutoCloseable {
                 e.addSuppressed(closeError);
             }
             throw e;
-        }
+        } finally { connectingSockets.remove(socket); }
     }
 
     boolean sendMessage(String message) throws IOException {
@@ -140,13 +158,9 @@ public final class ClientSession implements AutoCloseable {
                 return false;
             }
         }
-        try {
-            peer.sendMessage(message);
-            return true;
-        } catch (IOException e) {
-            closePeer(peer);
-            throw e;
-        }
+        // PacketChannel closes on I/O failure; local validation must leave a healthy peer open.
+        peer.sendMessage(message);
+        return true;
     }
 
     public void sendFile(String target, File file) {
@@ -222,8 +236,8 @@ public final class ClientSession implements AutoCloseable {
 
     private boolean registerPeer(String name, PeerConnection connection) {
         PeerConnection previous = null;
-        // Publishing and the CONNECTED report are ordered against DISCONNECTED on
-        // this peer only. The registry lock is never held while waiting for I/O.
+        // Enqueue CONNECTED while publishing the peer, before its reader can enqueue
+        // DISCONNECTED. The report worker performs network I/O outside peerLock.
         synchronized (connection) {
             boolean rejected;
             synchronized (peerLock) {
@@ -236,6 +250,7 @@ public final class ClientSession implements AutoCloseable {
                     if (!rejected) {
                         boolean firstPeer = peers.isEmpty();
                         previous = peers.put(name, connection);
+                        queueChatReport(name, connection, "CONNECTED");
                         if (selectedPeer == null && firstPeer) selectedPeer = name;
                     }
                 }
@@ -244,11 +259,10 @@ public final class ClientSession implements AutoCloseable {
                 closePeer(connection);
                 return false;
             }
-            reportChatSession(name, connection, "CONNECTED");
         }
         if (previous != null) {
             closePeer(previous);
-            reportChatSession(name, previous, "DISCONNECTED");
+            queueChatReport(name, previous, "DISCONNECTED");
         }
         events.onPeerConnected(name);
         Thread.startVirtualThread(() -> readPeer(name, connection));
@@ -268,15 +282,21 @@ public final class ClientSession implements AutoCloseable {
         } catch (IOException e) {
             if (!closing.get()) System.err.println("Peer " + name + ": " + e.getMessage());
         } finally {
+            closePeer(connection);
             files.closeForPeer(connection);
-            reportChatSession(name, connection, "DISCONNECTED");
+            queueChatReport(name, connection, "DISCONNECTED");
             synchronized (peerLock) {
                 if (peers.remove(name, connection)) {
                     if (name.equals(selectedPeer)) selectedPeer = null;
                     events.onPeerDisconnected(name);
                 }
             }
-            closePeer(connection);
+        }
+    }
+
+    private void queueChatReport(String name, PeerConnection connection, String state) {
+        synchronized (peerLock) {
+            if (!closing.get()) reports.execute(() -> reportChatSession(name, connection, state));
         }
     }
 
@@ -305,33 +325,51 @@ public final class ClientSession implements AutoCloseable {
 
     @Override
     public void close() {
-        if (!closing.compareAndSet(false, true)) return;
-        try { listener.close(); } catch (IOException e) {
-            System.err.println("Peer listener error: " + e.getMessage());
-        }
-        broadcasts.close();
-        files.close();
         Map<String, PeerConnection> closingPeers;
+        Set<Socket> pendingSockets;
         synchronized (peerLock) {
+            if (!closing.compareAndSet(false, true)) return;
             closingPeers = Map.copyOf(peers);
+            pendingSockets = Set.copyOf(connectingSockets);
             peers.clear();
             selectedPeer = null;
         }
-        for (var peer : closingPeers.entrySet()) {
-            closePeer(peer.getValue());
-            reportChatSession(peer.getKey(), peer.getValue(), "DISCONNECTED");
+        try { listener.close(); } catch (IOException e) {
+            System.err.println("Peer listener error: " + e.getMessage());
+        }
+        for (Socket socket : pendingSockets) {
+            try { socket.close(); } catch (IOException e) {
+                System.err.println("Cannot close pending peer: " + e.getMessage());
+            }
         }
+        broadcasts.close();
+        files.close();
+        closingPeers.values().forEach(this::closePeer);
+        // Keep intentional logout metadata when the server responds, but never let
+        // management I/O prevent closing local sockets. The FIFO worker preserves report order.
         try {
             if (started && server.isOpen()) {
-                PacketValidator.requireSuccess(server.request(Packet.empty(OpCode.LOGOUT_REQUEST), OpCode.LOGOUT_RESPONSE));
+                var graceful = reports.submit(() -> {
+                    for (var peer : closingPeers.entrySet()) reportChatSession(peer.getKey(), peer.getValue(), "DISCONNECTED");
+                    try {
+                        if (server.isOpen()) PacketValidator.requireSuccess(server.request(
+                                Packet.empty(OpCode.LOGOUT_REQUEST), OpCode.LOGOUT_RESPONSE));
+                    } catch (IOException e) {
+                        System.err.println("Logout: " + e.getMessage());
+                    }
+                });
+                graceful.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
             }
-        } catch (IOException e) {
-            System.err.println("Logout: " + e.getMessage());
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
+            System.err.println("Graceful logout incomplete: " + e.getClass().getSimpleName());
         } finally {
             try { server.close(); } catch (IOException e) {
                 System.err.println("Cannot close server connection: " + e.getMessage());
             }
-            broadcasts.awaitWorkers();
+            reports.shutdownNow();
         }
+        broadcasts.awaitWorkers();
     }
 }
```

</details>

<details>
<summary><strong>src/main/java/client/CommunityClient.java</strong></summary>

```diff
diff --git a/src/main/java/client/CommunityClient.java b/src/main/java/client/CommunityClient.java
index 7e3e5b2..31b4b55 100644
--- a/src/main/java/client/CommunityClient.java
+++ b/src/main/java/client/CommunityClient.java
@@ -55,7 +55,10 @@ public final class CommunityClient {
         Selection selection = selected;
         request(Packet.json(OpCode.COMMUNITY_LEAVE_REQUEST, Json.object("communityId", id)),
                 OpCode.COMMUNITY_LEAVE_RESPONSE);
-        if (selection != null && selection.id().equals(id)) selected = null;
+        synchronized (this) {
+            // Another caller may have selected a different community while leave waited for its reply.
+            if (selected != null && selected.id().equals(id)) selected = null;
+        }
         return selection;
     }
 
@@ -71,7 +74,7 @@ public final class CommunityClient {
         return Json.object(response.jsonBody(), "community");
     }
 
-    private Selection selectFrom(Packet response) throws IOException {
+    private synchronized Selection selectFrom(Packet response) throws IOException {
         Selection selection = new Selection(response.string("communityId"), response.string("name"));
         selected = selection;
         return selection;
```

</details>

<details>
<summary><strong>src/main/java/client/FileTransferController.java</strong></summary>

```diff
diff --git a/src/main/java/client/FileTransferController.java b/src/main/java/client/FileTransferController.java
index 8beecf0..557f013 100644
--- a/src/main/java/client/FileTransferController.java
+++ b/src/main/java/client/FileTransferController.java
@@ -87,7 +87,7 @@ public final class FileTransferController implements AutoCloseable {
             Thread.startVirtualThread(() -> runP2PSend(file, session, peer));
         } else {
             session.reject();
-            events.onFileState(transferId, ClientEvents.FileState.REJECTED, "Rejected by " + session.meta().receiver());
+            reportResult(session, ClientEvents.FileState.REJECTED, "Rejected by " + session.meta().receiver());
             finishTransfer(session);
             System.out.println("\n[FILE] Transfer '" + session.meta().fileName()
                     + "' was REJECTED by " + session.meta().receiver() + ".");
@@ -104,11 +104,11 @@ public final class FileTransferController implements AutoCloseable {
             FileSender sender = new FileSender(file, session.transferId(), channel, session, null);
             String checksum = sender.send();
             sender.awaitResult();
-            events.onFileState(session.transferId(), ClientEvents.FileState.COMPLETED, file.getAbsolutePath());
+            reportResult(session, ClientEvents.FileState.COMPLETED, file.getAbsolutePath());
             System.out.println("\n[P2P SENT] " + file.getName() + " completed! SHA-256: " + checksum);
         } catch (IOException | RuntimeException e) {
             session.fail();
-            events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, e.getMessage());
+            reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
             System.err.println("\n[P2P SEND ERROR] " + e.getMessage());
         } finally {
             finishTransfer(session);
@@ -144,7 +144,7 @@ public final class FileTransferController implements AutoCloseable {
             } catch (IOException | RuntimeException e) {
                 if (session != null) {
                     session.fail();
-                    events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, e.getMessage());
+                    reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
                     finishTransfer(session);
                 }
                 if (session == null) events.onFileState("", ClientEvents.FileState.FAILED, e.getMessage());
@@ -174,17 +174,18 @@ public final class FileTransferController implements AutoCloseable {
                     Json.object("transferId", transferId, "decision", decision));
             if (decision.equals("ACCEPT")) session.accept();
             else session.reject();
-            events.onFileState(transferId, decision.equals("REJECT") ? ClientEvents.FileState.REJECTED : ClientEvents.FileState.TRANSFERRING, decision);
+            if (decision.equals("ACCEPT")) events.onFileState(transferId, ClientEvents.FileState.TRANSFERRING, decision);
         }
         try {
             peer.sendPacket(response);
         } catch (IOException | RuntimeException e) {
             session.fail();
-            events.onFileState(transferId, ClientEvents.FileState.FAILED, e.getMessage());
+            reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
             finishTransfer(session);
             throw e;
         }
         if (decision.equals("REJECT")) {
+            reportResult(session, ClientEvents.FileState.REJECTED, "Rejected");
             finishTransfer(session);
             System.out.println("[FILE] Rejected offer #" + offerNumber + ".");
         } else {
@@ -192,7 +193,7 @@ public final class FileTransferController implements AutoCloseable {
                 synchronized (FileTransferController.this) {
                     if (session.state() == FileTransferSession.State.ACCEPTED) {
                         session.fail();
-                        events.onFileState(transferId, ClientEvents.FileState.FAILED, "Accepted offer expired");
+                        reportResult(session, ClientEvents.FileState.FAILED, "Accepted offer expired");
                         finishTransfer(session);
                         System.err.println("[FILE] Accepted offer expired: #" + offerNumber);
                     }
@@ -215,12 +216,12 @@ public final class FileTransferController implements AutoCloseable {
             claimed = true;
             File saved = new FileReceiver(channel,
                     downloadDir, session.meta(), session, null).receive();
-            events.onFileState(session.transferId(), ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
+            reportResult(session, ClientEvents.FileState.COMPLETED, saved.getAbsolutePath());
             System.out.println("\n[P2P RECEIVED] Saved to: " + saved.getAbsolutePath());
         } catch (IOException | RuntimeException e) {
             if (claimed) {
                 session.fail();
-                events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, e.getMessage());
+                reportResult(session, ClientEvents.FileState.FAILED, e.getMessage());
             } else {
                 try {
                     channel.send(Packet.failure(channel.lastReceivedSequence(), e.getMessage()));
@@ -277,6 +278,10 @@ public final class FileTransferController implements AutoCloseable {
         }
     }
 
+    private void reportResult(FileTransferSession session, ClientEvents.FileState state, String detail) {
+        if (session.markResultReported()) events.onFileState(session.transferId(), state, detail);
+    }
+
     private synchronized void finishTransfer(FileTransferSession session) {
         if (transferManager.removeSession(session)) {
             incomingOfferIds.values().removeIf(session.transferId()::equals);
@@ -289,8 +294,9 @@ public final class FileTransferController implements AutoCloseable {
     public synchronized void closeForPeer(PeerConnection peer) {
         for (FileTransferSession session : transferManager.getActiveSessions()) {
             if (peer == null || transferPeers.get(session.transferId()) == peer) {
+                if (session.state() == FileTransferSession.State.COMPLETED) continue;
                 session.cancel();
-                events.onFileState(session.transferId(), ClientEvents.FileState.FAILED, "Peer connection closed");
+                reportResult(session, ClientEvents.FileState.FAILED, "Peer connection closed");
                 Closeable connection = transferConnections.get(session.transferId());
                 if (connection != null) {
                     try {
```

</details>

<details>
<summary><strong>src/main/java/client/ServerConnection.java</strong></summary>

```diff
diff --git a/src/main/java/client/ServerConnection.java b/src/main/java/client/ServerConnection.java
index 5ea8329..5579942 100644
--- a/src/main/java/client/ServerConnection.java
+++ b/src/main/java/client/ServerConnection.java
@@ -25,6 +25,7 @@ final class ServerConnection implements AutoCloseable {
     private final PacketChannel channel;
     private final EventHandler events;
     private final AtomicBoolean started = new AtomicBoolean();
+    private final Object lifecycleLock = new Object();
     private final Object requestLock = new Object();
     private final AtomicBoolean closed = new AtomicBoolean();
     private volatile Pending pending;
@@ -40,6 +41,7 @@ final class ServerConnection implements AutoCloseable {
     }
 
     void start() {
+        if (closed.get()) throw new IllegalStateException("Connection closed");
         if (!started.compareAndSet(false, true)) throw new IllegalStateException("Reader already started");
         Thread.startVirtualThread(this::readPackets);
     }
@@ -73,10 +75,15 @@ final class ServerConnection implements AutoCloseable {
 
     Packet request(Packet packet, OpCode responseOpcode) throws IOException {
         synchronized (requestLock) {
-            if (closed.get()) throw new IOException("Server connection closed");
+            if (!started.get() || closed.get()) throw new IOException("Server connection is not running");
             CompletableFuture<Packet> response = new CompletableFuture<>();
             try {
-                channel.send(packet, sequence -> pending = new Pending(responseOpcode, sequence, response));
+                channel.send(packet, sequence -> {
+                    synchronized (lifecycleLock) {
+                        if (closed.get()) throw new IllegalStateException("Server connection closed");
+                        pending = new Pending(responseOpcode, sequence, response);
+                    }
+                });
                 return response.get(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
             } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
@@ -84,7 +91,7 @@ final class ServerConnection implements AutoCloseable {
             } catch (TimeoutException e) {
                 // Close on timeout so a late reply cannot satisfy a later request.
                 throw abort("Timed out waiting for " + responseOpcode, e);
-            } catch (ExecutionException | IOException e) {
+            } catch (ExecutionException | IOException | IllegalStateException e) {
                 throw abort("Request failed: " + responseOpcode, e);
             } finally {
                 pending = null;
@@ -112,10 +119,12 @@ final class ServerConnection implements AutoCloseable {
 
     @Override
     public void close() throws IOException {
-        if (closed.compareAndSet(false, true)) {
+        synchronized (lifecycleLock) {
+            if (!closed.compareAndSet(false, true)) return;
             Pending request = pending;
             if (request != null) request.future().completeExceptionally(new IOException("Server connection closed"));
-            try { channel.close(); } finally { onClosed.run(); }
         }
+        // Socket close must not wait for the writer lock: it releases blocked writes.
+        try { channel.close(); } finally { onClosed.run(); }
     }
 }
```

</details>

<details>
<summary><strong>src/main/java/protocols/PacketChannel.java</strong></summary>

```diff
diff --git a/src/main/java/protocols/PacketChannel.java b/src/main/java/protocols/PacketChannel.java
index ab522c9..28d2ba7 100644
--- a/src/main/java/protocols/PacketChannel.java
+++ b/src/main/java/protocols/PacketChannel.java
@@ -7,9 +7,17 @@ import java.util.function.LongConsumer;
 import java.io.OutputStream;
 import java.net.Socket;
 import java.util.Objects;
+import java.time.Duration;
+import java.util.concurrent.ScheduledThreadPoolExecutor;
+import java.util.concurrent.TimeUnit;
+import java.util.concurrent.atomic.AtomicBoolean;
 
 /** Owns the packet streams, sequence counters, and write lock of one TCP socket. */
 public final class PacketChannel implements AutoCloseable {
+    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(1,
+            Thread.ofPlatform().daemon().name("socket-write-deadlines").factory());
+    static { DEADLINES.setRemoveOnCancelPolicy(true); }
+    private final Duration writeTimeout;
     private final Socket socket;
     private final InputStream input;
     private final OutputStream output;
@@ -19,6 +27,12 @@ public final class PacketChannel implements AutoCloseable {
     private long sentSequence;
 
     public PacketChannel(Socket socket) throws IOException {
+        this(socket, Duration.ofSeconds(30));
+    }
+
+    PacketChannel(Socket socket, Duration writeTimeout) throws IOException {
+        this.writeTimeout = Objects.requireNonNull(writeTimeout);
+        if (writeTimeout.isZero() || writeTimeout.isNegative()) throw new IllegalArgumentException("Invalid timeout");
         this.socket = Objects.requireNonNull(socket);
         this.input = socket.getInputStream();
         this.output = socket.getOutputStream();
@@ -47,27 +61,31 @@ public final class PacketChannel implements AutoCloseable {
     }
 
     public long send(Packet packet, LongConsumer beforeWrite) throws IOException {
-        synchronized (writeLock) {
-            try {
-                long sequence = nextSequence(sentSequence);
-                beforeWrite.accept(sequence);
-                PacketCodec.write(output, packet, sequence);
-                sentSequence = sequence;
-                return sequence;
-            } catch (IOException e) {
-                closeAfterFailure(e);
-                throw e;
+        try (WriteDeadline deadline = new WriteDeadline()) {
+            synchronized (writeLock) {
+                try {
+                    long sequence = nextSequence(sentSequence);
+                    beforeWrite.accept(sequence);
+                    PacketCodec.write(output, packet, sequence);
+                    sentSequence = sequence;
+                    return sequence;
+                } catch (IOException e) {
+                    closeAfterFailure(e);
+                    throw e;
+                }
             }
         }
     }
 
     public void writeRaw(byte[] bytes, int offset, int length) throws IOException {
-        synchronized (writeLock) {
-            try {
-                output.write(bytes, offset, length);
-            } catch (IOException e) {
-                closeAfterFailure(e);
-                throw e;
+        try (WriteDeadline deadline = new WriteDeadline()) {
+            synchronized (writeLock) {
+                try {
+                    output.write(bytes, offset, length);
+                } catch (IOException e) {
+                    closeAfterFailure(e);
+                    throw e;
+                }
             }
         }
     }
@@ -102,6 +120,19 @@ public final class PacketChannel implements AutoCloseable {
 
     public Socket socket() { return socket; }
 
+    /** SO_TIMEOUT bounds reads only; socket close releases a stalled TCP writer. */
+    private final class WriteDeadline implements AutoCloseable {
+        private final AtomicBoolean done = new AtomicBoolean();
+        private final java.util.concurrent.ScheduledFuture<?> timer = DEADLINES.schedule(() -> {
+            if (done.compareAndSet(false, true)) {
+                try { socket.close(); } catch (IOException e) {
+                    System.err.println("Cannot close stalled socket: " + e.getMessage());
+                }
+            }
+        }, writeTimeout.toNanos(), TimeUnit.NANOSECONDS);
+        public void close() { done.set(true); timer.cancel(false); }
+    }
+
     @Override
     public void close() throws IOException {
         socket.close();
```

</details>

<details>
<summary><strong>src/main/java/server/connection/ClientHandler.java</strong></summary>

```diff
diff --git a/src/main/java/server/connection/ClientHandler.java b/src/main/java/server/connection/ClientHandler.java
index 1457cc8..cdd751f 100644
--- a/src/main/java/server/connection/ClientHandler.java
+++ b/src/main/java/server/connection/ClientHandler.java
@@ -228,9 +228,8 @@ public class ClientHandler implements Runnable {
         JsonObject payload;
         if (target.isEmpty()) {
             payload = Json.object("status", "USER_OFFLINE");
-        } else if (chatSessions.isConnected(session.username(), targetUsername)) {
-            payload = Json.object("status", "ALREADY_CONNECTED");
         } else {
+            // Reports are advisory: stale CONNECTED metadata must not block reconnection.
             UserSession targetUser = target.get();
             payload = Json.object("status", "OK", "ip", targetUser.ip(), "peerPort", targetUser.port());
         }
```

</details>

<details>
<summary><strong>src/main/java/transfer/FileReceiver.java</strong></summary>

```diff
diff --git a/src/main/java/transfer/FileReceiver.java b/src/main/java/transfer/FileReceiver.java
index 06cf6af..4702de6 100644
--- a/src/main/java/transfer/FileReceiver.java
+++ b/src/main/java/transfer/FileReceiver.java
@@ -69,11 +69,23 @@ public class FileReceiver {
                         });
             }
 
-            Path target = publishFile(tempFile, directory, safeName);
+            Path target;
+            if (session == null) target = publishFile(tempFile, directory, safeName);
+            else {
+                synchronized (session) {
+                    if (session.state() != FileTransferSession.State.TRANSFERRING) {
+                        throw new IOException("Transfer canceled before publishing");
+                    }
+                    target = publishFile(tempFile, directory, safeName);
+                    session.complete();
+                }
+            }
             tempFile = null;
-            channel.send(Packet.status(OpCode.FILE_RESULT, channel.lastReceivedSequence(), "OK"));
-            if (session != null) {
-                session.complete();
+            try {
+                channel.send(Packet.status(OpCode.FILE_RESULT, channel.lastReceivedSequence(), "OK"));
+            } catch (IOException e) {
+                // Local save is committed. Sender still needs this ACK to confirm delivery.
+                System.err.println("File saved; confirmation failed: " + e.getMessage());
             }
             return target.toFile();
         } catch (IOException | RuntimeException e) {
```

</details>

<details>
<summary><strong>src/main/java/transfer/FileTransferSession.java</strong></summary>

```diff
diff --git a/src/main/java/transfer/FileTransferSession.java b/src/main/java/transfer/FileTransferSession.java
index 28bdef5..3eba8bc 100644
--- a/src/main/java/transfer/FileTransferSession.java
+++ b/src/main/java/transfer/FileTransferSession.java
@@ -16,6 +16,7 @@ public class FileTransferSession {
 
     private final FileTransferMeta meta;
     private State state;
+    private boolean resultReported;
     private long bytesTransferred;
 
     public FileTransferSession(FileTransferMeta meta) {
@@ -106,6 +107,13 @@ public class FileTransferSession {
             || state == State.REJECTED;
     }
 
+    /** Claims the terminal notification, shared by cleanup and transfer worker. */
+    public synchronized boolean markResultReported() {
+        if (resultReported) return false;
+        resultReported = true;
+        return true;
+    }
+
     private void requireState(State expected, String action) {
         if (state != expected) {
             throw new IllegalStateException(
```

</details>

<details>
<summary><strong>src/main/java/ui/adapter/ChatClientAdapter.java</strong></summary>

```diff
diff --git a/src/main/java/ui/adapter/ChatClientAdapter.java b/src/main/java/ui/adapter/ChatClientAdapter.java
index bd649e2..5d7b81c 100644
--- a/src/main/java/ui/adapter/ChatClientAdapter.java
+++ b/src/main/java/ui/adapter/ChatClientAdapter.java
@@ -40,6 +40,11 @@ public final class ChatClientAdapter {
         thread.setDaemon(true);
         return thread;
     });
+    private final ScheduledExecutorService polling = Executors.newSingleThreadScheduledExecutor(task -> {
+        Thread thread = new Thread(task, "UI-Client-Polling");
+        thread.setDaemon(true);
+        return thread;
+    });
     private final AtomicLong generation = new AtomicLong();
     private volatile ClientSession session;
     private volatile boolean connected;
@@ -55,6 +60,11 @@ public final class ChatClientAdapter {
 
     public void addListener(ClientEventListener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
     public void removeListener(ClientEventListener listener) { listeners.remove(listener); }
+    public boolean isServerConnected() { return connected; }
+    public boolean isPeerConnected(String peer) {
+        ClientSession current = session;
+        return current != null && current.isPeerConnected(peer);
+    }
     public String getCurrentUsername() { return currentUsername; }
     public List<String> getLastKnownOnlineUsers() { return onlineUsers; }
     public List<CommunityModel> getLastKnownCommunities() { return communities; }
@@ -81,7 +91,7 @@ public final class ChatClientAdapter {
         if (poller != null) { poller.cancel(false); poller = null; }
         ClientSession previous = session;
         session = null;
-        if (previous != null) executor.execute(previous::close);
+        if (previous != null) Thread.startVirtualThread(previous::close);
         currentUsername = username;
         executor.execute(() -> {
             try {
@@ -93,17 +103,21 @@ public final class ChatClientAdapter {
                     loggingIn = false;
                     dispatch(token, listener -> listener.onLoginSuccess(username));
                     opened.start();
-                    poller = executor.scheduleWithFixedDelay(() -> {
+                    poller = polling.scheduleWithFixedDelay(() -> {
                         if (generation.get() != token || !connected) return;
                         try { refresh(opened, token); }
-                        catch (IOException e) { dispatch(token, l -> l.onSystemNotification(e.getMessage(), true)); }
+                        catch (IOException | RuntimeException e) {
+                            if (opened.isServerConnected()) dispatch(token, l -> l.onSystemNotification(e.getMessage(), true));
+                        }
                     }, 0, 3, TimeUnit.SECONDS);
                 }
             } catch (IOException | RuntimeException e) {
-                if (generation.get() == token) {
-                    loggingIn = false;
-                    connected = false;
-                    dispatch(token, listener -> listener.onLoginFailed(e.getMessage()));
+                synchronized (this) {
+                    if (generation.get() == token) {
+                        loggingIn = false;
+                        connected = false;
+                        dispatch(token, listener -> listener.onLoginFailed(e.getMessage()));
+                    }
                 }
             }
         });
@@ -135,7 +149,7 @@ public final class ChatClientAdapter {
         for (JsonElement entry : current.users()) users.add(entry.getAsString());
         List<String> snapshot = List.copyOf(users);
         JavaFxUtils.runOnFxThread(() -> {
-            if (generation.get() != token || snapshot.equals(onlineUsers)) return;
+            if (generation.get() != token || !connected || snapshot.equals(onlineUsers)) return;
             onlineUsers = snapshot;
             listeners.forEach(l -> l.onUserListUpdated(snapshot));
         });
@@ -145,7 +159,7 @@ public final class ChatClientAdapter {
         List<JsonObject> data = new ArrayList<>();
         for (JsonElement entry : current.communities().list()) data.add(Json.asObject(entry));
         JavaFxUtils.runOnFxThread(() -> {
-            if (generation.get() != token) return;
+            if (generation.get() != token || !connected) return;
             List<CommunityModel> snapshot = new ArrayList<>();
             try {
                 for (JsonObject entry : data) snapshot.add(new CommunityModel(Json.string(entry, "communityId"),
@@ -194,6 +208,27 @@ public final class ChatClientAdapter {
         });
     }
     public void sendChatMessage(String peer, String text) { submit(s -> s.sendMessage(peer, text)); }
+    public void sendChatMessage(String peer, String text, Consumer<String> result) {
+        submitMessage(s -> s.sendMessage(peer, text), result);
+    }
+    public void broadcastMessage(String id, String text, Consumer<String> result) {
+        submitMessage(s -> s.broadcastMessage(id, text), result);
+    }
+    private void submitMessage(SessionTask task, Consumer<String> result) {
+        long token = generation.get();
+        ClientSession current = session;
+        executor.execute(() -> {
+            String failure = null;
+            try {
+                if (current == null || current != session || generation.get() != token) throw new IOException("Session closed");
+                task.run(current);
+            } catch (IOException | RuntimeException e) {
+                failure = java.util.Objects.requireNonNullElse(e.getMessage(), "Send failed");
+            }
+            String error = failure;
+            JavaFxUtils.runOnFxThread(() -> { if (generation.get() == token) result.accept(error); });
+        });
+    }
     public void sendFile(String peer, File file) { submit(s -> { s.ensurePeer(peer); s.sendFile(peer, file); }); }
     public void broadcastMessage(String communityId, String text) { submit(s -> s.broadcastMessage(communityId, text)); }
     public void broadcastFile(String communityId, String communityName, File file) {
@@ -221,8 +256,11 @@ public final class ChatClientAdapter {
             public void onPeerConnected(String peer) { dispatch(token, l -> l.onPeerConnected(peer)); }
             public void onPeerDisconnected(String peer) { dispatch(token, l -> l.onPeerDisconnected(peer)); }
             public void onServerClosed() {
-                if (generation.get() != token) return;
-                connected = false;
+                synchronized (ChatClientAdapter.this) {
+                    if (generation.get() != token) return;
+                    connected = false;
+                    if (poller != null) { poller.cancel(false); poller = null; }
+                }
                 dispatch(token, ClientEventListener::onConnectionClosed);
             }
             public void onFileOffer(String id, FileTransferMeta meta, String communityId, boolean incoming) {
@@ -244,6 +282,9 @@ public final class ChatClientAdapter {
                         if (state == FileState.FAILED) listeners.forEach(l -> l.onSystemNotification(detail, true));
                         return;
                     }
+                    if (model.getStatus() == FileOfferModel.Status.COMPLETED
+                            || model.getStatus() == FileOfferModel.Status.FAILED
+                            || model.getStatus() == FileOfferModel.Status.REJECTED) return;
                     model.setStatus(switch (state) {
                         case TRANSFERRING -> FileOfferModel.Status.TRANSFERRING;
                         case REJECTED -> FileOfferModel.Status.REJECTED;
@@ -269,6 +310,6 @@ public final class ChatClientAdapter {
         onlineUsers = List.of();
         communities = List.of();
         fileOffers.clear();
-        if (closing != null) executor.execute(closing::close);
+        if (closing != null) Thread.startVirtualThread(closing::close);
     }
 }
```

</details>

<details>
<summary><strong>src/main/java/ui/controller/ChatController.java</strong></summary>

```diff
diff --git a/src/main/java/ui/controller/ChatController.java b/src/main/java/ui/controller/ChatController.java
index 225700e..f1c7083 100644
--- a/src/main/java/ui/controller/ChatController.java
+++ b/src/main/java/ui/controller/ChatController.java
@@ -334,7 +334,7 @@ public class ChatController implements ClientEventListener {
         messageScrollPane.setVisible(true);
         messageScrollPane.setManaged(true);
 
-        inputContainer.setDisable(!comm.isJoined());
+        inputContainer.setDisable(!comm.isJoined() || !adapter.isServerConnected());
         messageInput.setPromptText(comm.isJoined() ? "Broadcast to #" + comm.getName() + "..." : "Join community to participate...");
 
         loadConversation("comm-" + comm.getId());
@@ -372,7 +372,7 @@ public class ChatController implements ClientEventListener {
     }
 
     private void updateJoinLeaveButton(boolean isJoined) {
-        joinLeaveCommunityBtn.setDisable(false);
+        joinLeaveCommunityBtn.setDisable(!adapter.isServerConnected());
         if (isJoined) {
             joinLeaveCommunityBtn.setText("Leave");
             joinLeaveCommunityBtn.getStyleClass().clear();
@@ -417,21 +417,16 @@ public class ChatController implements ClientEventListener {
                 || (currentMode != Mode.DIRECT && activeCommunity == null)) return;
         messageInput.clear();
 
-        if (currentMode != Mode.DIRECT && activeCommunity != null) {
-            adapter.broadcastMessage(activeCommunity.getId(), text);
-            ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
-            String convKey = "comm-" + activeCommunity.getId();
-            conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);
-            messageView.render(msg);
-            scrollToBottom();
-        } else if (currentMode == Mode.DIRECT && activePeer != null) {
-            adapter.sendChatMessage(activePeer.getUsername(), text);
-            ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
-            String convKey = "peer-" + activePeer.getUsername();
-            conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);
-            messageView.render(msg);
-            scrollToBottom();
-        }
+        boolean direct = currentMode == Mode.DIRECT;
+        String target = direct ? activePeer.getUsername() : activeCommunity.getId();
+        ChatMessageModel msg = new ChatMessageModel(adapter.getCurrentUsername(), text, true);
+        msg.sending();
+        String convKey = (direct ? "peer-" : "comm-") + target;
+        conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);
+        messageView.render(msg);
+        if (direct) adapter.sendChatMessage(target, text, msg::sent);
+        else adapter.broadcastMessage(target, text, msg::sent);
+        scrollToBottom();
     }
 
     @FXML
@@ -446,7 +441,7 @@ public class ChatController implements ClientEventListener {
         File file = chooser.showOpenDialog(stage);
 
         if (file != null) {
-            if (file.length() > 500L * 1024 * 1024) {
+            if (file.length() > protocols.FileTransferMeta.MAX_FILE_SIZE) {
                 JavaFxUtils.showErrorAlert("File Too Large", "Maximum supported file size is 500 MiB.");
                 return;
             }
@@ -530,7 +525,7 @@ public class ChatController implements ClientEventListener {
         if (currentMode != Mode.DIRECT && activeCommunity != null && activeCommunity.getId().equals(communityId)) {
             activeCommunity.setJoined(true);
             updateJoinLeaveButton(true);
-            inputContainer.setDisable(false);
+            inputContainer.setDisable(!adapter.isServerConnected());
         }
         applyFilter(searchField.getText());
     }
@@ -557,11 +552,15 @@ public class ChatController implements ClientEventListener {
 
     @Override
     public void onBroadcastMessageReceived(String communityId, String sender, String message) {
+        if (communityId == null || communityId.isBlank()) {
+            onSystemNotification("Broadcast missing community ID", true);
+            return;
+        }
         ChatMessageModel msg = new ChatMessageModel(sender, message, sender.equals(adapter.getCurrentUsername()));
-        String convKey = "comm-" + (communityId != null ? communityId : (activeCommunity != null ? activeCommunity.getId() : "default"));
+        String convKey = "comm-" + communityId;
         conversations.computeIfAbsent(convKey, k -> FXCollections.observableArrayList()).add(msg);
 
-        if (currentMode != Mode.DIRECT && activeCommunity != null && (communityId == null || activeCommunity.getId().equals(communityId))) {
+        if (currentMode != Mode.DIRECT && activeCommunity != null && activeCommunity.getId().equals(communityId)) {
             messageView.render(msg);
             scrollToBottom();
         } else {
@@ -595,6 +594,13 @@ public class ChatController implements ClientEventListener {
                 updated.add(new UserModel(u, true));
             }
         }
+        for (UserModel existing : allUsers) {
+            if (adapter.isPeerConnected(existing.getUsername())
+                    && updated.stream().noneMatch(user -> user.getUsername().equals(existing.getUsername()))) {
+                existing.setOnline(true);
+                updated.add(existing);
+            }
+        }
         allUsers.setAll(updated);
         if (currentMode == Mode.DIRECT && activePeer != null) {
             for (UserModel m : allUsers) {
@@ -711,12 +717,14 @@ public class ChatController implements ClientEventListener {
 
     @Override
     public void onConnectionClosed() {
-        JavaFxUtils.showErrorAlert("Disconnected", "Server connection closed.");
+        connectionStatusLabel.setText("Server offline");
+        joinLeaveCommunityBtn.setDisable(true);
+        if (currentMode != Mode.DIRECT) inputContainer.setDisable(true);
     }
 
     @Override public void onSystemNotification(String message, boolean isError) {
         if (isError) {
-            joinLeaveCommunityBtn.setDisable(false);
+            joinLeaveCommunityBtn.setDisable(!adapter.isServerConnected());
             JavaFxUtils.showErrorAlert("Connection Error", message);
         }
     }
```

</details>

<details>
<summary><strong>src/main/java/ui/model/ChatMessageModel.java</strong></summary>

```diff
diff --git a/src/main/java/ui/model/ChatMessageModel.java b/src/main/java/ui/model/ChatMessageModel.java
index 94a96ce..f693aa5 100644
--- a/src/main/java/ui/model/ChatMessageModel.java
+++ b/src/main/java/ui/model/ChatMessageModel.java
@@ -1,6 +1,8 @@
 package ui.model;
 
 import java.time.LocalTime;
+import javafx.beans.property.SimpleStringProperty;
+import javafx.beans.property.StringProperty;
 import java.time.format.DateTimeFormatter;
 
 public class ChatMessageModel {
@@ -12,6 +14,11 @@ public class ChatMessageModel {
         SYSTEM
     }
 
+    private final StringProperty sendStatus = new SimpleStringProperty("");
+    public StringProperty sendStatusProperty() { return sendStatus; }
+    public void sending() { sendStatus.set("Sending..."); }
+    public void sent(String error) { sendStatus.set(error == null ? "Sent (unconfirmed)" : "Failed: " + error); }
+
     private final Type type;
     private final String sender;
     private final String content;
```

</details>

<details>
<summary><strong>src/main/java/ui/view/ChatMessageView.java</strong></summary>

```diff
diff --git a/src/main/java/ui/view/ChatMessageView.java b/src/main/java/ui/view/ChatMessageView.java
index d54771d..c4e58c3 100644
--- a/src/main/java/ui/view/ChatMessageView.java
+++ b/src/main/java/ui/view/ChatMessageView.java
@@ -58,7 +58,11 @@ public final class ChatMessageView {
             contentLbl.getStyleClass().add("bubble-sent-text");
             messageBubbleBox.setAlignment(Pos.TOP_RIGHT);
             timeLbl.setAlignment(Pos.CENTER_RIGHT);
-            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl);
+            Label status = new Label();
+            status.textProperty().bind(msg.sendStatusProperty());
+            status.getStyleClass().add("bubble-timestamp");
+            status.setWrapText(true);
+            messageBubbleBox.getChildren().addAll(contentLbl, timeLbl, status);
 
             HBox row = new HBox(messageBubbleBox);
             row.setAlignment(Pos.CENTER_RIGHT);
@@ -132,7 +136,8 @@ public final class ChatMessageView {
             rejectButton.visibleProperty().bind(offer.statusProperty().isEqualTo(FileOfferModel.Status.PENDING));
             acceptButton.visibleProperty().bind(rejectButton.visibleProperty());
             rejectButton.setOnAction(event -> {
-                offer.setStatus(FileOfferModel.Status.REJECTED);
+                offer.setStatus(FileOfferModel.Status.ACCEPTED);
+                offer.setStatusText("Rejecting...");
                 reject.accept(offer.getOfferNumber());
             });
             acceptButton.setOnAction(event -> {
```

</details>

## 8. Sửa script khởi chạy sau phản hồi sử dụng

- Tái hiện trực tiếp `./app.sh cli` báo **Permission denied**, exit 126: file chưa có executable bit. Đã sửa từ mode 100644 thành 100755.
- `run.sh` gọi `app.sh cli`, nhưng `app.sh` chạy `mvn package` nên bộ test mạng mới được chạy trước khi mở Main, mất khoảng 50 giây hoặc làm việc mở app dừng nếu test thất bại. Đã dùng `mvn -q -DskipTests package` trong launcher Bash và PowerShell; kiểm thử được chạy riêng.
- Đã chạy thực `./run.sh` và `./app.sh cli` với stdin EOF: cả hai exit 0, hiển thị `Run server(s) or client(c):`. Đây là smoke test vào Main, không phải kiểm thử một phiên chat đầy đủ.
- Thêm 6 test launcher bằng Python standard library: chạy từ thư mục khác có khoảng trắng, giữ argument, đúng entry point cho CLI/UI/server, bỏ qua test khi mở app, không chạy class cũ khi build thất bại. Cả 6 đạt. Test dùng Maven/JVM giả lập cho kiểm tra điều phối; GUI qua launcher và PowerShell chưa được mở thật trong lượt sửa script này.
- `./app.sh` mặc định mở JavaFX; để vào Main dùng `./run.sh` hoặc `./app.sh cli`.

```bash
python3 -m unittest discover -s tests -p test_launchers.py -v
```

<details>
<summary><strong>app.sh</strong></summary>

```diff
diff --git a/app.sh b/app.sh
old mode 100644
new mode 100755
index 523d2ff..1d3f242
--- a/app.sh
+++ b/app.sh
@@ -10,5 +10,5 @@ case "$mode" in
     ui) entry_point=ui.Launcher ;;
     *) printf 'Usage: %s [ui|server|cli]\n' "$0" >&2; exit 1 ;;
 esac
-mvn -q package
+mvn -q -DskipTests package
 exec java -cp "target/classes:target/deps/*" "$entry_point" "$@"
```

</details>

<details>
<summary><strong>app.ps1</strong></summary>

```diff
diff --git a/app.ps1 b/app.ps1
index c6ae84b..f30a8cd 100644
--- a/app.ps1
+++ b/app.ps1
@@ -5,7 +5,7 @@ param(
 $ErrorActionPreference = "Stop"
 Set-Location $PSScriptRoot
 
-mvn -q package
+mvn -q -DskipTests package
 if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
 $entryPoint = switch ($Mode) {
     { $_ -in "server", "s" } { "server.Server" }
```

</details>

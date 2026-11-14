# Kết quả kiểm chứng security

> Đây là báo cáo trước đợt refactor. Bản mới và kết quả 149 assertion nằm trong
> [security-refactor.md](security-refactor.md); hash JAR bên dưới thuộc bản cũ.

Ngày chạy: **15/11/2026**. Kết quả: **build thành công, 160 assertion đạt**,
thêm kiểm tra filesystem không còn file `.transfer-*` sau các ca gây lỗi.

## Môi trường và cách chạy

- OpenJDK 25.0.4.1; Maven biên dịch với `--release 21` theo `pom.xml`.
- Build: `mvn -o package -DskipTests`.
- Repository chưa có bộ JUnit; kiểm thử thực tế dùng ba harness Java tạm,
  biên dịch bằng `javac`, chạy trên JAR đã build. `-DskipTests` ở lệnh build
  không phải kết quả của các harness này.
- Integration chạy `Client`, `ClientSession`, `ClientHandler`, các controller
  thật trên TCP loopback, cổng ngẫu nhiên. Proxy chủ động sửa, lặp, cắt hoặc
  bỏ gói; không truy cập server bên ngoài.
- Khóa, cấu hình pin, storage, downloads và harness nằm riêng trong `/tmp`;
  được dọn sau khi ghi báo cáo theo yêu cầu không giữ file test tạm.
- Build có cảnh báo Maven/JavaFX về module và tài nguyên trùng khi shade JAR;
  không có lỗi biên dịch hoặc đóng gói.

SHA-256 của JAR được kiểm tra:

```text
f4e9b893226a8af6535cd021492e255b2a82f3c96b30d6ecf17a6252ff04e9a4
```

## Kết quả

| Bộ kiểm tra tạm | Assertion đạt |
| --- | ---: |
| SecurityAudit: crypto, server, P2P, file, broadcast, reconnect, heartbeat | 136 |
| EdgeAudit: concurrency, framing, sequence, timeout, lifecycle | 14 |
| PeerAudit: giả mạo peer và tấn công socket quản lý | 10 |
| **Tổng** | **160** |

### Crypto và chống giả mạo

- AES-GCM khớp vector chuẩn với key/nonce/plaintext toàn byte 0.
- HKDF-SHA256 khớp vector RFC 5869.
- Khóa Ed25519 server giữ nguyên khi lưu/nạp lại identity.
- Chứng thực peer hợp lệ được chấp nhận; thay username, session ID, public key,
  chữ ký bằng khóa không tin cậy hoặc Base64 sai đều bị từ chối.
- Trên TCP thật, chữ ký server giả, public key X25519 bị thay và HELLO_ACK của
  handshake cũ đều bị client từ chối trước AUTH.
- PeerListener từ chối chữ ký peer giả và không đăng ký peer đó.
- Có chứng thực public key hợp lệ nhưng không sở hữu private key tương ứng
  không vượt qua xác nhận khóa AES và không được đăng ký làm peer.
- Capture socket quản lý không chứa mật khẩu thử ở dạng rõ.

### Sửa gói và replay

- Sửa ciphertext/tag, replay record, đổi thứ tự record và replay sang session
  khác đều bị từ chối.
- Record thiếu prefix, thiếu payload hoặc khai báo độ dài quá lớn bị từ chối.
- GCM hợp lệ nhưng JSON sequence sai vẫn bị từ chối và đóng socket.
- Trên socket quản lý thật: tamper, replay và cắt giữa record làm request thất
  bại và chuyển client sang SERVER_OFFLINE.
- Trên socket file thật: tamper, replay và cắt record được thử ở cả giai đoạn
  xác nhận khóa lẫn dữ liệu file. Hai bên báo FAILED; chat P2P vẫn sử dụng được.

### File và bất đồng bộ

- So byte file gốc/đích với kích thước 0, 1, 65535, 65536, 65537 và 2097165 byte.
- File trùng tên được thêm hậu tố; lời mời REJECT kết thúc đúng ở hai bên.
- Tám lượt truyền song song hoàn tất với tám đường dẫn đích riêng.
- 800 lần ghi packet mã hóa từ tám writer giữ đúng sequence và counter nonce.
- Đọc raw với ranh giới buffer khác record vẫn khớp dữ liệu; packet FILE_END
  sau raw được giải mã đúng.
- File broadcast tải qua server khớp byte gốc.
- Sau hoàn tất, map khóa AES, socket và timer của các lượt truyền được dọn.
- Không còn file tạm `.transfer-*` trong downloads của bộ thử sau fault injection.

### Mất kết nối, timeout và reconnect

- Đứt kết nối server được phát hiện; chat và gửi file P2P còn kết nối vẫn chạy.
- Reconnect sai mật khẩu thất bại; reconnect đúng mật khẩu truy vấn được user mới.
- Proxy bỏ toàn bộ gói sau đăng nhập: client phát hiện mất mạng bằng heartbeat
  với thời gian cấu hình thật, không chỉ dựa vào EOF.
- Read bị treo hết timeout và đóng socket.
- Request quản lý đang chờ được giải phóng trong 2 giây khi socket bị đóng,
  không phải đợi đủ timeout request 10 giây.
- ACK heartbeat lặp bị từ chối. Timeout heartbeat ngắn cũng được thử độc lập.
- Offer hết hạn dọn registry, tham chiếu private key và timer. Ca này gọi cùng
  đường xử lý timeout với thời gian rút ngắn 100 ms; không chờ đủ 2 phút.

## Code sửa trong lượt kiểm chứng này

### `network/EncryptedRecords.java`

Khi payload bị cắt, `DataInputStream.readFully()` ném EOFException không có
message. Bổ sung nguyên nhân rõ ràng và giữ exception gốc:

```java
try {
    input.readFully(ciphertext);
} catch (EOFException e) {
    EOFException failure = new EOFException("Incomplete encrypted record payload");
    failure.initCause(e);
    throw failure;
}
```

### `client/transfer/FileTransferController.java`

Không phát sự kiện lỗi file với detail null, kể cả lỗi chuẩn bị offer chưa có session:

```java
if (session == null) events.onFileState("", ClientEvents.FileState.FAILED,
        Objects.requireNonNullElse(e.getMessage(), "Cannot prepare file transfer"));

// Trong reportResult(...):
if (session.markResultReported()) events.onFileState(session.transferId(), state,
        Objects.requireNonNullElse(detail, "File transfer failed"));
```

Ca cắt socket ban đầu làm bộ ghi kết quả thử lỗi vì detail null. Bộ thử đã được
sửa để nhận được detail null, và code ứng dụng cũng bổ sung thông báo dự phòng.
Sau đó build lại và chạy các bộ kiểm tra trên bản mới; kết quả ở trên là các lần
chạy cuối thành công, không cộng assertion từ những lần chẩn đoán trước đó.

## Giới hạn

Đây là kiểm chứng các đường xử lý cụ thể, không phải chứng nhận toàn bộ ứng dụng
an toàn. Chưa thử GUI trong lượt này, JDK 21 runtime, WAN/router thực, file
500 MiB, đầy ổ đĩa, crash tiến trình hay toàn bộ phân quyền/lưu mật khẩu.
Chưa có resume file theo offset; mất socket file phải gửi lại. UDP discovery
vẫn chưa xác thực/mã hóa; quy trình phân phối, thay và thu hồi key pin vẫn cần
được thiết kế riêng khi triển khai thực tế.

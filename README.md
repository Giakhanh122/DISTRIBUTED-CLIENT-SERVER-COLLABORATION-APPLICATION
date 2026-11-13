# DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION

Bài tập môn Computer Networking: ứng dụng client–server dùng Java TCP socket.

## Hiện trạng

- Handshake, đăng nhập và tra cứu người dùng qua server TCP.
- `UserSessionRegistry` quản lý user online; `ChatSessionRegistry` theo dõi
  các kết nối P2P bằng UUID. `/chats` xem các phiên và trạng thái trên server.
- Chat trực tiếp giữa hai peer qua TCP; server dùng virtual thread cho mỗi client.
- Truyền file qua socket TCP riêng: chunk 64 KiB, kiểm tra kích thước và SHA-256,
  chỉ báo thành công sau khi receiver xác nhận đã lưu file.
- Offer/decision đi trên kết nối chat P2P đã mở; socket dữ liệu dùng lại cổng
  `PeerListener`. Không cần server tham gia vào truyền file sau khi đã kết nối peer.
- UDP broadcast discovery; yêu cầu JDK 21 trở lên.

## Chạy chương trình

Yêu cầu JDK 21 trở lên và curl (nếu cần tải Gson lần đầu). Từ thư mục gốc repository, biên dịch và chạy bằng một lệnh:

```bash
./run.sh
```

Chọn `s` ở terminal thứ nhất để chạy server. Mở terminal khác, chạy lại script và
chọn `c` để chạy client. Khi không truyền địa chỉ, client dùng UDP discovery để tìm server.

Để chỉ định server và bỏ qua discovery:

```bash
./run.sh localhost
./run.sh 192.168.1.10
```

Thay IP ví dụ bằng IP thật của máy server. Sau khi chọn `c`, đăng nhập bằng tài khoản
trong `AuthService`. Dùng `/users` để xem danh sách online, `/lookup bob` để tìm và
kết nối peer Bob, `/logout` để thoát. Dùng Ctrl+C tại terminal server để dừng.
`/chats` hiển thị danh sách phiên P2P chung, gồm người tham gia, UUID và trạng thái.
Cả hai registry lưu RAM; mất kết nối server chuyển metadata phiên
chưa đóng sang `UNKNOWN`, không đóng đường P2P đang hoạt động.

Tài khoản demo: `alice/alice123`, `bob/bob123`, `admin1/password1`.
Sau `/lookup bob`, nhập nội dung để chat với Bob; Bob có thể trả lời trực tiếp.

Khi đã kết nối nhiều peer, dùng `/chat` để xem danh sách và người đang chọn;
`/chat bob` chuyển sang Bob trên kết nối có sẵn. `/lookup bob` cũng chọn lại Bob
nếu đã kết nối, không mở thêm socket hoặc cần server lookup lại. Tin nhắn thường
chỉ gửi tới peer đang chọn; gửi nhóm dùng `/broadcast -m`.
Nếu chọn/lookup một peer không khả dụng, lựa chọn hiện tại được bỏ để tin nhắn kế
tiếp không đi nhầm sang người trước đó. Các kết nối peer khác vẫn được giữ.

Để gửi file từ Alice sang Bob, kết nối chat bằng `/lookup bob` trước, rồi:

```text
/sendfile bob /đường/dẫn/tệp dữ liệu.bin
```

Bob nhận được số thứ tự ngắn, ví dụ `[FILE OFFER #1]`, và chọn một trong hai lệnh:

```text
/accept 1
/reject 1
```

Số thứ tự tăng dần trong mỗi phiên đăng nhập và không dùng lại khi offer kết thúc.
UUID vẫn được dùng trong giao thức để nhận diện lần truyền giữa hai máy.

File được lưu vào `downloads/<username>/` trong thư mục làm việc của receiver. File trùng tên
được thêm hậu tố `(1)`, `(2)`, …; giới hạn mỗi file là 500 MiB. Bob có thể gửi
ngược lại Alice trên cùng kết nối chat, không cần lookup lại. Có thể chat và dùng
`/users` trong lúc truyền (các lệnh quản lý vẫn cần server).
Hai peer cần kết nối trực tiếp được tới cổng TCP của nhau, chẳng hạn cùng LAN.
Offer được accept chờ socket dữ liệu tối đa 30 giây; socket dữ liệu có read timeout
30 giây. Logout hoặc đóng chat peer dọn các transfer liên quan. Mất kết nối server
không đóng chat/file P2P đã thiết lập.

Script lấy Gson từ Maven cache hoặc tải vào `.deps/` (có thể đặt `GSON_JAR` để dùng jar có sẵn), rồi biên dịch lại vào `out/` mỗi lần chạy, dừng nếu biên dịch lỗi và chuyển các
tham số cho `Main`. Script cũng chạy được khi được gọi bằng đường dẫn từ thư mục khác.
`out/`, `target/`, file `.class` và `downloads/` được Git bỏ qua.

Các script mở app dùng `-DskipTests` để không chạy bộ kiểm thử trước mỗi lần mở.
Các kết quả kiểm thử trước khi dọn được ghi trong báo cáo sửa lỗi.

| Lệnh | Chế độ |
| --- | --- |
| `./run.sh` hoặc `./app.sh cli` | Chạy `Main`, chọn server/client trong terminal |
| `./app.sh server` | Chạy server trực tiếp |
| `./app.sh` hoặc `./app.sh ui` hoặc `./run_ui.sh` | Mở JavaFX |

## Khởi động giao diện UI

1. Chạy server qua lệnh

```bash
./run.sh
```

Sau đó chọn tùy chọn "s" (server) 2. Khởi động giao diện đồ họa qua lệnh

```bash
./run_ui.sh
```

## Protocol hiện tại

Thông điệp điều khiển dùng `[len:4 byte big-endian][JSON UTF-8]`; len đếm toàn bộ
byte JSON. Envelope gồm `opcode`, `sequence`, `body`. Mỗi chiều TCP đếm sequence
riêng. ACK/response có `body.status` và `body.replyTo` trỏ tới sequence request.
Handshake trao đổi protocolVersion 1. Không có magic bytes hoặc header JSON riêng.

Socket file riêng gửi đúng fileSize byte nhị phân rồi FILE_END JSON; receiver
kiểm tra SHA-256 và trả FILE_RESULT. Không đóng gói từng buffer thành FILE_CHUNK.
Client/server phải chạy cùng phiên bản mới. Xem docs/protocol.md để biết chi tiết.

## Tổ chức client

`Client` giữ API khởi chạy, handshake và đăng nhập. Mỗi lần đăng nhập tạo một
`ClientSession` riêng để quản lý peer và dọn tài nguyên khi logout. `ClientConsole`
đọc lệnh và hiển thị kết quả; `ServerDiscovery` phụ trách tìm server UDP.
`ServerConnection` sở hữu reader server, ghép response bằng replyTo và xử lý timeout.
`CommunityClient` giữ community đang chọn; `BroadcastFileController` xử lý file
broadcast ở worker, theo dõi socket và hủy khi phiên đóng. File P2P dùng
`FileTransferController`; cả hai loại offer dùng chung bộ cấp số trong mỗi phiên.

## Kiểm tra build

```bash
mvn -DskipTests package
```

Bộ test tạm và dữ liệu sinh ra trong đợt sửa lỗi đã được dọn theo yêu cầu.
Hiện repository không giữ bộ test tự động; `mvn test` không chạy lại các bài test cũ.
Kết quả kiểm thử trước khi dọn được lưu trong [báo cáo sửa lỗi](docs/BAO_CAO_SUA_LOI.md).

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng frame JSON và binary,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.

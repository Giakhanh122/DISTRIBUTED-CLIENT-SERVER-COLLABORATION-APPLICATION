# Giao thức và kiến trúc hiện tại

Yêu cầu JDK 21+. Server mặc định dùng TCP 8080 và UDP discovery 41234.
Server giữ hai registry riêng: `UserSessionRegistry` cho user online và
`ChatSessionRegistry` cho metadata phiên P2P. Chat và dữ liệu file đi trực tiếp giữa peer.

## Packet nhị phân

```text
OP:u8 | RequestID:i32 | PayloadLength:i32 | Data:N bytes
```

Header dài 9 byte, số nhiều byte dùng big-endian. Payload tối đa 1 MiB.
`PacketCodec.read` đọc đủ payload; TCP có thể chia hoặc gộp các lần ghi tùy ý.
Mỗi connection có một luồng đọc. Sau login, client phân phối phản hồi quản lý
qua request ID. Reader của chat phân phối CHAT_MESSAGE, FILE_OFFER và FILE_DECISION.
`PeerConnection.sendPacket` khóa cho cả packet, dùng chung với `sendMessage` để
chat và file control không ghi xen byte. Socket file có reader/worker riêng.
Các packet quản lý từ console và worker peer dùng chung khóa ghi socket server;
request ID được cấp bằng `AtomicInteger`.

## Kết nối server và quản lý user

1. Client gửi `HELLO` với payload rỗng, nhận `HELLO_ACK` cùng request ID.
2. Client mở peer listener ở cổng động rồi gửi `AUTH_REQUEST`:
   `username\npassword\npeerPort` (UTF-8).
3. Server trả `AUTH_RESPONSE`: `OK`, `INVALID_AUTH_REQUEST`,
   `INVALID_CREDENTIALS` hoặc `ALREADY_LOGGED_IN`.
4. `/users` gửi `USER_LIST_REQUEST` rỗng; `USER_LIST_RESPONSE` chứa username
   sắp xếp, ngăn cách bằng newline. `/lookup <user>` gửi `PEER_LOOKUP_REQUEST`;
   phản hồi là `OK\nip\npeerPort` hoặc `USER_OFFLINE`.
5. `/logout` báo đóng các phiên P2P rồi gửi `LOGOUT_REQUEST` rỗng.
   Server xử lý các báo cáo trước đó, trả `LOGOUT_RESPONSE` rỗng cùng request ID
   rồi đóng kết nối, xóa user online. Client có thể login lại.

Peer port được giữ nguyên trong session để phục vụ lookup. Server không xử lý
hoặc chuyển tiếp opcode file.

## Peer chat

Client khởi tạo tạo UUID riêng cho mỗi kết nối, gửi `PEER_HELLO` chứa
`username\npeerPort\nsessionId` UTF-8. Peer nhận lưu cùng UUID và trả
`PEER_HELLO_ACK` rỗng, cùng request ID. Hai bên gửi `CHAT_MESSAGE` chứa nội dung
UTF-8, tối đa 64 KiB. `/lookup` chọn peer để gửi các dòng chat tiếp theo.
Initiator biết listener của receiver từ địa chỉ kết nối; receiver lấy listener
của initiator từ PEER_HELLO. Vì vậy cả hai chiều đều gửi file được mà không lookup lại.

## Theo dõi phiên chat trên server

`UserSession(username, ip, port)` chỉ biểu diễn một lần đăng nhập đang online.
`ChatSession(sessionId, userA, userB, state)` biểu diễn một kết nối P2P với UUID,
hai `UserSession` tham gia và trạng thái. Không lưu cờ xác nhận hoặc mốc thời gian.
Server tạo một instance mỗi registry và truyền chung cho tất cả `ClientHandler`.
Registry chat không giữ socket peer; các socket vẫn ở map `peers` của client.

Sau handshake, mỗi client gửi `CHAT_SESSION_REPORT` tới server:

```text
sessionId\npeerUsername\nCONNECTED
sessionId\npeerUsername\nDISCONNECTED
```

Request ID dương; UUID dùng dạng canonical. Server xác định người báo từ phiên
đăng nhập, không lấy username người báo từ payload. Peer phải online khi báo
CONNECTED; UUID đã tồn tại phải thuộc đúng hai lần đăng nhập ban đầu.
Phản hồi `CHAT_SESSION_REPORT_RESPONSE` cùng request ID là `OK`,
`INVALID_SESSION_REPORT`, `INVALID_SESSION` hoặc `USER_OFFLINE`.
Báo cáo chạy độc lập với đọc chat: lỗi báo cáo không đóng P2P.

- Báo CONNECTED đầu tiên sau handshake tạo phiên `CONNECTED`; báo cáo bên còn lại
  được gộp theo cùng UUID, không cần chờ xác nhận từ cả hai bên.
- Một bên báo DISCONNECTED chuyển thành `DISCONNECTED`.
  Báo CONNECTED tới muộn không mở lại phiên đã đóng.
- Khi mất kết nối client–server, các phiên chưa đóng của lần đăng nhập đó chuyển
  thành `UNKNOWN`: đường P2P có thể vẫn hoạt động. Không tự đóng socket P2P.
- Mỗi lần thay kết nối dùng UUID mới; báo đóng của kết nối cũ không thay trạng thái
  kết nối mới. Logout chủ động gửi báo đóng trước khi đóng socket server.

`/chats` gửi `CHAT_SESSION_LIST_REQUEST` rỗng. Mọi user đã đăng nhập có thể xem
danh sách chung. `CHAT_SESSION_LIST_RESPONSE` chứa các dòng, sắp xếp theo UUID:

```text
sessionId<TAB>userA<TAB>userB<TAB>state
```

Danh sách rỗng là payload rỗng. Các bản ghi phiên đã đóng được giữ trong tiến trình
server để báo cáo CONNECTED tới muộn không mở lại phiên cũ.
Hai registry chỉ lưu RAM. Chưa có heartbeat phiên P2P, thống kê byte,
tự kết nối lại, lưu database hoặc chuyển tiếp chat/file qua server.

## Truyền file

Mỗi transfer có UUID riêng và một socket TCP dữ liệu riêng, nên chunk index không
cần chia sẻ không gian request ID với chat hoặc management.

### Offer và decision qua chat P2P

`FILE_OFFER` chứa metadata nhị phân theo thứ tự:

```text
transferId:string | sender:string | receiver:string | fileName:string | fileSize:i64 | checksum:string
string := byteLength:i32 | UTF-8 bytes
```

UUID dùng dạng canonical, kích thước file từ 0 tới 100 MiB. Checksum là SHA-256 hex
64 ký tự thường, được tính bằng cách đọc file theo buffer trước khi gửi offer.
Việc hash chạy ở worker để không chặn console/chat. Receiver kiểm tra sender khớp
với peer chat, receiver khớp user hiện tại, rồi gắn session với chính connection đó.
CLI gán số thứ tự cục bộ tăng dần cho mỗi incoming offer, ví dụ `[FILE OFFER #1]`.
Người nhận nhập `/accept 1` hoặc `/reject 1`; số này được ánh xạ về UUID trước khi
gửi packet. Số không dùng lại trong phiên đăng nhập và được dọn khi transfer kết thúc.

`FILE_DECISION` gửi lại qua cùng chat connection:

```text
transferId\nACCEPT
transferId\nREJECT
```

Không mở listener mới cho mỗi file. Sender dùng địa chỉ listener đã biết từ chat.
Khi chưa kết nối chat với đích, `/sendfile` yêu cầu `/lookup` trước. Một quyết định
chỉ được áp dụng cho outgoing offer đang OFFERED và thuộc đúng chat connection.

### Dữ liệu qua TCP trực tiếp

1. Sender kết nối cổng PeerListener hiện có của receiver, gửi `FILE_OFFER` với
   request ID 0 và metadata giống offer đã chấp nhận. Listener đọc packet đầu:
   PEER_HELLO đi vào chat handshake; FILE_OFFER đi vào handler socket file.
   Mỗi socket được xử lý bởi một virtual thread riêng.
   Receiver kiểm tra UUID, toàn bộ metadata, IP và chat connection đã gắn với session.
   Session phải ở ACCEPTED và được claim nguyên tử sang TRANSFERRING; unknown,
   unaccepted, mismatched hoặc replayed connection bị từ chối trước khi mở file tạm.
2. Sender đọc file tuần tự theo chunk tối đa 64 KiB, gửi `FILE_CHUNK` có request ID
   là chunk index bắt đầu từ 0. Payload là các byte gốc, không đổi sang chuỗi.
3. Receiver kiểm tra index, kích thước chunk và tổng byte; ghi vào file tạm được
   tạo bằng `Files.createTempFile` trong thư mục download. Cả hai tính SHA-256.
4. Sender gửi `FILE_END`: request ID là số chunk, payload là SHA-256 dạng hex.
5. Receiver kiểm tra số chunk, số byte và hash với cả offer ban đầu lẫn FILE_END,
   dành tên đích riêng để tránh ghi đè
   khi nhiều file trùng tên đến đồng thời, rồi chuyển file tạm thành file đích.
6. Receiver gửi `FILE_RESULT` với request ID 0: `OK` hoặc `FAIL: <reason>`.
   Sender phải đọc được `OK` mới chuyển session thành COMPLETED và in completed.

Khi nhận thiếu byte, checksum sai, sai thứ tự hoặc lỗi lưu file, receiver đánh dấu
FAILED, dọn file tạm và cố gửi FAIL. EOF hoặc thiếu FILE_RESULT không được coi là
thành công ở sender. Tên file được loại bỏ thành phần đường dẫn; transfer ID không
được dùng để tạo đường dẫn file tạm.

Kết nối có connect timeout 10 giây, read timeout 30 giây. Accepted offer chưa có
socket hết hạn sau 30 giây. Logout hoặc đóng chat peer đóng các socket transfer
liên quan và dọn registry. Mất kết nối server không đóng P2P đã thiết lập. Trạng thái
session được đồng bộ giữa console và các worker. File đã tồn tại được giữ nguyên;
file mới nhận trùng tên được thêm `(1)`, `(2)`, … trước phần mở rộng.

Luồng trạng thái:

```text
OFFERED -> ACCEPTED -> TRANSFERRING -> COMPLETED
       -> REJECTED
Bất kỳ trạng thái chưa kết thúc -> FAILED hoặc CANCELED
```

FILE_CANCEL đã bỏ. Gián đoạn được xử lý bằng đóng socket và dọn file tạm; không có
ACK từng chunk hoặc cơ chế retransmit riêng. Resume chưa được triển khai; gửi lại
bằng offer mới. Header packet không đổi; metadata và PEER_HELLO có thêm trường,
nên các peer cần chạy cùng phiên bản.

## UDP discovery

Client broadcast `DISCOVER_REQUEST` rỗng tới `255.255.255.255:41234`, tối đa 5 lượt,
chờ 3 giây mỗi lượt. Server gửi `DISCOVER_RESPONSE` cùng request ID, payload là
cổng TCP ở dạng số UTF-8. Client lấy địa chỉ IP từ datagram phản hồi.
Truyền host vào `run.sh` bỏ qua discovery.

## Opcode

| Nhóm | Opcode | Hex |
| --- | --- | --- |
| Kết nối | HELLO / HELLO_ACK | 0x01 / 0x02 |
| Quản lý | AUTH_REQUEST / AUTH_RESPONSE | 0x10 / 0x11 |
| Quản lý | USER_LIST_REQUEST / USER_LIST_RESPONSE | 0x12 / 0x13 |
| Quản lý | PEER_LOOKUP_REQUEST / PEER_LOOKUP_RESPONSE | 0x14 / 0x15 |
| Quản lý | LOGOUT_REQUEST / LOGOUT_RESPONSE | 0x16 / 0x17 |
| Peer | PEER_HELLO / PEER_HELLO_ACK | 0x20 / 0x21 |
| Chat | CHAT_MESSAGE | 0x30 |
| File | FILE_OFFER / FILE_DECISION | 0x40 / 0x41 |
| File | FILE_CHUNK / FILE_END | 0x42 / 0x43 |
| File | FILE_RESULT | 0x44 |
| Discovery | DISCOVER_REQUEST / DISCOVER_RESPONSE | 0x50 / 0x51 |
| Theo dõi phiên | CHAT_SESSION_REPORT / CHAT_SESSION_REPORT_RESPONSE | 0x60 / 0x61 |
| Theo dõi phiên | CHAT_SESSION_LIST_REQUEST / CHAT_SESSION_LIST_RESPONSE | 0x62 / 0x63 |

Các opcode PRESENCE_UPDATE, CHAT_RECEIPT, TYPING, HEARTBEAT/ACK và ERROR
vẫn là phần dự kiến.

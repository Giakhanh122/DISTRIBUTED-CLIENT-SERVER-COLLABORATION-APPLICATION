# DLP Protocol Specification

Đặc tả thông điệp JSON và luồng dữ liệu file của ứng dụng. Server mặc định dùng
TCP 8080, UDP discovery 41234. Phiên bản protocol hiện tại: 1.

## 1. Cấu trúc thông điệp

```text
┌────────────────────────────┬─────────────────────────────────────────┐
│ len                        │ JSON UTF-8                              │
│ 4 byte, integer big-endian │ đúng len byte                           │
└────────────────────────────┴─────────────────────────────────────────┘
```

`len` là số byte của **toàn bộ JSON sau khi mã hóa UTF-8**, không bao gồm chính
4 byte tiền tố. Tổng kích thước thông điệp là `4 + len` byte.

```java
byte[] bytes = Json.bytes(envelope);
int len = bytes.length;
```

Không có magic bytes, header JSON riêng, headerLength, contentType hoặc bodyLength
trên đường truyền. Các thông điệp điều khiển đều dùng cùng một JSON envelope:

```json
{"opcode":48,"sequence":3,"body":{"content":"Hello"}}
```

## 2. Metadata và body

| Trường | Kiểu JSON | Bắt buộc | Ý nghĩa |
| --- | --- | --- | --- |
| opcode | Integer | Có | Mã thao tác, biểu diễn bằng số thập phân |
| sequence | Integer | Có | Số thứ tự thông điệp trong một chiều kết nối TCP |
| body | Object | Có | Dữ liệu riêng của thao tác; dùng `{}` nếu không có trường |

Envelope có đúng ba trường. Thứ tự key không có ý nghĩa. Metadata file như tên,
kích thước và checksum thuộc body FILE_OFFER, không thuộc metadata chung.

### 2.1. sequence

- Mỗi chiều TCP đếm riêng, bắt đầu từ 1, tăng đúng 1 theo mỗi thông điệp JSON.
- Tất cả opcode cùng chiều dùng chung bộ đếm, bao gồm sự kiện chủ động.
- Mỗi socket mới bắt đầu bộ đếm mới. Response không sao chép sequence request.
- Luồng byte file không có sequence riêng và không làm tăng bộ đếm JSON.
- Sequence là integer dương tới 2^63−1; hết miền số thì mở kết nối mới.
- Sequence sai, lặp hoặc bỏ bước là lỗi protocol.

### 2.2. status và replyTo trong body phản hồi

Mọi ACK/RESPONSE đã triển khai và FILE_RESULT có các trường chung trong body:

| Trường | Kiểu | Ý nghĩa |
| --- | --- | --- |
| status | String không rỗng | OK khi thành công hoặc mã kết quả/lỗi cụ thể |
| replyTo | Integer | Sequence của request mà thông điệp này trả lời, trên cùng kết nối |

FILE_DECISION cũng có replyTo trỏ tới FILE_OFFER; lựa chọn thể hiện bằng decision
ACCEPT/REJECT. Request, chat và sự kiện chủ động không cần replyTo/status.

Ví dụ request có sequence 5:

```json
{"opcode":18,"sequence":5,"body":{}}
```

Response có sequence riêng và trả lời request 5:

```json
{"opcode":19,"sequence":8,"body":{"replyTo":5,"status":"OK","users":["alice","bob"]}}
```

Client kiểm tra opcode và replyTo trước khi sử dụng kết quả. Giá trị replyTo thông
thường phải dương. Sự kiện broadcast không được coi là response của request đang chờ.
Danh sách rỗng vẫn có status OK và mảng `[]`.

Trong luồng file, FILE_RESULT OK trả lời FILE_END. Nếu lỗi xảy ra trước khi nhận
FILE_END, FILE_RESULT FAIL trỏ tới thông điệp JSON gần nhất đã nhận trên socket đó.
Riêng receiver download chưa nhận được JSON nào từ sender có thể dùng replyTo 0
cho lỗi sớm. Đây chỉ là thông báo thất bại, không được coi là xác nhận hoàn tất file.

## 3. Body theo opcode

Ký hiệu: C = client, S = server, P = peer. Mã trong bảng dùng hex để đối chiếu
OpCode.java; JSON gửi số thập phân. Các trường bên dưới nằm trực tiếp trong body.
Những trường ghi "khi OK" chỉ bắt buộc khi status là OK.

### 3.1. Kết nối, đăng nhập và chat

| Opcode | Mã | Chiều | Các trường body |
| --- | --- | --- | --- |
| HELLO | 0x01 | C → S | protocolVersion:integer |
| HELLO_ACK | 0x02 | S → C | replyTo:integer, status:string; khi OK có protocolVersion:integer |
| AUTH_REQUEST | 0x10 | C → S | username:string, password:string, peerPort:integer |
| AUTH_RESPONSE | 0x11 | S → C | replyTo:integer, status:string |
| USER_LIST_REQUEST | 0x12 | C → S | `{}` |
| USER_LIST_RESPONSE | 0x13 | S → C | replyTo:integer, status:string; khi OK có users:string[] |
| PEER_LOOKUP_REQUEST | 0x14 | C → S | username:string của người cần tìm |
| PEER_LOOKUP_RESPONSE | 0x15 | S → C | replyTo:integer, status:string; khi OK có ip:string, peerPort:integer |
| LOGOUT_REQUEST | 0x16 | C → S | `{}` |
| LOGOUT_RESPONSE | 0x17 | S → C | replyTo:integer, status:string |
| PEER_HELLO | 0x20 | P → P | protocolVersion:integer, username:string, peerPort:integer, sessionId:string |
| PEER_HELLO_ACK | 0x21 | P → P | replyTo:integer, status:string; khi OK có protocolVersion:integer |
| CHAT_MESSAGE | 0x30 | P → P | content:string |

protocolVersion hiện là 1. Version không khớp trả status UNSUPPORTED_PROTOCOL_VERSION.
Port từ 1 tới 65535; UUID là chuỗi canonical. Nội dung chat tối đa 64 KiB theo
byte UTF-8; body đăng nhập tối đa 4096 byte. Danh sách user được sắp xếp.
AUTH_RESPONSE có status OK, INVALID_AUTH_REQUEST, INVALID_CREDENTIALS hoặc
ALREADY_LOGGED_IN. Lookup trả OK, USER_OFFLINE hoặc ALREADY_CONNECTED.

### 3.2. Community và chat broadcast

| Opcode | Mã | Chiều | Các trường body |
| --- | --- | --- | --- |
| COMMUNITY_LIST_REQUEST | 0x70 | C → S | `{}` |
| COMMUNITY_LIST_RESPONSE | 0x71 | S → C | replyTo, status; khi OK có communities:Community[] |
| COMMUNITY_CREATE_REQUEST | 0x72 | C → S | name:string |
| COMMUNITY_CREATE_RESPONSE | 0x73 | S → C | replyTo, status; khi OK có communityId:string, name:string |
| COMMUNITY_JOIN_REQUEST | 0x74 | C → S | communityId:string |
| COMMUNITY_JOIN_RESPONSE | 0x75 | S → C | replyTo, status; khi OK có communityId:string, name:string |
| COMMUNITY_LEAVE_REQUEST | 0x76 | C → S | communityId:string |
| COMMUNITY_LEAVE_RESPONSE | 0x77 | S → C | replyTo, status |
| COMMUNITY_MEMBERS_REQUEST | 0x78 | C → S | communityId:string |
| COMMUNITY_MEMBERS_RESPONSE | 0x79 | S → C | replyTo, status; khi OK có members:Member[] |
| COMMUNITY_SELECT_REQUEST | 0x7A | C → S | communityId:string |
| COMMUNITY_SELECT_RESPONSE | 0x7B | S → C | replyTo, status; khi OK có communityId:string, name:string |
| COMMUNITY_INFO_REQUEST | 0x7C | C → S | communityId:string |
| COMMUNITY_INFO_RESPONSE | 0x7D | S → C | replyTo, status; khi OK có community:Community |
| CHAT_MESSAGE_BROADCAST | 0x31 | C → S | communityId:string, content:string |
| CHAT_MESSAGE_BROADCAST | 0x31 | S → C | communityId:string, sender:string, content:string |

Các trường replyTo/status trong bảng có kiểu integer/string như mục 2.2.
Community gồm communityId:string, name:string, owner:string, memberCount:integer,
isMember:boolean, createdAt:string ISO 8601. Member gồm username:string,
online:boolean. Tên community không rỗng, tối đa 100 ký tự.

Status lỗi gồm NOT_FOUND, NOT_MEMBER, ALREADY_MEMBER, OWNER_CANNOT_LEAVE,
INVALID_NAME hoặc NAME_TOO_LONG. Chat broadcast không có response riêng.

### 3.3. File P2P

| Opcode | Mã | Các trường body |
| --- | --- | --- |
| FILE_OFFER | 0x40 | transferId:string, sender:string, receiver:string, fileName:string, fileSize:integer, checksum:string |
| FILE_DECISION | 0x41 | replyTo:integer, transferId:string, decision:string ACCEPT/REJECT |
| FILE_END | 0x43 | transferId:string, checksum:string |
| FILE_RESULT | 0x44 | replyTo:integer, status:string OK/FAIL; FAIL có reason:string |

fileSize là số byte file gốc, từ 0 tới 100 MiB. checksum là SHA-256 hex thường
64 ký tự. Mã FILE_CHUNK 0x42 được giữ chỗ, không còn dùng để truyền dữ liệu.

### 3.4. File broadcast qua server

| Opcode | Mã | Các trường body |
| --- | --- | --- |
| FILE_BROADCAST_UPLOAD_REQUEST | 0x45 | communityId:string, fileName:string, fileSize:integer, checksum:string |
| FILE_BROADCAST_UPLOAD_RESPONSE | 0x46 | replyTo, status; khi OK có fileId:string, uploadToken:string |
| FILE_BROADCAST_OFFER | 0x47 | fileId:string, sender:string, communityId:string, fileName:string, fileSize:integer, checksum:string |
| FILE_BROADCAST_DECISION | 0x48 | fileId:string, decision:string ACCEPT/REJECT |
| FILE_BROADCAST_DOWNLOAD_REQUEST | 0x49 | `{}`; đường thay thế, hiện trả USE_DATA_CONNECTION |
| FILE_BROADCAST_DOWNLOAD_RESPONSE | 0x4A | replyTo, status; ACCEPT thành công có downloadToken:string |
| FILE_BROADCAST_UPLOAD_START | 0x4B | fileId:string, uploadToken:string |
| FILE_BROADCAST_DOWNLOAD_START | 0x4C | fileId:string, downloadToken:string |

UPLOAD/DOWNLOAD_START là JSON đầu tiên trên socket dữ liệu riêng. FILE_END dùng
transferId bằng fileId. FILE_BROADCAST_OFFER là sự kiện, không mang replyTo.
FILE_BROADCAST_DECISION là request mới; response trỏ tới sequence của decision.

### 3.5. Theo dõi phiên và discovery

| Opcode | Mã | Các trường body |
| --- | --- | --- |
| CHAT_SESSION_REPORT | 0x60 | sessionId:string, peerUsername:string, state:string CONNECTED/DISCONNECTED |
| CHAT_SESSION_REPORT_RESPONSE | 0x61 | replyTo:integer, status:string |
| CHAT_SESSION_LIST_REQUEST | 0x62 | `{}` |
| CHAT_SESSION_LIST_RESPONSE | 0x63 | replyTo, status; khi OK có sessions:[{sessionId,userA,userB,state}] |
| DISCOVER_REQUEST | 0x50 | `{}` |
| DISCOVER_RESPONSE | 0x51 | replyTo:integer, status:string; khi OK có tcpPort:integer |

Các trường sessionId/userA/userB/state của mỗi session là string. Danh sách
sắp xếp theo UUID. Status báo cáo là OK, INVALID_SESSION_REPORT, INVALID_SESSION
hoặc USER_OFFLINE.

## 4. Ví dụ hoàn chỉnh

### 4.1. Handshake

JSON request:

```json
{"opcode":1,"sequence":1,"body":{"protocolVersion":1}}
```

JSON response:

```json
{"opcode":2,"sequence":1,"body":{"replyTo":1,"status":"OK","protocolVersion":1}}
```

Mỗi JSON được gửi với tiền tố len riêng, tính từ đúng chuỗi UTF-8 đã serialize.

### 4.2. Chat

```json
{"opcode":48,"sequence":3,"body":{"content":"Chào bạn"}}
```

### 4.3. Danh sách user rỗng

```json
{"opcode":19,"sequence":8,"body":{"replyTo":5,"status":"OK","users":[]}}
```

### 4.4. Lỗi đăng nhập

```json
{"opcode":17,"sequence":2,"body":{"replyTo":2,"status":"INVALID_CREDENTIALS"}}
```

## 5. Quy tắc trao đổi

### 5.1. Đọc/ghi thông điệp

Bên gửi serialize JSON, ghi len rồi ghi JSON. Bên nhận đọc đủ 4 byte, kiểm tra
len, rồi đọc đúng len byte trước khi parse. TCP có thể chia/gộp các lần ghi; một
lần read không tương ứng một thông điệp. EOF trước thông điệp kế là đóng bình
thường; EOF giữa tiền tố hoặc JSON là lỗi.

len phải dương và không vượt 1 MiB. JSON phải là UTF-8 hợp lệ, không trùng key,
không lồng quá 32 mức và có đúng kiểu trường. Độ dài được kiểm tra trước khi
cấp phát buffer. Các kiểm tra này phục vụ việc đọc đúng protocol.

### 5.2. Request/response

Client hiện xếp hàng request quản lý và chờ một response tại một thời điểm.
Trước khi gửi, client đăng ký opcode và sequence của request để response nhanh
không đến trước bước đăng ký. Response phải khớp cả opcode mong đợi và replyTo.
Broadcast chat/file vẫn được xử lý độc lập. Timeout request là 10 giây.

Handshake quản lý: HELLO → HELLO_ACK → AUTH_REQUEST → AUTH_RESPONSE.
Handshake peer: PEER_HELLO → PEER_HELLO_ACK. Mỗi ACK thành công có status OK,
replyTo đúng và protocolVersion 1. Mỗi lần kết nối chat có sessionId riêng.

Chọn người nhận chat là thao tác cục bộ: `/chat` hiển thị peer đang chọn và các
kết nối hiện có; `/chat <username>` chọn một kết nối đang mở. `/lookup <username>`
ưu tiên chọn kết nối cục bộ nếu đã có, chỉ hỏi server khi chưa kết nối. CHAT_MESSAGE
được ghi đúng một socket của peer đang chọn, không phát tới mọi peer. Chọn/lookup
thất bại bỏ lựa chọn để tránh gửi nhầm sang peer trước. Thao tác chọn peer không
phát sinh opcode hoặc session mới.

### 5.3. Dữ liệu file nhị phân

Sau ACCEPT, sender mở socket dữ liệu riêng. Đối với P2P, JSON đầu tiên là
FILE_OFFER lặp metadata đã chấp nhận. Đối với broadcast, JSON đầu là START.
Receiver xác định transfer và fileSize từ metadata trước khi đọc file.

```text
Sender → Receiver:
[len][JSON xác định transfer, sequence=1]
[đúng fileSize byte file gốc]
[len][JSON FILE_END, sequence=2]

Receiver → Sender:
[len][JSON FILE_RESULT, sequence=1, body.replyTo=2]
```

Trên broadcast download, START đi chiều client → server với sequence 1;
server gửi raw file rồi FILE_END sequence 1 theo chiều server → client;
client trả FILE_RESULT sequence 2, replyTo 1.

Receiver đọc đúng fileSize byte bằng buffer tối đa 64 KiB, sau đó quay lại đọc
JSON FILE_END. Byte file không có len, sequence hoặc header trên từng buffer;
không đổi sang string, JSON array hay Base64. Không dùng chunkCount vì ranh giới
các lần đọc TCP không tương ứng ranh giới buffer bên gửi. File rỗng bỏ qua pha
raw và đọc FILE_END ngay.

Receiver ghi file tạm, kiểm tra transferId và SHA-256 với offer/FILE_END rồi lưu
file đích. Sender chỉ báo hoàn tất khi FILE_RESULT có status OK và replyTo khớp
sequence FILE_END. EOF hoặc thiếu xác nhận không phải thành công. Lỗi sớm có thể
trả FILE_RESULT FAIL theo quy tắc replyTo ở mục 2.2.

P2P có connect timeout 10 giây, read timeout 30 giây; accepted offer chưa có
socket hết hạn sau 30 giây. Socket dữ liệu broadcast có read timeout 60 giây.
File nhận trùng tên được thêm hậu tố; file tạm được dọn khi thất bại.

### 5.4. Phiên và discovery

UserSessionRegistry lưu user online, ChatSessionRegistry lưu metadata phiên P2P.
Hai báo cáo CONNECTED cùng UUID được gộp; báo muộn không mở lại phiên đã đóng.
Logout báo DISCONNECTED và đóng peer/transfer. Mất server chuyển metadata thành
UNKNOWN nhưng không tự đóng các kết nối P2P đã thiết lập. Registry chỉ lưu RAM.

Discovery dùng một thông điệp `[len][JSON]` trên mỗi datagram UDP. Request và
response đều có sequence 1 vì mỗi datagram được xử lý độc lập; response replyTo 1.
Datagram thừa byte bị bỏ. Client broadcast tới 255.255.255.255:41234 tối đa 5 lần,
chờ 3 giây mỗi lần; lấy IP từ datagram và TCP port từ body. Truyền host cho run.sh
để bỏ qua discovery.

## 6. Triển khai và tương thích

PacketCodec chỉ encode/decode `[len][JSON]`. PacketChannel sở hữu socket, bộ đếm
sequence và khóa đọc/ghi; nó cũng cung cấp đọc/ghi raw cho socket file riêng.
ServerConnection phía client sở hữu reader server và quản lý request/response,
replyTo. ClientSession giữ tài nguyên từng lần đăng nhập; console, discovery,
community và file broadcast nằm trong các lớp riêng. Việc refactor này không đổi
schema, opcode hoặc nội dung packet. FileDataReader
đọc đúng fileSize byte rồi kiểm tra FILE_END, dùng chung cho P2P và broadcast.

Không tương thích phiên bản headerLength/header JSON/body cũ; cần cập nhật và
khởi động lại cả server lẫn client. File đã lưu không cần chuyển đổi.

PRESENCE_UPDATE, CHAT_RECEIPT, TYPING, HEARTBEAT/ACK và ERROR vẫn là các opcode
dự kiến. FILE_CHUNK không còn được dùng. UI và bộ test cũ chưa được chuyển đổi
trong đợt này; chưa chạy kiểm thử runtime cho schema envelope/replyTo mới.

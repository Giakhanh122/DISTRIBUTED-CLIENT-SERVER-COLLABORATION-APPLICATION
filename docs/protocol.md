# DLP Gateway & Events

Giao thức thời gian thực của ứng dụng: đăng nhập và thao tác community qua server,
chat và truyền file trực tiếp giữa các peer. Phiên bản hiện tại: **2**.

Tài liệu này mô tả các event ứng dụng gửi/nhận, cấu trúc payload và ví dụ JSON.
Các ví dụ là payload trước khi mã hóa hoặc sau khi giải mã.

## Example Gateway Event

Alice gửi một tin nhắn cho Bob trên kết nối P2P đã xác thực:

```json
{
  "opcode": 4,
  "sequence": 3,
  "body": {
    "type": "MESSAGE",
    "content": "Chào Bob!"
  }
}
```

`opcode = 4` chọn nhóm CHAT; `body.type = MESSAGE` xác định sự kiện gửi tin nhắn.
Bob nhận cùng payload trên socket của Alice và lấy danh tính người gửi từ kết nối.

## Contents

- [Payload Structure](#payload-structure)
- [Connection Lifecycle](#connection-lifecycle)
- [Send Events](#send-events)
- [Receive Events](#receive-events)
- [Event Reference](#event-reference)
- [Objects](#objects)
- [Errors and Limits](#errors-and-limits)
- [Reserved Events](#reserved-events)

## Payload Structure

Mọi event có cùng envelope gồm đúng ba trường:

| Field | Type | Description |
| --- | --- | --- |
| `opcode` | integer | Nhóm chức năng, từ 0 đến 7. |
| `sequence` | integer | Thứ tự event theo chiều gửi trên kết nối hiện tại. |
| `body` | object | Luôn có `type`; các trường còn lại phụ thuộc event. |

Trường duy nhất bắt buộc trong body của mọi event:

| Field | Type | Description |
| --- | --- | --- |
| `type` | string | Tên thao tác trong nhóm opcode, viết HOA và nối bằng `_`. |

`replyTo` và `status` thuộc schema của từng event: response/ACK có cả hai;
FILE/DECISION có `replyTo` và `decision`, không có `status`. Xem Structure của
từng event bên dưới.

Mỗi chiều TCP có sequence riêng, bắt đầu từ 1 và tăng 1 theo từng event, kể cả
heartbeat. Response có sequence riêng; `replyTo` mới là liên kết tới request.
Dữ liệu file nhị phân không làm tăng sequence event. Mỗi socket mới bắt đầu lại
bộ đếm. Discovery trên UDP dùng sequence 1 cho từng request/response độc lập.

Khi chờ response, kiểm tra đủ **opcode + type + replyTo**. Các tên như LIST_REQUEST
xuất hiện ở cả USER và COMMUNITY; không định tuyến bằng type đơn lẻ dạng string.
Type thiếu, không được định nghĩa hoặc không thuộc opcode tương ứng bị từ chối.

### Opcode Groups

| Opcode | Group | Purpose |
| --- | --- | --- |
| 0 | SYSTEM | Xác nhận khóa và heartbeat. |
| 1 | CONNECTION | Handshake với server hoặc peer. |
| 2 | AUTH | Đăng nhập và đăng xuất. |
| 3 | USER | Danh sách online, địa chỉ peer và proof public key. |
| 4 | CHAT | Tin nhắn trực tiếp, broadcast và metadata phiên P2P. |
| 5 | FILE | Offer, quyết định và điều khiển truyền file. |
| 6 | COMMUNITY | Tạo, tham gia, chọn và xem community. |
| 7 | DISCOVERY | Tìm server qua UDP trên LAN. |

## Connection Lifecycle

### Client–Server

1. Mở TCP tới server, mặc định port 8080; có thể dùng Discovery để tìm địa chỉ.
2. Client gửi CONNECTION/HELLO, server trả CONNECTION/HELLO_ACK.
3. Client kiểm tra chữ ký Ed25519 bằng public key server đã pin. Hai bên dùng
   X25519 và HKDF để tạo hai khóa chiều, bật AES-GCM.
4. Client gửi SYSTEM/KEY_CONFIRM, server trả SYSTEM/KEY_CONFIRM_ACK dưới mã hóa.
5. Client gửi AUTH/LOGIN_REQUEST; LOGIN_RESPONSE với status OK mở phiên quản lý.
6. Hai bên duy trì SYSTEM/PING/PONG. Client gửi request quản lý, đồng thời nhận
   response và các event chủ động như CHAT/BROADCAST hoặc FILE/BROADCAST_OFFER.

Hello/Hello ACK chưa mã hóa. Key Confirm và mọi event tiếp theo đều đi trong
AES-GCM. Kết nối chỉ xử lý đăng nhập sau khi xác nhận khóa thành công.

### P2P Chat

1. Alice hỏi USER/PEER_LOOKUP_REQUEST để tìm Bob.
2. Alice tạo cặp khóa X25519, dùng USER/PEER_KEY_REQUEST xin proof từ server.
3. Alice mở TCP tới Bob và gửi CONNECTION/PEER_HELLO kèm proof.
4. Bob kiểm tra proof Alice, xin proof cho khóa của mình với cùng sessionId,
   rồi trả CONNECTION/PEER_HELLO_ACK.
5. Alice kiểm tra proof Bob. Hai bên dẫn xuất khóa và trao đổi SYSTEM/KEY_CONFIRM/ACK.
6. Công bố kết nối P2P, gửi CHAT/MESSAGE hoặc FILE/OFFER trực tiếp.

Hai peer đều tin public key Ed25519 server đã pin. Server ký xác nhận danh tính
và public key; nội dung chat P2P không đi qua server.

### P2P File

1. Sender gửi FILE/OFFER trên kênh chat đã mã hóa.
2. Receiver trả FILE/DECISION với ACCEPT và public key file, hoặc REJECT.
3. Sau ACCEPT, hai bên dẫn xuất khóa transfer. Sender mở TCP riêng và gửi
   FILE/TRANSFER_START; receiver trả FILE/READY kèm connectionNonce mới.
4. Hai bên dẫn xuất khóa socket rồi trao đổi SYSTEM/KEY_CONFIRM/ACK.
5. Receiver claim transfer; sender truyền đúng fileSize byte dữ liệu, rồi FILE/END.
6. Receiver kiểm tra SHA-256, lưu file và trả FILE/RESULT. Sender chỉ hoàn tất khi
   nhận status OK trả lời đúng End.

Transfer Start/Ready chưa mã hóa. Từ Key Confirm trở đi dùng AES-GCM với khóa
riêng của socket file. File rỗng bỏ qua dữ liệu nhị phân và gửi End ngay.

### Community File

1. Sender xin FILE/BROADCAST_UPLOAD_REQUEST trên phiên quản lý và nhận uploadToken.
2. Sender mở socket server mới, thực hiện Hello/ACK và Key Confirm/ACK, gửi
   FILE/BROADCAST_UPLOAD_START rồi truyền file, End và nhận Result.
3. Server lưu file và phát FILE/BROADCAST_OFFER trên kênh quản lý của các người nhận.
4. Receiver gửi FILE/BROADCAST_DECISION với ACCEPT và nhận downloadToken.
5. Receiver mở socket server mới, hoàn tất handshake/xác nhận khóa, gửi
   FILE/BROADCAST_DOWNLOAD_START rồi nhận dữ liệu, End và trả Result.

Socket dữ liệu dùng token một lần thay cho việc gửi lại password. Server đọc được
nội dung file community và lưu xuống đĩa; chat/file P2P đi trực tiếp giữa hai peer.

### Disconnecting and Reconnecting

Mất kênh quản lý đưa client về SERVER_OFFLINE; các kết nối P2P còn sống tiếp tục
hoạt động. Khi người dùng kết nối lại bằng mật khẩu, client mở TCP mới, thực hiện
lại Hello, Key Confirm và Login. Sequence và khóa của kênh mới được tạo lại.
Client không giữ mật khẩu, không replay tin nhắn/file và không resume file theo offset.
Community đang chọn được xóa; người dùng chọn lại sau khi kết nối thành công.

## Send Events

Các event client có thể gửi. `C` = client, `S` = server, `P` = peer.
Các event hệ thống hai chiều được mô tả riêng trong Event Reference.

| Event | Direction | Description |
| --- | --- | --- |
| [CONNECTION/HELLO](#connection-hello) | C → S | Bắt đầu handshake Client–Server bằng public key X25519 mới và challenge ngẫu nhiên 32 byte. |
| [CONNECTION/PEER_HELLO](#connection-peer-hello) | P initiator → P responder | Mở kết nối chat P2P. |
| [AUTH/LOGIN_REQUEST](#auth-login-request) | C → S | Đăng nhập trên kênh đã hoàn tất xác nhận khóa. |
| [AUTH/LOGOUT_REQUEST](#auth-logout-request) | C → S | Yêu cầu kết thúc phiên đăng nhập hiện tại. |
| [USER/LIST_REQUEST](#user-list-request) | C → S | Lấy danh sách tài khoản đang online trên server. |
| [USER/PEER_LOOKUP_REQUEST](#user-peer-lookup-request) | C → S | Tìm địa chỉ kết nối P2P của một tài khoản. |
| [USER/PEER_KEY_REQUEST](#user-peer-key-request) | C → S | Xin server ký public key X25519 của một kết nối P2P. |
| [CHAT/MESSAGE](#chat-message) | P ↔ P | Gửi một tin nhắn trực tiếp trên kết nối chat P2P đã xác nhận khóa. |
| [CHAT/BROADCAST](#chat-broadcast) | C → S → C trong community | Gửi tin nhắn tới các thành viên online của một community qua server. |
| [CHAT/SESSION_REPORT](#chat-session-report) | C → S | Báo trạng thái kết nối P2P để server theo dõi metadata phiên chat. |
| [CHAT/SESSION_LIST_REQUEST](#chat-session-list-request) | C → S | Lấy danh sách metadata các phiên P2P mà server đang lưu. |
| [FILE/OFFER](#file-offer) | P sender → P receiver, kênh chat | Đề nghị gửi file và trao đổi public key X25519 riêng cho transfer. |
| [FILE/DECISION](#file-decision) | P receiver → P sender, kênh chat | Chấp nhận hoặc từ chối File Offer. |
| [FILE/TRANSFER_START](#file-transfer-start) | P sender → P receiver, socket file | Chọn transfer đã được chấp nhận trên socket dữ liệu mới. |
| [FILE/READY](#file-ready) | P receiver → P sender, socket file | Trả nonce ngẫu nhiên mới cho lần kết nối socket file. |
| [FILE/BROADCAST_UPLOAD_REQUEST](#file-broadcast-upload-request) | C → S, kênh quản lý | Xin upload file vào community mà người gửi là thành viên. |
| [FILE/BROADCAST_UPLOAD_START](#file-broadcast-upload-start) | C → S, socket dữ liệu server | Bắt đầu upload bằng token đã nhận. |
| [FILE/BROADCAST_DECISION](#file-broadcast-decision) | C → S, kênh quản lý | Chấp nhận hoặc từ chối tải file đã được server thông báo. |
| [FILE/BROADCAST_DOWNLOAD_START](#file-broadcast-download-start) | C → S, socket dữ liệu server | Bắt đầu tải bằng fileId và token sau khi hoàn tất handshake và xác nhận khóa. |
| [FILE/BROADCAST_DOWNLOAD_REQUEST](#file-broadcast-download-request) | C → S, kênh quản lý | Nhánh API cũ đã có handler nhưng không được dùng cho luồng download chính. |
| [COMMUNITY/LIST_REQUEST](#community-list-request) | C → S | Lấy danh sách community và trạng thái membership của người gọi. |
| [COMMUNITY/CREATE_REQUEST](#community-create-request) | C → S | Tạo community mới; người tạo trở thành owner và thành viên đầu tiên. |
| [COMMUNITY/JOIN_REQUEST](#community-join-request) | C → S | Tham gia một community hiện có. |
| [COMMUNITY/LEAVE_REQUEST](#community-leave-request) | C → S | Rời community hiện tại. |
| [COMMUNITY/SELECT_REQUEST](#community-select-request) | C → S | Kiểm tra và chọn một community đã tham gia làm đích thao tác của client. |
| [COMMUNITY/MEMBERS_REQUEST](#community-members-request) | C → S | Lấy danh sách thành viên của community đã tham gia. |
| [COMMUNITY/INFO_REQUEST](#community-info-request) | C → S | Lấy thông tin chi tiết một community theo ID. |
| [DISCOVERY/REQUEST](#discovery-request) | C → LAN, UDP | Tìm server trên LAN bằng UDP port 41234. |

## Receive Events

Response trả lời request theo replyTo. Event chủ động không có replyTo/status,
ví dụ Broadcast và Broadcast File Offer. Chat/file P2P nhận trực tiếp từ peer.

| Event | Direction | Description |
| --- | --- | --- |
| [SYSTEM/KEY_CONFIRM_ACK](#system-key-confirm-ack) | Responder → Initiator | Trả lời Key Confirm dưới mã hóa để bên khởi tạo kiểm tra khóa chiều ngược lại. |
| [SYSTEM/PONG](#system-pong) | C ↔ S | Trả lời Ping trên cùng kết nối; được reader xử lý trước các response quản lý. |
| [CONNECTION/HELLO_ACK](#connection-hello-ack) | S → C | Server trả public key X25519 của kết nối và chữ ký Ed25519. |
| [CONNECTION/PEER_HELLO_ACK](#connection-peer-hello-ack) | P responder → P initiator | Peer nhận trả proof của mình, cùng sessionId với Peer Hello. |
| [AUTH/LOGIN_RESPONSE](#auth-login-response) | S → C | Trả kết quả đăng nhập; OK tạo UserSession gắn với kết nối. |
| [AUTH/LOGOUT_RESPONSE](#auth-logout-response) | S → C | Xác nhận logout trước khi kết thúc xử lý kết nối. |
| [USER/LIST_RESPONSE](#user-list-response) | S → C | Trả danh sách username online, sắp xếp theo tên. |
| [USER/PEER_LOOKUP_RESPONSE](#user-peer-lookup-response) | S → C | Trả địa chỉ của peer đang online. |
| [USER/PEER_KEY_RESPONSE](#user-peer-key-response) | S → C | Trả proof gắn danh tính người đăng nhập với sessionId và public key. |
| [CHAT/MESSAGE](#chat-message) | P ↔ P | Gửi một tin nhắn trực tiếp trên kết nối chat P2P đã xác nhận khóa. |
| [CHAT/BROADCAST](#chat-broadcast) | C → S → C trong community | Gửi tin nhắn tới các thành viên online của một community qua server. |
| [CHAT/SESSION_REPORT_RESPONSE](#chat-session-report-response) | S → C | Trả kết quả tiếp nhận báo cáo phiên P2P. |
| [CHAT/SESSION_LIST_RESPONSE](#chat-session-list-response) | S → C | Trả danh sách phiên, sắp xếp theo UUID. |
| [FILE/OFFER](#file-offer) | P sender → P receiver, kênh chat | Đề nghị gửi file và trao đổi public key X25519 riêng cho transfer. |
| [FILE/DECISION](#file-decision) | P receiver → P sender, kênh chat | Chấp nhận hoặc từ chối File Offer. |
| [FILE/READY](#file-ready) | P receiver → P sender, socket file | Trả nonce ngẫu nhiên mới cho lần kết nối socket file. |
| [FILE/END](#file-end) | Bên gửi file → bên nhận file | Kết thúc phần dữ liệu file và cung cấp checksum cuối cùng. |
| [FILE/RESULT](#file-result) | Bên nhận file → bên gửi file | Trả kết quả sau khi kiểm tra checksum và lưu file. |
| [FILE/BROADCAST_UPLOAD_RESPONSE](#file-broadcast-upload-response) | S → C, kênh quản lý | Cấp fileId và token để upload trên socket dữ liệu riêng. |
| [FILE/BROADCAST_OFFER](#file-broadcast-offer) | S → C, kênh quản lý | Thông báo file đã upload thành công cho các thành viên nhận trong community. |
| [FILE/BROADCAST_DOWNLOAD_RESPONSE](#file-broadcast-download-response) | S → C, kênh quản lý | Trả kết quả quyết định tải; ACCEPT hợp lệ được cấp downloadToken. |
| [COMMUNITY/LIST_RESPONSE](#community-list-response) | S → C | Trả các Community Object hiện có. |
| [COMMUNITY/CREATE_RESPONSE](#community-create-response) | S → C | Trả community vừa tạo và chọn community đó trên client. |
| [COMMUNITY/JOIN_RESPONSE](#community-join-response) | S → C | Trả kết quả thao tác join community. |
| [COMMUNITY/LEAVE_RESPONSE](#community-leave-response) | S → C | Trả kết quả thao tác leave community. |
| [COMMUNITY/SELECT_RESPONSE](#community-select-response) | S → C | Trả kết quả thao tác select community. |
| [COMMUNITY/MEMBERS_RESPONSE](#community-members-response) | S → C | Trả username và trạng thái online của các thành viên. |
| [COMMUNITY/INFO_RESPONSE](#community-info-response) | S → C | Trả một Community Object. |
| [DISCOVERY/RESPONSE](#discovery-response) | S → C, UDP | Thông báo TCP port mà server đang lắng nghe. |

## Event Reference

- [SYSTEM](#system-events)
- [CONNECTION](#connection-events)
- [AUTH](#auth-events)
- [USER](#user-events)
- [CHAT](#chat-events)
- [FILE](#file-events)
- [COMMUNITY](#community-events)
- [DISCOVERY](#discovery-events)

Mỗi mục bên dưới gồm điều kiện sử dụng, hướng truyền, structure của body và một
payload JSON đầy đủ. `type` là trường bắt buộc của mọi body; bảng Structure
liệt kê các trường còn lại của từng event, gồm `replyTo` và `status` nếu có.
Các trường dữ liệu ngoài `replyTo` và `status` của response thành công chỉ bắt
buộc khi status là OK.

Các ví dụ là các tình huống độc lập; sequence không tạo thành một trace liên tục.
Các chuỗi `<...>` là placeholder cần thay bằng dữ liệu thật. UUID và checksum là
minh họa; checksum của ví dụ file 0 byte khớp SHA-256 của nội dung rỗng.

### SYSTEM Events

<a id="system-key-confirm"></a>

#### Key Confirm

**Event:** `SYSTEM/KEY_CONFIRM` · **Opcode:** `0` · **Direction:** Initiator → Responder

Gửi ngay sau khi bật AES-GCM để chứng minh bên khởi tạo có khóa gửi. Áp dụng cho kết nối quản lý, P2P chat và P2P file.

Body chỉ có `type`.

**Example Key Confirm**

```json
{
  "opcode": 0,
  "sequence": 2,
  "body": {
    "type": "KEY_CONFIRM"
  }
}
```

Chỉ nhận trong pha xác nhận khóa. Chưa gửi dữ liệu ứng dụng hoặc công bố peer trước khi bước này hoàn tất.

<a id="system-key-confirm-ack"></a>

#### Key Confirm ACK

**Event:** `SYSTEM/KEY_CONFIRM_ACK` · **Opcode:** `0` · **Direction:** Responder → Initiator

Trả lời Key Confirm dưới mã hóa để bên khởi tạo kiểm tra khóa chiều ngược lại.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example Key Confirm ACK**

```json
{
  "opcode": 0,
  "sequence": 2,
  "body": {
    "type": "KEY_CONFIRM_ACK",
    "replyTo": 2,
    "status": "OK"
  }
}
```

Phải khớp type, replyTo và status OK. Sai khóa, sai phản hồi hoặc timeout làm thất bại handshake và đóng kết nối.

<a id="system-ping"></a>

#### Ping

**Event:** `SYSTEM/PING` · **Opcode:** `0` · **Direction:** C ↔ S

Duy trì kết nối quản lý sau đăng nhập. Mỗi bên gửi tối đa một Ping đang chờ phản hồi.

Body chỉ có `type`.

**Example Ping**

```json
{
  "opcode": 0,
  "sequence": 12,
  "body": {
    "type": "PING"
  }
}
```

Gửi mỗi 5 giây khi không còn Ping pending; sau 30 giây chưa có Pong tương ứng thì đóng kết nối. Không áp dụng cho socket chat/file P2P hoặc socket dữ liệu broadcast.

<a id="system-pong"></a>

#### Pong

**Event:** `SYSTEM/PONG` · **Opcode:** `0` · **Direction:** C ↔ S

Trả lời Ping trên cùng kết nối; được reader xử lý trước các response quản lý.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example Pong**

```json
{
  "opcode": 0,
  "sequence": 18,
  "body": {
    "type": "PONG",
    "replyTo": 12,
    "status": "OK"
  }
}
```

replyTo phải khớp Ping đang chờ. Pong không xác nhận tin nhắn đã được nhận hoặc đọc.

### CONNECTION Events

<a id="connection-hello"></a>

#### Hello

**Event:** `CONNECTION/HELLO` · **Opcode:** `1` · **Direction:** C → S

Bắt đầu handshake Client–Server bằng public key X25519 mới và challenge ngẫu nhiên 32 byte.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `protocolVersion` | integer | Phiên bản giao thức; hiện tại là 2. |
| `serverVerificationChallenge` | string | Challenge ngẫu nhiên 32 byte, Base64. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |

**Example Hello**

```json
{
  "opcode": 1,
  "sequence": 1,
  "body": {
    "type": "HELLO",
    "protocolVersion": 2,
    "serverVerificationChallenge": "<32-byte-random-challenge-base64>",
    "publicKey": "<client-x25519-public-key-base64>"
  }
}
```

Chưa mã hóa. Public key dùng X.509 DER rồi Base64. Challenge giúp ràng buộc chữ ký server với lần kết nối này.

<a id="connection-hello-ack"></a>

#### Hello ACK

**Event:** `CONNECTION/HELLO_ACK` · **Opcode:** `1` · **Direction:** S → C

Server trả public key X25519 của kết nối và chữ ký Ed25519. Client kiểm tra bằng public key server đã pin.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `protocolVersion` | integer | Phiên bản giao thức; hiện tại là 2. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |
| `serverSignature` | string | Chữ ký Ed25519 của server, Base64. |

**Example Hello ACK**

```json
{
  "opcode": 1,
  "sequence": 1,
  "body": {
    "type": "HELLO_ACK",
    "replyTo": 1,
    "status": "OK",
    "protocolVersion": 2,
    "publicKey": "<server-x25519-public-key-base64>",
    "serverSignature": "<ed25519-signature-base64>"
  }
}
```

Chưa mã hóa. Server ký purpose=server-handshake, toàn bộ body Hello và serverPublicKey. Sau kiểm tra chữ ký, hai bên dẫn xuất K1/K2, bật AES-GCM và trao đổi Key Confirm. Version khác trả UNSUPPORTED_PROTOCOL_VERSION.

<a id="connection-peer-hello"></a>

#### Peer Hello

**Event:** `CONNECTION/PEER_HELLO` · **Opcode:** `1` · **Direction:** P initiator → P responder

Mở kết nối chat P2P. Peer khởi tạo gửi danh tính, cổng lắng nghe và public key đã được server ký.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `protocolVersion` | integer | Phiên bản giao thức; hiện tại là 2. |
| `username` | string | Tên tài khoản. |
| `peerPort` | integer | Cổng TCP lắng nghe P2P, từ 1 đến 65535. |
| `sessionId` | string | UUID canonical của kết nối chat P2P. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |
| `serverSignature` | string | Chữ ký Ed25519 của server, Base64. |

**Example Peer Hello**

```json
{
  "opcode": 1,
  "sequence": 1,
  "body": {
    "type": "PEER_HELLO",
    "protocolVersion": 2,
    "username": "alice",
    "peerPort": 9001,
    "sessionId": "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011",
    "publicKey": "<x25519-public-key-base64>",
    "serverSignature": "<ed25519-signature-base64>"
  }
}
```

Chưa mã hóa. Bên nhận kiểm tra proof bằng public key server đã pin trước khi dùng public key peer. sessionId là UUID của kết nối chat.

<a id="connection-peer-hello-ack"></a>

#### Peer Hello ACK

**Event:** `CONNECTION/PEER_HELLO_ACK` · **Opcode:** `1` · **Direction:** P responder → P initiator

Peer nhận trả proof của mình, cùng sessionId với Peer Hello.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `protocolVersion` | integer | Phiên bản giao thức; hiện tại là 2. |
| `username` | string | Tên tài khoản. |
| `sessionId` | string | UUID canonical của kết nối chat P2P. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |
| `serverSignature` | string | Chữ ký Ed25519 của server, Base64. |

**Example Peer Hello ACK**

```json
{
  "opcode": 1,
  "sequence": 1,
  "body": {
    "type": "PEER_HELLO_ACK",
    "replyTo": 1,
    "status": "OK",
    "protocolVersion": 2,
    "username": "bob",
    "sessionId": "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011",
    "publicKey": "<bob-x25519-public-key-base64>",
    "serverSignature": "<server-signature-for-bob-base64>"
  }
}
```

Chưa mã hóa. Alice kiểm tra username Bob, sessionId và chữ ký. Hai peer dẫn xuất khóa từ transcript Hello/ACK rồi thực hiện SYSTEM/KEY_CONFIRM và KEY_CONFIRM_ACK trước khi công bố kết nối.

### AUTH Events

<a id="auth-login-request"></a>

#### Login Request

**Event:** `AUTH/LOGIN_REQUEST` · **Opcode:** `2` · **Direction:** C → S

Đăng nhập trên kênh đã hoàn tất xác nhận khóa.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `username` | string | Tên tài khoản. |
| `password` | string | Mật khẩu người dùng; chỉ gửi sau khi bật mã hóa và xác nhận khóa. |
| `peerPort` | integer | Cổng TCP lắng nghe P2P, từ 1 đến 65535. |

**Example Login Request**

```json
{
  "opcode": 2,
  "sequence": 3,
  "body": {
    "type": "LOGIN_REQUEST",
    "username": "alice",
    "password": "<password>",
    "peerPort": 9001
  }
}
```

Mật khẩu đi trong AES-GCM. Server lấy địa chỉ IP từ socket và kết hợp peerPort để đăng ký địa chỉ P2P.

<a id="auth-login-response"></a>

#### Login Response

**Event:** `AUTH/LOGIN_RESPONSE` · **Opcode:** `2` · **Direction:** S → C

Trả kết quả đăng nhập; OK tạo UserSession gắn với kết nối.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example Login Response**

```json
{
  "opcode": 2,
  "sequence": 3,
  "body": {
    "type": "LOGIN_RESPONSE",
    "replyTo": 3,
    "status": "OK"
  }
}
```

**Example Login Failure**

```json
{
  "opcode": 2,
  "sequence": 3,
  "body": {
    "type": "LOGIN_RESPONSE",
    "replyTo": 3,
    "status": "INVALID_CREDENTIALS"
  }
}
```

Các status: OK, INVALID_AUTH_REQUEST, INVALID_CREDENTIALS, ALREADY_LOGGED_IN. Đăng nhập thất bại kết thúc kết nối.

<a id="auth-logout-request"></a>

#### Logout Request

**Event:** `AUTH/LOGOUT_REQUEST` · **Opcode:** `2` · **Direction:** C → S

Yêu cầu kết thúc phiên đăng nhập hiện tại.

Body chỉ có `type`.

**Example Logout Request**

```json
{
  "opcode": 2,
  "sequence": 10,
  "body": {
    "type": "LOGOUT_REQUEST"
  }
}
```

<a id="auth-logout-response"></a>

#### Logout Response

**Event:** `AUTH/LOGOUT_RESPONSE` · **Opcode:** `2` · **Direction:** S → C

Xác nhận logout trước khi kết thúc xử lý kết nối.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example Logout Response**

```json
{
  "opcode": 2,
  "sequence": 17,
  "body": {
    "type": "LOGOUT_RESPONSE",
    "replyTo": 10,
    "status": "OK"
  }
}
```

Client cũng đóng peer và transfer của phiên. Nếu không nhận được response, server dọn session khi socket đóng.

### USER Events

<a id="user-list-request"></a>

#### User List Request

**Event:** `USER/LIST_REQUEST` · **Opcode:** `3` · **Direction:** C → S

Lấy danh sách tài khoản đang online trên server.

Body chỉ có `type`.

**Example User List Request**

```json
{
  "opcode": 3,
  "sequence": 10,
  "body": {
    "type": "LIST_REQUEST"
  }
}
```

<a id="user-list-response"></a>

#### User List Response

**Event:** `USER/LIST_RESPONSE` · **Opcode:** `3` · **Direction:** S → C

Trả danh sách username online, sắp xếp theo tên.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `users` | string[] | Danh sách tài khoản online. |

**Example User List Response**

```json
{
  "opcode": 3,
  "sequence": 17,
  "body": {
    "type": "LIST_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "users": [
      "alice",
      "bob"
    ]
  }
}
```

Danh sách rỗng được biểu diễn bằng users: [].

<a id="user-peer-lookup-request"></a>

#### Peer Lookup Request

**Event:** `USER/PEER_LOOKUP_REQUEST` · **Opcode:** `3` · **Direction:** C → S

Tìm địa chỉ kết nối P2P của một tài khoản.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `username` | string | Tên tài khoản. |

**Example Peer Lookup Request**

```json
{
  "opcode": 3,
  "sequence": 10,
  "body": {
    "type": "PEER_LOOKUP_REQUEST",
    "username": "bob"
  }
}
```

<a id="user-peer-lookup-response"></a>

#### Peer Lookup Response

**Event:** `USER/PEER_LOOKUP_RESPONSE` · **Opcode:** `3` · **Direction:** S → C

Trả địa chỉ của peer đang online.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `ip` | string | Địa chỉ IP để kết nối peer. |
| `peerPort` | integer | Cổng TCP lắng nghe P2P, từ 1 đến 65535. |

**Example Peer Lookup Response**

```json
{
  "opcode": 3,
  "sequence": 17,
  "body": {
    "type": "PEER_LOOKUP_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "ip": "192.168.1.20",
    "peerPort": 9002
  }
}
```

Peer offline trả USER_OFFLINE và không có ip/peerPort. Lookup không chuyển tiếp nội dung chat qua server.

<a id="user-peer-key-request"></a>

#### Peer Key Request

**Event:** `USER/PEER_KEY_REQUEST` · **Opcode:** `3` · **Direction:** C → S

Xin server ký public key X25519 của một kết nối P2P.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | string | UUID canonical của kết nối chat P2P. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |

**Example Peer Key Request**

```json
{
  "opcode": 3,
  "sequence": 10,
  "body": {
    "type": "PEER_KEY_REQUEST",
    "sessionId": "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011",
    "publicKey": "<x25519-public-key-base64>"
  }
}
```

Chỉ dùng trên phiên đã đăng nhập. Server lấy username từ UserSession, không nhận username tự khai trong request.

<a id="user-peer-key-response"></a>

#### Peer Key Response

**Event:** `USER/PEER_KEY_RESPONSE` · **Opcode:** `3` · **Direction:** S → C

Trả proof gắn danh tính người đăng nhập với sessionId và public key.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `username` | string | Tên tài khoản. |
| `sessionId` | string | UUID canonical của kết nối chat P2P. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |
| `serverSignature` | string | Chữ ký Ed25519 của server, Base64. |

**Example Peer Key Response**

```json
{
  "opcode": 3,
  "sequence": 17,
  "body": {
    "type": "PEER_KEY_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "username": "alice",
    "sessionId": "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011",
    "publicKey": "<x25519-public-key-base64>",
    "serverSignature": "<ed25519-signature-base64>"
  }
}
```

Chữ ký bao phủ purpose=peer-public-key, protocolVersion=2, username, sessionId và publicKey. Request không hợp lệ trả INVALID_PEER_KEY.

### CHAT Events

<a id="chat-message"></a>

#### Message

**Event:** `CHAT/MESSAGE` · **Opcode:** `4` · **Direction:** P ↔ P

Gửi một tin nhắn trực tiếp trên kết nối chat P2P đã xác nhận khóa.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `content` | string | Nội dung tin nhắn, tối đa 64 KiB theo byte UTF-8. |

**Example Message**

```json
{
  "opcode": 4,
  "sequence": 3,
  "body": {
    "type": "MESSAGE",
    "content": "Chào Bob!"
  }
}
```

Giới hạn content là 64 KiB tính theo byte UTF-8. Danh tính bên gửi lấy từ kết nối đã xác thực; body không cần sender/receiver. Chưa có receipt nhận/đọc tin.

<a id="chat-broadcast"></a>

#### Broadcast

**Event:** `CHAT/BROADCAST` · **Opcode:** `4` · **Direction:** C → S → C trong community

Gửi tin nhắn tới các thành viên online của một community qua server.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |
| `content` | string | Nội dung tin nhắn, tối đa 64 KiB theo byte UTF-8. |

**Example Broadcast**

```json
{
  "opcode": 4,
  "sequence": 10,
  "body": {
    "type": "BROADCAST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "content": "Nhóm mình bắt đầu nhé!"
  }
}
```

**Example Broadcast nhận từ server**

```json
{
  "opcode": 4,
  "sequence": 17,
  "body": {
    "type": "BROADCAST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "sender": "alice",
    "content": "Nhóm mình bắt đầu nhé!"
  }
}
```

Request C → S có communityId và content. Server kiểm tra membership, thêm sender từ session và phát cho thành viên khác; người gửi không nhận echo. Event này không có response riêng.

<a id="chat-session-report"></a>

#### Session Report

**Event:** `CHAT/SESSION_REPORT` · **Opcode:** `4` · **Direction:** C → S

Báo trạng thái kết nối P2P để server theo dõi metadata phiên chat.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | string | UUID canonical của kết nối chat P2P. |
| `peerUsername` | string | Tài khoản peer còn lại của phiên chat. |
| `state` | string | CONNECTED hoặc DISCONNECTED khi báo cáo phiên. |

**Example Session Report**

```json
{
  "opcode": 4,
  "sequence": 10,
  "body": {
    "type": "SESSION_REPORT",
    "sessionId": "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011",
    "peerUsername": "bob",
    "state": "CONNECTED"
  }
}
```

state gửi lên chỉ nhận CONNECTED hoặc DISCONNECTED. Đây là metadata; dữ liệu chat vẫn đi trực tiếp giữa hai peer.

<a id="chat-session-report-response"></a>

#### Session Report Response

**Event:** `CHAT/SESSION_REPORT_RESPONSE` · **Opcode:** `4` · **Direction:** S → C

Trả kết quả tiếp nhận báo cáo phiên P2P.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example Session Report Response**

```json
{
  "opcode": 4,
  "sequence": 17,
  "body": {
    "type": "SESSION_REPORT_RESPONSE",
    "replyTo": 10,
    "status": "OK"
  }
}
```

Các status: OK, INVALID_SESSION_REPORT, INVALID_SESSION, USER_OFFLINE. Hai báo cáo cùng UUID/cặp user được gộp; báo cáo muộn không mở lại phiên đã DISCONNECTED.

<a id="chat-session-list-request"></a>

#### Session List Request

**Event:** `CHAT/SESSION_LIST_REQUEST` · **Opcode:** `4` · **Direction:** C → S

Lấy danh sách metadata các phiên P2P mà server đang lưu.

Body chỉ có `type`.

**Example Session List Request**

```json
{
  "opcode": 4,
  "sequence": 10,
  "body": {
    "type": "SESSION_LIST_REQUEST"
  }
}
```

<a id="chat-session-list-response"></a>

#### Session List Response

**Event:** `CHAT/SESSION_LIST_RESPONSE` · **Opcode:** `4` · **Direction:** S → C

Trả danh sách phiên, sắp xếp theo UUID.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `sessions` | ChatSession[] | Danh sách metadata phiên P2P; xem Objects. |

**Example Session List Response**

```json
{
  "opcode": 4,
  "sequence": 17,
  "body": {
    "type": "SESSION_LIST_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "sessions": [
      {
        "sessionId": "8f38b564-4b4f-46ae-94c1-2a9d7b5f0011",
        "userA": "alice",
        "userB": "bob",
        "state": "CONNECTED"
      }
    ]
  }
}
```

state trả về là CONNECTED, DISCONNECTED hoặc UNKNOWN. UNKNOWN có thể xuất hiện khi mất kết nối quản lý với server.

### FILE Events

<a id="file-offer"></a>

#### File Offer

**Event:** `FILE/OFFER` · **Opcode:** `5` · **Direction:** P sender → P receiver, kênh chat

Đề nghị gửi file và trao đổi public key X25519 riêng cho transfer.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `transferId` | string | UUID canonical của transfer; với file community bằng fileId. |
| `sender` | string | Tài khoản người gửi. |
| `receiver` | string | Tài khoản nhận file P2P. |
| `fileName` | string | Tên file gốc; bên nhận xử lý tên trước khi lưu. |
| `fileSize` | integer | Kích thước file theo byte, từ 0 đến 524288000. |
| `checksum` | string | SHA-256 của toàn bộ file, 64 ký tự hex thường. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. |

**Example File Offer**

```json
{
  "opcode": 5,
  "sequence": 10,
  "body": {
    "type": "OFFER",
    "transferId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "sender": "alice",
    "receiver": "bob",
    "fileName": "notes.txt",
    "fileSize": 0,
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "publicKey": "<sender-file-x25519-public-key-base64>"
  }
}
```

Người nhận phải ACCEPT trước khi mở lượt truyền. Checksum ví dụ là SHA-256 của file rỗng notes.txt. Public key file được xác thực nhờ kênh chat đã mã hóa.

<a id="file-decision"></a>

#### File Decision

**Event:** `FILE/DECISION` · **Opcode:** `5` · **Direction:** P receiver → P sender, kênh chat

Chấp nhận hoặc từ chối File Offer.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `transferId` | string | UUID canonical của transfer; với file community bằng fileId. |
| `decision` | string | ACCEPT hoặc REJECT. |
| `publicKey` | string | Public key X25519, X.509 DER rồi Base64. Chỉ bắt buộc khi ACCEPT. |

**Example File Decision**

```json
{
  "opcode": 5,
  "sequence": 17,
  "body": {
    "type": "DECISION",
    "replyTo": 10,
    "transferId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "decision": "ACCEPT",
    "publicKey": "<receiver-file-x25519-public-key-base64>"
  }
}
```

replyTo trỏ tới sequence File Offer. Khi REJECT, bỏ publicKey. ACCEPT dùng public key hai bên và metadata để dẫn xuất khóa transfer, sau đó sender mở socket file riêng.

<a id="file-transfer-start"></a>

#### File Transfer Start

**Event:** `FILE/TRANSFER_START` · **Opcode:** `5` · **Direction:** P sender → P receiver, socket file

Chọn transfer đã được chấp nhận trên socket dữ liệu mới.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `transferId` | string | UUID canonical của transfer; với file community bằng fileId. |

**Example File Transfer Start**

```json
{
  "opcode": 5,
  "sequence": 1,
  "body": {
    "type": "TRANSFER_START",
    "transferId": "286c5133-e7c4-46ba-91ef-43bc35b47a10"
  }
}
```

Chưa mã hóa. transferId phải khớp lời mời đang ACCEPTED của người nhận; chỉ biết transferId chưa đủ để chiếm lượt truyền.

<a id="file-ready"></a>

#### File Ready

**Event:** `FILE/READY` · **Opcode:** `5` · **Direction:** P receiver → P sender, socket file

Trả nonce ngẫu nhiên mới cho lần kết nối socket file.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `transferId` | string | UUID canonical của transfer; với file community bằng fileId. |
| `connectionNonce` | string | Nonce ngẫu nhiên 32 byte/Base64, mới cho từng lần thử socket file. |

**Example File Ready**

```json
{
  "opcode": 5,
  "sequence": 1,
  "body": {
    "type": "READY",
    "replyTo": 1,
    "status": "OK",
    "transferId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "connectionNonce": "<32-byte-random-connection-nonce-base64>"
  }
}
```

Chưa mã hóa. Hai bên dẫn xuất khóa socket từ khóa transfer và nonce, rồi trao đổi SYSTEM/KEY_CONFIRM/KEY_CONFIRM_ACK dưới AES-GCM. Receiver chỉ claim transfer sau xác nhận khóa thành công; lần thử lỗi không tiêu thụ offer hợp lệ.

<a id="file-end"></a>

#### File End

**Event:** `FILE/END` · **Opcode:** `5` · **Direction:** Bên gửi file → bên nhận file

Kết thúc phần dữ liệu file và cung cấp checksum cuối cùng.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `transferId` | string | UUID canonical của transfer; với file community bằng fileId. |
| `checksum` | string | SHA-256 của toàn bộ file, 64 ký tự hex thường. |

**Example File End**

```json
{
  "opcode": 5,
  "sequence": 3,
  "body": {
    "type": "END",
    "transferId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
```

Chỉ gửi sau đúng fileSize byte dữ liệu. File rỗng gửi End ngay sau xác nhận khóa. Dùng cho cả P2P và broadcast; với broadcast, transferId bằng fileId.

<a id="file-result"></a>

#### File Result

**Event:** `FILE/RESULT` · **Opcode:** `5` · **Direction:** Bên nhận file → bên gửi file

Trả kết quả sau khi kiểm tra checksum và lưu file.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example File Result**

```json
{
  "opcode": 5,
  "sequence": 3,
  "body": {
    "type": "RESULT",
    "replyTo": 3,
    "status": "OK"
  }
}
```

**Example File Failure**

```json
{
  "opcode": 5,
  "sequence": 3,
  "body": {
    "type": "RESULT",
    "replyTo": 3,
    "status": "FAIL",
    "reason": "Checksum mismatch"
  }
}
```

OK phải trả lời File End. FAIL có thêm reason:string; lỗi sớm có thể trả lời JSON gần nhất. replyTo=0 chỉ được chấp nhận với FAIL khi chưa nhận JSON nào. Sender chỉ báo hoàn tất sau khi nhận OK đúng replyTo.

<a id="file-broadcast-upload-request"></a>

#### Broadcast Upload Request

**Event:** `FILE/BROADCAST_UPLOAD_REQUEST` · **Opcode:** `5` · **Direction:** C → S, kênh quản lý

Xin upload file vào community mà người gửi là thành viên.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |
| `fileName` | string | Tên file gốc; bên nhận xử lý tên trước khi lưu. |
| `fileSize` | integer | Kích thước file theo byte, từ 0 đến 524288000. |
| `checksum` | string | SHA-256 của toàn bộ file, 64 ký tự hex thường. |

**Example Broadcast Upload Request**

```json
{
  "opcode": 5,
  "sequence": 10,
  "body": {
    "type": "BROADCAST_UPLOAD_REQUEST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "fileName": "notes.txt",
    "fileSize": 0,
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
```

<a id="file-broadcast-upload-response"></a>

#### Broadcast Upload Response

**Event:** `FILE/BROADCAST_UPLOAD_RESPONSE` · **Opcode:** `5` · **Direction:** S → C, kênh quản lý

Cấp fileId và token để upload trên socket dữ liệu riêng.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `fileId` | string | UUID file community do server cấp. |
| `uploadToken` | string | Token upload một lần, thời hạn 5 phút. |

**Example Broadcast Upload Response**

```json
{
  "opcode": 5,
  "sequence": 17,
  "body": {
    "type": "BROADCAST_UPLOAD_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "fileId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "uploadToken": "<one-use-upload-token>"
  }
}
```

Token dùng một lần, hết hạn sau 5 phút. Các status: OK, INVALID_REQUEST, INVALID_SIZE, NOT_MEMBER. fileId và uploadToken chỉ có khi OK.

<a id="file-broadcast-upload-start"></a>

#### Broadcast Upload Start

**Event:** `FILE/BROADCAST_UPLOAD_START` · **Opcode:** `5` · **Direction:** C → S, socket dữ liệu server

Bắt đầu upload bằng token đã nhận.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | string | UUID file community do server cấp. |
| `uploadToken` | string | Token upload một lần, thời hạn 5 phút. |

**Example Broadcast Upload Start**

```json
{
  "opcode": 5,
  "sequence": 3,
  "body": {
    "type": "BROADCAST_UPLOAD_START",
    "fileId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "uploadToken": "<one-use-upload-token>"
  }
}
```

Socket này đã hoàn tất CONNECTION/HELLO/ACK và SYSTEM/KEY_CONFIRM/ACK. Không đăng nhập lại bằng password; token cấp quyền cho lượt upload. Tiếp theo là dữ liệu file, FILE/END và FILE/RESULT.

<a id="file-broadcast-offer"></a>

#### Broadcast File Offer

**Event:** `FILE/BROADCAST_OFFER` · **Opcode:** `5` · **Direction:** S → C, kênh quản lý

Thông báo file đã upload thành công cho các thành viên nhận trong community.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | string | UUID file community do server cấp. |
| `sender` | string | Tài khoản người gửi. |
| `communityId` | string | ID community. |
| `fileName` | string | Tên file gốc; bên nhận xử lý tên trước khi lưu. |
| `fileSize` | integer | Kích thước file theo byte, từ 0 đến 524288000. |
| `checksum` | string | SHA-256 của toàn bộ file, 64 ký tự hex thường. |

**Example Broadcast File Offer**

```json
{
  "opcode": 5,
  "sequence": 17,
  "body": {
    "type": "BROADCAST_OFFER",
    "fileId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "sender": "alice",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "fileName": "notes.txt",
    "fileSize": 0,
    "checksum": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  }
}
```

Sự kiện chủ động, không có replyTo/status. Chỉ các thành viên online được đưa vào danh sách người nhận tại lúc phát offer mới nhận lời mời.

<a id="file-broadcast-decision"></a>

#### Broadcast File Decision

**Event:** `FILE/BROADCAST_DECISION` · **Opcode:** `5` · **Direction:** C → S, kênh quản lý

Chấp nhận hoặc từ chối tải file đã được server thông báo.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | string | UUID file community do server cấp. |
| `decision` | string | ACCEPT hoặc REJECT. |

**Example Broadcast File Decision**

```json
{
  "opcode": 5,
  "sequence": 10,
  "body": {
    "type": "BROADCAST_DECISION",
    "fileId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "decision": "ACCEPT"
  }
}
```

Đây là request mới, không mang replyTo. Server kiểm tra người dùng thuộc danh sách người nhận và vẫn là thành viên community; trả FILE/BROADCAST_DOWNLOAD_RESPONSE.

<a id="file-broadcast-download-response"></a>

#### Broadcast Download Response

**Event:** `FILE/BROADCAST_DOWNLOAD_RESPONSE` · **Opcode:** `5` · **Direction:** S → C, kênh quản lý

Trả kết quả quyết định tải; ACCEPT hợp lệ được cấp downloadToken.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `downloadToken` | string | Token download một lần, thời hạn 5 phút. |

**Example Broadcast Download Response**

```json
{
  "opcode": 5,
  "sequence": 17,
  "body": {
    "type": "BROADCAST_DOWNLOAD_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "downloadToken": "<one-use-download-token>"
  }
}
```

Token dùng một lần, hết hạn sau 5 phút. Các status: OK, REJECTED, NOT_FOUND, NOT_AUTHORIZED, INVALID_DECISION; nhánh legacy Download Request trả USE_DATA_CONNECTION. Chỉ OK sau ACCEPT có downloadToken.

<a id="file-broadcast-download-start"></a>

#### Broadcast Download Start

**Event:** `FILE/BROADCAST_DOWNLOAD_START` · **Opcode:** `5` · **Direction:** C → S, socket dữ liệu server

Bắt đầu tải bằng fileId và token sau khi hoàn tất handshake và xác nhận khóa.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `fileId` | string | UUID file community do server cấp. |
| `downloadToken` | string | Token download một lần, thời hạn 5 phút. |

**Example Broadcast Download Start**

```json
{
  "opcode": 5,
  "sequence": 3,
  "body": {
    "type": "BROADCAST_DOWNLOAD_START",
    "fileId": "286c5133-e7c4-46ba-91ef-43bc35b47a10",
    "downloadToken": "<one-use-download-token>"
  }
}
```

Server tiêu thụ token và kiểm tra lại quyền tải, rồi gửi dữ liệu file và FILE/END. Client lưu file, kiểm tra checksum và trả FILE/RESULT. Token sai/hết hạn nhận FILE/RESULT FAIL nếu kênh còn dùng được.

<a id="file-broadcast-download-request"></a>

#### Broadcast Download Request

**Event:** `FILE/BROADCAST_DOWNLOAD_REQUEST` · **Opcode:** `5` · **Direction:** C → S, kênh quản lý

Nhánh API cũ đã có handler nhưng không được dùng cho luồng download chính.

Body chỉ có `type`.

**Example Broadcast Download Request**

```json
{
  "opcode": 5,
  "sequence": 10,
  "body": {
    "type": "BROADCAST_DOWNLOAD_REQUEST"
  }
}
```

Không cấp token và không gửi dữ liệu. Luôn trả FILE/BROADCAST_DOWNLOAD_RESPONSE với status USE_DATA_CONNECTION. Luồng chính dùng Broadcast File Decision.

### COMMUNITY Events

<a id="community-list-request"></a>

#### Community List Request

**Event:** `COMMUNITY/LIST_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Lấy danh sách community và trạng thái membership của người gọi.

Body chỉ có `type`.

**Example Community List Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "LIST_REQUEST"
  }
}
```

<a id="community-list-response"></a>

#### Community List Response

**Event:** `COMMUNITY/LIST_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả các Community Object hiện có.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `communities` | Community[] | Danh sách community; xem Objects. |

**Example Community List Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "LIST_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "communities": [
      {
        "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
        "name": "Network Study",
        "owner": "alice",
        "memberCount": 2,
        "isMember": true,
        "createdAt": "2026-11-15T08:00:00Z"
      }
    ]
  }
}
```

<a id="community-create-request"></a>

#### Community Create Request

**Event:** `COMMUNITY/CREATE_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Tạo community mới; người tạo trở thành owner và thành viên đầu tiên.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `name` | string | Tên community không rỗng, tối đa 100 ký tự. |

**Example Community Create Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "CREATE_REQUEST",
    "name": "Network Study"
  }
}
```

<a id="community-create-response"></a>

#### Community Create Response

**Event:** `COMMUNITY/CREATE_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả community vừa tạo và chọn community đó trên client.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `communityId` | string | ID community. |
| `name` | string | Tên community không rỗng, tối đa 100 ký tự. |

**Example Community Create Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "CREATE_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "name": "Network Study"
  }
}
```

Tên không hợp lệ trả INVALID_NAME hoặc NAME_TOO_LONG. Khi mới tạo, memberCount là 1. Nếu cùng owner đã có community cùng tên không phân biệt hoa/thường, server trả community hiện có.

<a id="community-join-request"></a>

#### Community Join Request

**Event:** `COMMUNITY/JOIN_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Tham gia một community hiện có.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |

**Example Community Join Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "JOIN_REQUEST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90"
  }
}
```

<a id="community-join-response"></a>

#### Community Join Response

**Event:** `COMMUNITY/JOIN_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả kết quả thao tác join community.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `communityId` | string | ID community. |
| `name` | string | Tên community không rỗng, tối đa 100 ký tự. |

**Example Community Join Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "JOIN_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "name": "Network Study"
  }
}
```

Có thể trả NOT_FOUND hoặc ALREADY_MEMBER.

<a id="community-leave-request"></a>

#### Community Leave Request

**Event:** `COMMUNITY/LEAVE_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Rời community hiện tại.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |

**Example Community Leave Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "LEAVE_REQUEST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90"
  }
}
```

<a id="community-leave-response"></a>

#### Community Leave Response

**Event:** `COMMUNITY/LEAVE_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả kết quả thao tác leave community.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |

**Example Community Leave Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "LEAVE_RESPONSE",
    "replyTo": 10,
    "status": "OK"
  }
}
```

Có thể trả NOT_FOUND hoặc NOT_MEMBER. Owner vẫn được rời: quyền sở hữu chuyển cho thành viên còn lại có username nhỏ nhất theo thứ tự sắp xếp; nếu không còn thành viên, community bị xóa.

<a id="community-select-request"></a>

#### Community Select Request

**Event:** `COMMUNITY/SELECT_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Kiểm tra và chọn một community đã tham gia làm đích thao tác của client.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |

**Example Community Select Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "SELECT_REQUEST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90"
  }
}
```

<a id="community-select-response"></a>

#### Community Select Response

**Event:** `COMMUNITY/SELECT_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả kết quả thao tác select community.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `communityId` | string | ID community. |
| `name` | string | Tên community không rỗng, tối đa 100 ký tự. |

**Example Community Select Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "SELECT_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
    "name": "Network Study"
  }
}
```

Có thể trả NOT_FOUND hoặc NOT_MEMBER. Việc chọn không tạo socket mới.

<a id="community-members-request"></a>

#### Community Members Request

**Event:** `COMMUNITY/MEMBERS_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Lấy danh sách thành viên của community đã tham gia.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |

**Example Community Members Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "MEMBERS_REQUEST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90"
  }
}
```

<a id="community-members-response"></a>

#### Community Members Response

**Event:** `COMMUNITY/MEMBERS_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả username và trạng thái online của các thành viên.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `members` | Member[] | Danh sách thành viên và trạng thái online; xem Objects. |

**Example Community Members Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "MEMBERS_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "members": [
      {
        "username": "alice",
        "online": true
      },
      {
        "username": "bob",
        "online": true
      }
    ]
  }
}
```

Có thể trả NOT_FOUND hoặc NOT_MEMBER.

<a id="community-info-request"></a>

#### Community Info Request

**Event:** `COMMUNITY/INFO_REQUEST` · **Opcode:** `6` · **Direction:** C → S

Lấy thông tin chi tiết một community theo ID; handler hiện tại không yêu cầu membership.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | ID community. |

**Example Community Info Request**

```json
{
  "opcode": 6,
  "sequence": 10,
  "body": {
    "type": "INFO_REQUEST",
    "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90"
  }
}
```

<a id="community-info-response"></a>

#### Community Info Response

**Event:** `COMMUNITY/INFO_RESPONSE` · **Opcode:** `6` · **Direction:** S → C

Trả một Community Object.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `community` | Community | Chi tiết community; xem Objects. |

**Example Community Info Response**

```json
{
  "opcode": 6,
  "sequence": 17,
  "body": {
    "type": "INFO_RESPONSE",
    "replyTo": 10,
    "status": "OK",
    "community": {
      "communityId": "1e09a4c8-b353-49ef-b49f-148fbd217c90",
      "name": "Network Study",
      "owner": "alice",
      "memberCount": 2,
      "isMember": true,
      "createdAt": "2026-11-15T08:00:00Z"
    }
  }
}
```

Community không tồn tại trả NOT_FOUND; isMember trong object thể hiện membership của người gọi.

### DISCOVERY Events

<a id="discovery-request"></a>

#### Discovery Request

**Event:** `DISCOVERY/REQUEST` · **Opcode:** `7` · **Direction:** C → LAN, UDP

Tìm server trên LAN bằng UDP port 41234.

Body chỉ có `type`.

**Example Discovery Request**

```json
{
  "opcode": 7,
  "sequence": 1,
  "body": {
    "type": "REQUEST"
  }
}
```

Chưa mã hóa. Client gửi qua broadcast của từng giao diện IPv4, tối đa 5 lần, chờ phản hồi 3 giây mỗi lần.

<a id="discovery-response"></a>

#### Discovery Response

**Event:** `DISCOVERY/RESPONSE` · **Opcode:** `7` · **Direction:** S → C, UDP

Thông báo TCP port mà server đang lắng nghe.

**Structure**

| Field | Type | Description |
| --- | --- | --- |
| `replyTo` | integer | Sequence của request hoặc event mà phản hồi này trả lời. |
| `status` | string | Kết quả xử lý: OK hoặc mã lỗi của event. |
| `tcpPort` | integer | Cổng TCP server, từ 1 đến 65535. |

**Example Discovery Response**

```json
{
  "opcode": 7,
  "sequence": 1,
  "body": {
    "type": "RESPONSE",
    "replyTo": 1,
    "status": "OK",
    "tcpPort": 8080
  }
}
```

IP lấy từ địa chỉ nguồn datagram. Client probe TCP, nhưng chỉ handshake có kiểm tra chữ ký bằng khóa đã pin mới xác thực danh tính server; Discovery Response chưa được ký.

## Objects

### Community Object

| Field | Type | Description |
| --- | --- | --- |
| `communityId` | string | UUID của community. |
| `name` | string | Tên hiển thị. |
| `owner` | string | Username người sở hữu. |
| `memberCount` | integer | Tổng số thành viên. |
| `isMember` | boolean | Người gọi có là thành viên hay không. |
| `createdAt` | string | Thời điểm tạo, ISO 8601. |

### Member Object

| Field | Type | Description |
| --- | --- | --- |
| `username` | string | Tên tài khoản. |
| `online` | boolean | Có phiên quản lý đang online trên server hay không. |

### ChatSession Object

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | string | UUID của kết nối P2P. |
| `userA` | string | Username một bên. |
| `userB` | string | Username bên còn lại. |
| `state` | string | CONNECTED, DISCONNECTED hoặc UNKNOWN. |

## Errors and Limits

Lỗi nghiệp vụ thường nằm trong status của response tương ứng. Khi status khác
OK, không giả định các trường của response thành công còn tồn tại. Sai cấu trúc,
sai sequence, response không khớp request, lỗi xác thực AES-GCM hoặc lỗi socket
có thể kết thúc kết nối. Server không gửi lỗi plaintext sau khi đã bật mã hóa.

| Scope | Behavior |
| --- | --- |
| Request quản lý | Chờ response tối đa 10 giây; timeout đóng kênh quản lý. |
| Heartbeat | Gửi mỗi 5 giây; quá 30 giây chưa có Pong đúng thì đóng kết nối. |
| Nội dung chat | Tối đa 64 KiB theo byte UTF-8. |
| Tên community | Không rỗng, tối đa 100 ký tự. |
| File | Tối đa 500 MiB; kiểm tra SHA-256 trước khi công bố file. |
| File offer P2P | Hết hạn sau 2 phút nếu chưa có quyết định. |
| Sau ACCEPT P2P | Chờ socket/xác nhận khóa tối đa 30 giây. |
| Socket file P2P | Connect timeout 10 giây, read timeout 30 giây. |
| Socket dữ liệu broadcast | Read timeout 60 giây khi truyền. |
| Upload/download token | Một lần, thời hạn 5 phút. |

File nhận trùng tên được thêm hậu tố. Lỗi nhận dọn file tạm. Receiver có thể đã
lưu file nhưng không gửi được Result; sender chỉ xác nhận hoàn tất khi nhận OK.
Không tự retry tin nhắn hoặc file để tránh tạo bản trùng.

## Reserved Events

Các type dưới đây có trong enum nhưng chưa có luồng gửi/nhận ứng dụng. Chúng
không phải API đang hoạt động; không gửi chúng để yêu cầu hành vi chưa triển khai.

| Opcode | Type | Intended purpose |
| --- | --- | --- |
| 0 | ERROR | Thông báo lỗi giao thức. Validator yêu cầu reason:string không rỗng; chưa có handler ứng dụng. |
| 3 | PRESENCE_UPDATE | Thông báo thay đổi trạng thái online. |
| 4 | RECEIPT | Xác nhận nhận tin nhắn. |
| 4 | TYPING | Thông báo đang nhập tin. |

FILE/BROADCAST_DOWNLOAD_REQUEST đã có handler nhưng chỉ trả USE_DATA_CONNECTION;
luồng tải hiện tại dùng FILE/BROADCAST_DECISION. Không có event FILE/CHUNK:
dữ liệu file được truyền trực tiếp trên socket file giữa Ready/Key Confirm và End.

## Implementation Notes

App dùng TCP cho kênh server và P2P, UDP cho Discovery. Payload trong tài liệu là
JSON ở lớp event; PacketCodec và PacketChannel xử lý đóng khung và mã hóa khi
truyền. Đây là cách trình bày tài liệu theo event, không đổi app sang WebSocket.

Phiên bản 2 không tương thích phiên bản 1; client và server phải cập nhật đồng thời.
Các enum trong `src/main/java/protocols/*Type.java` xác định type theo từng nhóm.

## Documentation References

Bố cục tổng quan kết nối tham khảo [Discord Gateway](https://docs.discord.com/developers/events/gateway).
Cách mô tả event bằng Structure và Example tham khảo [Discord Gateway Events](https://docs.discord.com/developers/events/gateway-events).
Các opcode, field, trạng thái và ví dụ trong tài liệu này mô tả ứng dụng DLP.

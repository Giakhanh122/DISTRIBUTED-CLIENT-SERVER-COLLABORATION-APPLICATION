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

Yêu cầu JDK 21 trở lên và Maven. Từ thư mục gốc repository, biên dịch và chạy bằng một lệnh:

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

Script dùng Maven để biên dịch vào `target/classes/` và chép thư viện vào
`target/deps/`, dừng nếu build lỗi rồi chuyển tham số cho entry point đã chọn.
Maven cần tải dependency trong lần đầu. Script chạy được khi gọi từ thư mục khác.
`target/`, file `.class` và `downloads/` được Git bỏ qua.

Các script mở app dùng `-DskipTests` để không chạy bộ kiểm thử trước mỗi lần mở.
File kiểm thử tạm và dữ liệu sinh ra được dọn sau khi chạy.

| Lệnh | Chế độ |
| --- | --- |
| `./run.sh` hoặc `./app.sh cli` | Chạy `app.Main`, chọn server/client trong terminal |
| `./app.sh server` | Chạy server trực tiếp |
| `./app.sh` hoặc `./app.sh ui` hoặc `./run_ui.sh` | Mở JavaFX |

## Khởi động giao diện UI

Chạy `./app.sh server` ở một terminal, sau đó chạy `./app.sh` ở terminal khác
để mở JavaFX. Trên Windows dùng `app.ps1 server` và `app.ps1 ui` tương ứng.

## Mất kết nối và kết nối lại

Server và client trao đổi heartbeat trên **TCP quản lý** mỗi 5 giây. Một probe
không có ACK đúng `replyTo` trong 30 giây sẽ làm đóng kết nối đó; mất mạng âm thầm
thường được phát hiện trong khoảng 30–35 giây, tùy thời điểm probe và lịch chạy.
EOF, lỗi socket hoặc timeout request có thể phát hiện sớm hơn. UDP discovery giữ nguyên.

Khi mất server, GUI hiện **Mất kết nối server**, khóa tạo/join/leave community và
broadcast. Chat/file P2P với peer còn kết nối vẫn dùng được, gồm cả accept offer P2P.
Offer/transfer broadcast thuộc kết nối server cũ bị hủy, không bị gửi lại tự động.

Trong GUI, bấm **Kết nối lại**, nhập lại mật khẩu. Trong CLI dùng `/reconnect`,
rồi nhập mật khẩu khi được hỏi. App dùng lại host/port, username và cổng P2P hiện có;
không lưu mật khẩu để tự retry. Trong lúc nối lại, nút bị khóa để tránh nhiều lần đăng nhập.

Sau đăng nhập thành công, GUI tải lại users/community và đồng bộ metadata P2P còn
sống lên server; lịch sử chat và socket P2P được giữ. Community đang chọn phải
được chọn lại ở CLI. Nếu server restart, các community RAM cũ không còn thì GUI
bỏ lựa chọn cũ, không gửi nhầm sang community khác. Sai mật khẩu hoặc server chưa
sẵn sàng sẽ giữ trạng thái offline và cho thử lại. Nếu gặp `ALREADY_LOGGED_IN`,
phiên cũ có thể chưa hết hạn heartbeat hoặc đang được dùng ở nơi khác; không tự chiếm phiên.

Đây là kết nối lại phần quản lý, không phải resume file theo offset: file P2P còn
sống tiếp tục trên socket cũ; file có socket dữ liệu đã đứt phải gửi lại từ đầu.

## Protocol hiện tại

Thông điệp điều khiển dùng `[len:4 byte big-endian][JSON UTF-8]`; len đếm toàn bộ
byte JSON. Envelope gồm `opcode`, `sequence`, `body`. Mỗi chiều TCP đếm sequence
riêng. ACK/response có `body.status` và `body.replyTo` trỏ tới sequence request.
Handshake trao đổi `protocolVersion = 1`. Server và client cần chạy cùng bản code;
chưa có thương lượng khả năng để tương thích với bản cũ thiếu heartbeat.
Không có magic bytes hoặc header JSON riêng.

Socket file riêng gửi đúng fileSize byte nhị phân rồi FILE_END JSON; receiver
kiểm tra SHA-256 và trả FILE_RESULT. Không đóng gói từng buffer thành FILE_CHUNK.
Client/server phải chạy cùng phiên bản mới. Xem docs/protocol.md để biết chi tiết.

## Tổ chức source

```text
src/main/java/
├── app/          Main: entry point terminal
├── protocols/    Opcode, packet, JSON codec, validation, metadata file
├── network/      PacketChannel và HeartbeatMonitor: I/O, sequence, liveness
├── client/       Đăng nhập, vòng đời phiên, P2P, community, điều phối truyền file
├── server/       connection, auth, session, community, broadcast, storage
├── transfer/     Registry transfer và đọc/ghi dữ liệu file dùng chung
└── ui/           adapter, controller, model, view, util; Launcher mở GUI
```

`Client` cung cấp discovery/handshake/đăng nhập. `ClientSession` sở hữu trạng thái
`ConnectionState`, peer, listener và vòng đời tài nguyên. `ServerContext` nội bộ
nhóm control connection và broadcast controller của một lần kết nối server;
reconnect thay nhóm này và giữ các peer còn sống. `ServerConnection` có một reader,
ghép response bằng `replyTo`, tách ACK heartbeat khỏi request quản lý.

`ClientEvents` chuyển sự kiện có cấu trúc tới `ConsoleEvents` hoặc adapter GUI.
`ClientConsole` xử lý lệnh terminal; code mạng không in hướng dẫn CLI.
`CommunityInfo` và danh sách username là snapshot có kiểu, GUI không giải mã JSON.
`FileOffer` mang cả ID, số offer ngắn và community ID; hai loại offer dùng chung
bộ cấp số trong phiên. `FileTransferRegistry` chỉ giữ/tra cứu transfer và offer.

Trên server, `ClientHandler` giữ handshake, auth, dispatch và dọn phiên;
`CommunityRequestHandler` xử lý request community; `BroadcastService` xử lý
nhắn nhóm, upload và download. Trên GUI, `ConversationStore` giữ lịch sử theo
peer/community ID, `ConnectionStatusView` render trạng thái; `ChatController`
điều phối thao tác màn hình. Adapter giữ token phiên và revision control connection
để chặn callback cũ; đây là hàng rào bất đồng bộ, không phải trạng thái kết nối thứ hai.

## Kiểm tra build

```bash
mvn -DskipTests package
```

Repository không giữ source test hoặc dependency kiểm thử theo yêu cầu;
`mvn test` không chạy lại các bài thử tạm đã dọn.

Đợt refactor đã kiểm tra bằng TCP localhost và GUI JavaFX thật: danh sách user và
community có kiểu, chuyển peer, broadcast vào hai nhóm, file P2P/broadcast 2 MiB
đúng dữ liệu, file rỗng/trùng tên/từ chối, số offer chung, hủy offer broadcast khi
mất server, chat/file P2P lúc offline, sai mật khẩu rồi reconnect, server restart,
logout giữa handshake, 320 request xen heartbeat, thiếu ACK/sai replyTo, tải FXML,
lịch sử và định tuyến file theo community ID, khóa/mở chức năng server và thử lại login.
Script cũng được kiểm tra chế độ GUI mặc định, CLI, server, chuyển host và dừng khi build lỗi.
Dữ liệu và chương trình thử nằm trong thư mục tạm, được dọn sau kiểm thử.
Chưa kiểm chứng nhiều máy, NAT/firewall, đóng gói Windows hoặc biên dịch PDF LaTeX.
Các thử nghiệm này không phải benchmark.

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng frame JSON và binary,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.

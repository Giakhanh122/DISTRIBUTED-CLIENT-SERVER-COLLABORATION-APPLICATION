# DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION

Bài tập môn Computer Networking: ứng dụng client–server dùng Java TCP socket.

## Hiện trạng

- Handshake, đăng nhập và tra cứu người dùng qua server TCP.
- `UserSessionRegistry` quản lý user online; `ChatSessionRegistry` theo dõi
  các kết nối P2P bằng UUID. `/chats` xem các phiên và trạng thái trên server.
- Chat trực tiếp giữa hai peer qua TCP; server dùng virtual thread cho mỗi client.
- Chat broadcast toàn cục tới user đang online và broadcast trong community qua server.
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

Trước lần chạy client đầu tiên, đặt public key của server tại
`config/server-public.key`. Nếu server và client chạy trong cùng repository:

```bash
mkdir -p config
cp server_identity/server-public.key config/server-public.key
```

Server tạo public key khi khởi động lần đầu. Nếu client ở máy khác, lấy
`server_identity/server-public.key` từ đúng máy server qua kênh tin cậy rồi đặt
vào thư mục `config` trên máy client. Chỉ sao chép public key; private key giữ
trên server. Khóa pin phải khớp server mà client kết nối.

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

Có **20 tài khoản demo**:

| Username | Password |
| --- | --- |
| `alice` | `alice123` |
| `bob` | `bob123` |
| `admin1` | `password1` |
| `user01` đến `user17` | `demo123` |

Mỗi tài khoản chỉ đăng nhập một phiên server cùng lúc. Mở nhiều cửa sổ client
với các tài khoản khác nhau để trình diễn xử lý đồng thời.
Sau `/lookup bob`, nhập nội dung để chat với Bob; Bob có thể trả lời trực tiếp.

Khi đã kết nối nhiều peer, dùng `/chat` để xem danh sách và người đang chọn;
`/chat bob` chuyển sang Bob trên kết nối có sẵn. `/lookup bob` cũng chọn lại Bob
nếu đã kết nối, không mở thêm socket hoặc cần server lookup lại. Tin nhắn thường
chỉ gửi tới peer đang chọn; gửi nhóm dùng `/broadcast -m`.
Nếu chọn/lookup một peer không khả dụng, lựa chọn hiện tại được bỏ để tin nhắn kế
tiếp không đi nhầm sang người trước đó. Các kết nối peer khác vẫn được giữ.
Trong CLI, dùng `/global <message>` để gửi tin tới tất cả user online; lệnh
`/broadcast -m` vẫn chỉ gửi vào community đang chọn.

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

File nhận được lưu vào thư mục **Downloads của tài khoản hệ điều hành**.
Trên Linux, app đọc `XDG_DOWNLOAD_DIR` trong `user-dirs.dirs`; nếu không có cấu
hình thì dùng `~/Downloads`. Trên Windows/macOS dùng `user.home/Downloads`.
Không tạo thư mục downloads trong repository; các client trên cùng máy dùng
chung Downloads nên tên trùng được xử lý riêng. File trùng tên
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
`target/` và file `.class` được Git bỏ qua.

Các script mở app dùng `-DskipTests` để không chạy bộ kiểm thử trước mỗi lần mở.
Chạy bộ kiểm thử riêng bằng `mvn test`; test dùng cổng TCP/UDP ngẫu nhiên và
thư mục tạm riêng, không tải file thử vào Downloads thật.

| Lệnh | Chế độ |
| --- | --- |
| `./run.sh` hoặc `./app.sh cli` | Chạy `app.Main`, chọn server/client trong terminal |
| `./app.sh server` | Chạy server trực tiếp |
| `./app.sh` hoặc `./app.sh ui` hoặc `./run_ui.sh` | Mở JavaFX |

## Khởi động giao diện UI

Chạy `./app.sh server` ở một terminal, sau đó chạy `./app.sh` ở terminal khác
để mở JavaFX. Trên Windows dùng `app.ps1 server` và `app.ps1 ui` tương ứng.

Ở file nhận, bấm icon tải xuống để bắt đầu; không có nút Accept/Reject trên GUI.
File P2P chưa tải sẽ hết hạn sau 2 phút. Trong khi truyền có thanh tiến trình,
tốc độ, thời gian còn lại ước tính và nút × để hủy từng file. Hủy đóng socket dữ
liệu riêng, xóa file tải dở rồi kết thúc; lỗi xóa được báo thay vì coi là đã dọn.
File gốc và file đã hoàn tất không bị xóa. Các file khác và chat tiếp tục hoạt động.
Mỗi file dùng một TCP riêng; không giới hạn số file đồng thời.
Sau khi hoàn tất, bấm thẻ file, tên file hoặc icon thư mục để mở vị trí lưu.
Muốn mở file bằng ứng dụng tương ứng, bấm chuột phải thẻ file rồi chọn “Mở file”.

Tin nhắn liên tiếp cùng người gửi trong dưới 5 phút được nhóm; mỗi tin giữ thời gian riêng.
Trạng thái gửi mặc định ẩn, bấm nội dung tin gửi đi để hiện/ẩn. “Đã gửi” không
có nghĩa đã đọc; tooltip phân biệt xác nhận P2P với gửi community.

Trong cửa sổ chat, nút đính kèm cho phép chọn nhiều file hoặc ảnh, xem trước ảnh,
bỏ từng mục rồi gửi cùng caption bằng một lần bấm Send. Caption và từng file vẫn
được gửi bằng các thao tác hiện có; mỗi file là một offer/transfer riêng, không
được gộp vào cùng một socket.

## Mất kết nối và kết nối lại

Server và client trao đổi heartbeat trên **TCP quản lý** mỗi 5 giây.
Mỗi kết nối chat P2P cũng có heartbeat riêng với cùng mốc thời gian. Một probe
không có ACK đúng `replyTo` trong 30 giây sẽ làm đóng kết nối đó; mất mạng âm thầm
thường được phát hiện trong khoảng 30–35 giây, tùy thời điểm probe và lịch chạy.
EOF, lỗi socket hoặc timeout request có thể phát hiện sớm hơn. UDP discovery giữ nguyên.

Khi mất kết nối tới server, GUI hiển thị trạng thái ở sidebar; banner chi tiết
chỉ hiện ở màn hình community, không phủ lên chat riêng. App khóa tạo/join/leave community và
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
Giao thức dùng 8 nhóm opcode và `body.type` bắt buộc. Handshake trao đổi
`protocolVersion = 4`, sau đó xác nhận khóa bằng `SYSTEM/KEY_CONFIRM` và
`SYSTEM/KEY_CONFIRM_ACK` đã mã hóa. Server và client cần chạy cùng bản code;
wire protocol phiên bản 4 không tương thích phiên bản 3.
Không có magic bytes hoặc header JSON riêng.

Socket file P2P mở bằng `FILE/TRANSFER_START` và `FILE/READY`, rồi xác nhận khóa
qua `SYSTEM/KEY_CONFIRM/KEY_CONFIRM_ACK`. Nội dung file, `FILE/END` và `FILE/RESULT`
đều được mã hóa; receiver kiểm tra SHA-256 trước khi công bố file. Không dùng
FILE_CHUNK. Xem [docs/protocol.md](docs/protocol.md) để biết luồng đầy đủ.

## Tổ chức source

```text
src/main/java/
├── app/          Main: entry point terminal
├── protocols/    Opcode, packet, JSON codec, validation, metadata file
├── network/      PacketChannel và HeartbeatMonitor: I/O, sequence, liveness
├── security/     X25519, HKDF, SessionKeys, AES-GCM và chứng thực public key peer
├── client/       Đăng nhập, vòng đời phiên, P2P, community, điều phối truyền file
├── server/       connection, auth, session, community, broadcast, storage
├── transfer/     Registry transfer và đọc/ghi dữ liệu file dùng chung
└── ui/           adapter, controller, model, view, util; Launcher mở GUI
```

Client được chia theo trách nhiệm; các API dùng chung cho GUI/CLI nằm ở gốc:

```text
client/
├── Client.java, ClientSession.java, ClientEvents.java, ConnectionState.java
├── connection/   ServerConnection, ServerDiscovery
├── peer/         PeerConnection, PeerListener, PeerLookupResult
├── community/    CommunityClient, CommunityInfo
├── transfer/     FileTransferController, BroadcastFileController, FileOffer
├── console/      ClientConsole, ConsoleEvents
└── security/     ServerKeyPinning
```

`client.transfer` điều phối truyền file phía client; `transfer` ở gốc source
chứa phần đọc/ghi dữ liệu và registry dùng chung cho client/server.

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

## Cài đặt và kiểm tra môi trường

Cài JDK **21 trở lên** và Maven, đặt `java`, `javac`, `mvn` trên PATH. Kiểm tra:

```text
java -version
javac -version
mvn -version
```

Lần build đầu cần truy cập kho Maven để tải Gson, JavaFX và JUnit. Mở terminal
trong repository rồi dùng các lệnh ở trên; trên Windows có thể dùng
`powershell -ExecutionPolicy Bypass -File .\app.ps1 -Mode server` và
`powershell -ExecutionPolicy Bypass -File .\app.ps1 -Mode ui`.
Sau khi server tạo identity, sao chép **chỉ public key** vào `config/server-public.key`
trên từng máy client. Trên PowerShell: `New-Item -ItemType Directory -Force config`,
rồi `Copy-Item server_identity/server-public.key config/server-public.key` nếu chạy cùng máy.

Với hai máy LAN, dùng IP LAN thật của server. Cổng TCP 8080 phục vụ quản lý và
file community; UDP 41234 phục vụ discovery. P2P cần cho phép cổng TCP listener
của client (cấp động). Nếu discovery không đi qua router/VPN, nhập IP server
trực tiếp. Client cần môi trường desktop để chạy JavaFX; có thể dùng CLI nếu không có.

## Kiểm tra và bằng chứng hiện tại

```bash
mvn test
mvn -DskipTests package
```

Bộ kiểm thử gồm **12 bài**, chạy trên localhost Linux/JDK 25 (target Java 21):

- 20 tài khoản đăng nhập đồng thời; server tiếp tục phục vụ sau client ngắt,
  sai mật khẩu và frame có độ dài sai.
- File P2P 200 MiB, file rỗng và tên/nội dung Unicode, nhiều file cùng lúc;
  so sánh byte bằng `Files.mismatch` và xác nhận hai phía.
- Community upload/download 200 MiB; hủy download xóa file tải dở; hủy upload
  không để lại file tạm hoặc file đã công bố trên server.
- Hủy P2P giữ file đã tồn tại cùng tên, dọn file tạm, truyền file tiếp và chat bình thường.
- Server khởi động lại, sai mật khẩu reconnect rồi thử lại; P2P và cổng listener được giữ.
- Từ chối khóa server không tin cậy, bản ghi AES-GCM bị sửa hoặc cắt ngắn;
  heartbeat mất ACK; frame TCP phân mảnh/gộp; sai checksum và đứt giữa luồng file.

Test socket cần được môi trường cho phép mở localhost. Đây là kiểm tra chức năng,
không phải benchmark băng thông hay bằng chứng 20 GUI chạy trên 20 máy khác nhau.
Chưa xác minh NAT/firewall, đóng gói Windows/macOS hoặc tải liên tục dài hạn.

## Phạm vi và giới hạn

Theo phạm vi hoàn thiện đã chốt, không bổ sung server relay tin nhắn riêng
(bước 5) hoặc global chat. Kiến trúc hiện tại vẫn là P2P cho tin riêng và server
phân phối theo community. Không khẳng định đáp ứng đầy đủ bước 5 của rubric.

Tài khoản là demo trong bộ nhớ; community, phiên và metadata không bền vững khi
server restart. File ngắt phải tải/gửi lại, chưa resume theo offset. Tắt server
bình thường sẽ đóng listener/control/data socket và chờ worker dọn tài nguyên;
SIGKILL hoặc mất điện không thể bảo đảm cleanup như shutdown bình thường.

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng frame JSON và binary,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.
Báo cáo: [report/main.pdf](report/main.pdf); nguồn LaTeX nằm trong `report/`.

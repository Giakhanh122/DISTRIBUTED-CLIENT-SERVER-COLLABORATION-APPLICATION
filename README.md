# DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION

Bài tập môn Computer Networking: ứng dụng client–server dùng Java TCP socket.

- GVHD : Nguyễn Lê Duy Lai
- Sinh viên thực hiện:
  - Võ Văn Gia Khánh - 2411549
  - Nguyễn Hồng Phúc - 2412732
  - Nguyễn Tiến Đạt - 2410712

## Cài đặt và chạy

Làm theo thứ tự các bước dưới đây. Cần cài JDK và Maven; Maven sẽ tự tải các
thư viện Java của dự án trong lần build đầu tiên.

### 1. Cài công cụ

- Cài JDK 21 trở lên, gồm cả `java` và `javac`. Có thể tải Temurin từ
  <https://adoptium.net/temurin/releases/>.
- Cài Apache Maven 3.9 trở lên từ
  <https://maven.apache.org/download.cgi> và thêm thư mục `bin` của Maven vào
  `PATH`.
- Mở terminal mới và xác nhận các lệnh sau chạy được:

```text
java -version
javac -version
mvn -version
```

### 2. Lấy mã nguồn

Clone repository, sau đó chuyển vào thư mục gốc:

```bash
git clone https://github.com/Giakhanh122/DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION.git
cd DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION
```

Nếu đã có mã nguồn, mở terminal tại thư mục chứa `pom.xml`.

### 3. Tải dependencies và build

Chạy tại thư mục gốc. Lần đầu cần Internet để Maven tải Gson, JavaFX, JUnit và
các plugin build:

```bash
mvn -DskipTests package
```

Không cần tải hoặc chép thư viện Java thủ công. Các script chạy ứng dụng cũng
tự build lại dự án.

### 4. Khởi động server trên máy A

Trên máy A, mở terminal tại thư mục gốc và chạy một lệnh phù hợp với hệ điều hành:

```bash
# Linux/macOS
./app.sh server
```

```powershell
# Windows PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -File .\app.ps1 -Mode server
```

Giữ terminal này mở. Server mặc định lắng nghe TCP cổng `8080` và UDP cổng
`41234`; ghi lại địa chỉ IP LAN được in ra khi server khởi động. Lần chạy đầu
tiên, server tạo identity trong `server_identity/`.

### 5. Chuyển public key từ máy A sang máy B

Client xác thực server bằng public key đã pin. Chỉ chuyển file
`server_identity/server-public.key`; **không chép hoặc gửi
`server_identity/server-private.key` cho máy client vào thư mục config/ (tạo thư mục config nếu chưa có).**

Nếu server và client chạy trong cùng một bản checkout, mở terminal thứ hai tại
thư mục gốc rồi chép public key:

```bash
# Linux/macOS
mkdir -p config
cp server_identity/server-public.key config/server-public.key
```

```powershell
# Windows PowerShell
New-Item -ItemType Directory -Force config
Copy-Item server_identity\server-public.key config\server-public.key
```

Với hai máy khác nhau, làm lần lượt:

1. Trên máy A, gửi file `server_identity/server-public.key` sang máy B qua USB
   hoặc một kênh tin cậy khác.
2. Trên máy B, clone repository và mở terminal tại thư mục gốc. Đặt file vừa
   nhận vào `config/server-public.key`. Ví dụ, nếu file nằm trong Downloads:

```bash
# Linux/macOS
mkdir -p config
cp "$HOME/Downloads/server-public.key" config/server-public.key
```

```powershell
# Windows PowerShell
New-Item -ItemType Directory -Force config
Copy-Item "$HOME\Downloads\server-public.key" config\server-public.key
```

Nếu file được lưu ở vị trí khác, thay đường dẫn nguồn trong lệnh. Lặp lại bước
này trên mỗi máy client; public key phải thuộc đúng server mà client kết nối.

### 6. Kết nối client trên máy B tới server trên máy A

Mở terminal mới tại thư mục gốc trên máy client:

```bash
# Linux/macOS
./app.sh cli
```

```powershell
# Windows PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -File .\app.ps1 -Mode cli
```

Khi được hỏi `Run server(s) or client(c):`, nhập `c`. Nếu hai máy cùng mạng LAN,
client có thể tìm server bằng UDP discovery. Nếu discovery không hoạt động, hãy
nhập trực tiếp IP LAN của máy A:

```bash
# Linux/macOS
./run.sh 192.168.1.10
```

```powershell
# Windows
.\run.bat 192.168.1.10
```

Thay `192.168.1.10` bằng IP mà server đã in ra. Khi được hỏi, nhập `c` để chạy
client.

### 7. Đăng nhập và kết nối thêm client

| Username              | Password    |
| --------------------- | ----------- |
| `alice`               | `alice123`  |
| `bob`                 | `bob123`    |
| `admin1`              | `password1` |
| `user01` đến `user17` | `demo123`   |

Mỗi username chỉ đăng nhập được một lần tại một thời điểm. Để kết nối thêm
client trên máy khác, cài JDK/Maven và build ở bước 1--3, sau đó lặp lại bước
5--6 bằng một username khác. Để bắt đầu chat P2P với Bob trong CLI, nhập
`/lookup bob`.

### Cổng mạng cần cho phép

- TCP `8080` trên server: kết nối quản lý và truyền file community.
- UDP `41234` trên cùng mạng LAN: tự tìm server. Nếu discovery không qua router
  hoặc VPN, dùng IP server trực tiếp như bước 6.
- TCP listener P2P của mỗi client: cổng được cấp động; firewall trên máy client
  cần cho phép ứng dụng Java nhận kết nối trong mạng sử dụng.

P2P cần có đường TCP trực tiếp giữa hai client; kết nối qua NAT/firewall hoặc
Internet không được bảo đảm. Để dừng server, nhấn `Ctrl+C` tại terminal server.

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

## Dùng CLI sau khi đăng nhập

- `/users`: xem người dùng đang online.
- `/lookup bob`: tìm Bob và kết nối P2P.
- `/chat`: xem các peer đã kết nối; `/chat bob` chọn một peer đang kết nối.
- `/chats`: xem metadata phiên P2P trên server.
- `/broadcast -m`: gửi tin vào community đang chọn.
- `/global <message>`: gửi tin tới mọi người đang online.
- `/logout`: đăng xuất.

`/lookup` cũng chọn lại peer nếu đã kết nối, không mở thêm socket. Nếu peer
không khả dụng, lựa chọn hiện tại được bỏ để tin nhắn tiếp theo không gửi nhầm
sang peer trước đó; các kết nối peer khác vẫn được giữ.

Để gửi file từ Alice sang Bob, kết nối chat bằng `/lookup bob` trước, rồi:

```text
/sendfile bob /đường/dẫn/tệp dữ liệu.bin
```

Bob nhận được số thứ tự ngắn, ví dụ `[FILE FILE_OFFER #1]`, và chọn một trong hai lệnh:

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

## Khởi động giao diện UI

Chạy `./app.sh server` ở một terminal, sau đó chạy `./app.sh` ở terminal khác
để mở JavaFX. Trên Windows, chạy server bằng
`powershell -NoProfile -ExecutionPolicy Bypass -File .\app.ps1 -Mode server`
và mở giao diện bằng
`powershell -NoProfile -ExecutionPolicy Bypass -File .\app.ps1 -Mode ui`.

Ở file nhận, chọn nút tải về để chấp nhận hoặc nút từ chối trên thẻ offer.
File P2P chưa tải sẽ hết hạn sau 5 phút. Trong khi truyền có thanh tiến trình,
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
`protocolVersion = 6`, sau đó xác nhận khóa bằng `SYSTEM/KEY_CONFIRM` và
`SYSTEM/KEY_CONFIRM_ACK` đã mã hóa. Server và client cần chạy cùng bản code;
wire protocol phiên bản 6 không tương thích các phiên bản cũ.
Không có magic bytes hoặc header JSON riêng.

Socket file P2P mở bằng `FILE_TRANSFER_START` và `FILE_READY`, rồi xác nhận khóa
qua `SYSTEM/KEY_CONFIRM/KEY_CONFIRM_ACK`. Nội dung file, `FILE_END` và `FILE_RESULT`
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

## Kiểm tra và bằng chứng hiện tại

```bash
mvn test
mvn -DskipTests package
```

Bộ kiểm thử hiện tại xác minh trên localhost:

- Server broadcast global tới mọi user online, community chỉ tới thành viên online;
  danh tính người gửi lấy từ phiên đăng nhập, không mở kết nối peer cho broadcast.
- File community upload một lần lên server, phía gửi hiển thị một thẻ file. Các thành
  viên tải độc lập từ server ngay cả khi người gửi đã thoát.
- Hủy một lượt download dọn file tạm, không ảnh hưởng người nhận khác; kiểm tra lại
  membership trước khi cấp quyền download và cho phép từ chối offer.
- Chat/file riêng vẫn truyền trực tiếp qua P2P, tiếp tục hoạt động khi mất server;
  FILE_CANCEL_ACK xác nhận hủy trước khi đóng socket dữ liệu.
- Tên community trùng (không phân biệt hoa/thường, bỏ khoảng trắng hai đầu) báo lỗi,
  giữ nguyên nhóm đang chọn và cho phép nhập tên khác.

Test socket cần được môi trường cho phép mở localhost. Đây là kiểm tra chức năng,
không phải benchmark băng thông hay bằng chứng 20 GUI chạy trên 20 máy khác nhau.
Chưa xác minh NAT/firewall, đóng gói Windows/macOS hoặc tải liên tục dài hạn.

## Phạm vi và giới hạn

Chat riêng và file riêng dùng P2P. Global chat và community dùng client–server:
client gửi tin lên server, server phân phối tới mọi user online hoặc thành viên
online của community, không echo cho người gửi. File community upload một lần,
server lưu và gửi offer; người nhận tải file qua socket riêng tới server.
Server cần hoạt động để dùng global/community và tải file community. Chưa có
xác nhận nhận/đọc riêng cho tin global/community. UI giữ badge riêng cho global,
community và tin riêng, dùng tiếng Anh và hiển thị một thẻ cho mỗi lần gửi file.
Không khẳng định đáp ứng đầy đủ bước 5 của rubric.

Tài khoản là demo trong bộ nhớ; community, phiên và metadata không bền vững khi
server restart. File ngắt phải tải/gửi lại, chưa resume theo offset. Tắt server
bình thường sẽ đóng listener/control/data socket và chờ worker dọn tài nguyên;
SIGKILL hoặc mất điện không thể bảo đảm cleanup như shutdown bình thường.

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng frame JSON và binary,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.
Báo cáo: [report/main.pdf](report/main.pdf); nguồn LaTeX nằm trong `report/`.

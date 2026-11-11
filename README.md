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

Yêu cầu JDK 21 trở lên. Từ thư mục gốc repository, biên dịch và chạy bằng một lệnh:

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

File được lưu vào `downloads/` trong thư mục làm việc của receiver. File trùng tên
được thêm hậu tố `(1)`, `(2)`, …; giới hạn mỗi file là 100 MiB. Bob có thể gửi
ngược lại Alice trên cùng kết nối chat, không cần lookup lại. Có thể chat và dùng
`/users` trong lúc truyền (các lệnh quản lý vẫn cần server).
Hai peer cần kết nối trực tiếp được tới cổng TCP của nhau, chẳng hạn cùng LAN.
Offer được accept chờ socket dữ liệu tối đa 30 giây; socket dữ liệu có read timeout
30 giây. Logout hoặc đóng chat peer dọn các transfer liên quan. Mất kết nối server
không đóng chat/file P2P đã thiết lập.

Script biên dịch lại vào `out/` mỗi lần chạy, dừng nếu biên dịch lỗi và chuyển các
tham số cho `Main`. Script cũng chạy được khi được gọi bằng đường dẫn từ thư mục khác.
`out/`, `target/`, file `.class` và `downloads/` được Git bỏ qua.

## Kiểm thử

Kiểm thử hai registry và luồng theo dõi phiên:

```bash
python3 scripts/test-sessions.py
```

Lệnh dùng JDK 21+ và Python 3, biên dịch vào thư mục tạm, mở TCP cục bộ ở cổng động.
Kiểm tra báo cáo sau handshake, báo cáo đồng thời, quyền sở hữu phiên, báo cáo tới
muộn, thay kết nối, `/chats`, chat hai chiều, truyền file nhị phân, logout và
P2P tiếp tục hoạt động khi mất server. File kiểm thử được tạo trong thư mục tạm.

Yêu cầu thêm Python 3, không cần thư viện ngoài:

```bash
python3 scripts/test-network-resilience.py
```

Lệnh tự biên dịch source hiện tại với Java 21 vào thư mục tạm và chạy `Client`,
`ClientHandler` qua TCP cục bộ trên các cổng động. Không chiếm cổng `8080`/`41234`
và không kiểm thử UDP discovery. Cần quyền mở socket cục bộ.

Test in 11 nhóm kết quả với mục mong đợi, thực tế và PASS/FAIL: đăng nhập sai,
chat hai chiều, timeout handshake/đăng nhập, client reset TCP, truyền file nhị phân,
file rỗng, trùng tên, từ chối, đồng thời, gián đoạn, session không hợp lệ, timeout
`/users` và `/lookup`, mất kết nối server, truyền P2P sau lỗi và logout giữa lúc nhận file.
Thời gian thường khoảng 30–50 giây do kiểm tra timeout thật 10 giây.

Mặc định test in trực tiếp output thật của ứng dụng với nhãn `[server]`, `[alice]`,
`[bob]`, cùng các lệnh test đang gửi như `[bob >] /accept 1`. Các dòng output bao gồm
cả `System.out` và `System.err`. Báo cáo và transcript đầy đủ của từng tiến trình
được lưu trong một thư mục tạm; đường dẫn được in ở cuối. Để chọn chỗ lưu:

```bash
python3 scripts/test-network-resilience.py --log-dir /tmp/collaboration-network-results
```

Dùng `--quiet` nếu chỉ muốn xem báo cáo PASS/FAIL; log ứng dụng vẫn được lưu đầy đủ.

Metadata offer hiện chứa SHA-256; `PEER_HELLO` chứa username, cổng listener và UUID phiên chat.
Các peer cần cùng phiên bản giao thức này; header packet 9 byte vẫn giữ nguyên.

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng packet nhị phân,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.

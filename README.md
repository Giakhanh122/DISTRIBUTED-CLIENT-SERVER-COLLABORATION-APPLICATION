# DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION

Bài tập môn Computer Networking: ứng dụng client–server dùng Java TCP socket.

## Hiện trạng

- Handshake, đăng nhập và tra cứu người dùng qua server TCP.
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

Tài khoản demo: `alice/alice123`, `bob/bob123`, `admin1/password1`.
Sau `/lookup bob`, nhập nội dung để chat với Bob; Bob có thể trả lời trực tiếp.

Để gửi file từ Alice sang Bob, kết nối chat bằng `/lookup bob` trước, rồi:

```text
/sendfile bob /đường/dẫn/tệp dữ liệu.bin
```

Bob nhận được transfer ID và chọn một trong hai lệnh:

```text
/accept <transfer_id>
/reject <transfer_id>
```

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

Yêu cầu thêm Python 3, không cần thư viện ngoài:

```bash
python3 scripts/test.py
```

Lệnh biên dịch vào thư mục tạm, kiểm tra lỗi truyền file và chạy server cùng hai
client qua TCP/UDP thật: đăng nhập, lookup, chat hai chiều, file nhị phân hơn 8 MiB,
file rỗng, trùng tên, từ chối, gián đoạn, kết nối sai session, hai file đồng thời,
truyền ngược chiều và gửi file khi server đã tắt. Cần cổng
TCP `8080`, UDP `41234` đang trống và quyền mở socket cục bộ.

Để chỉ chạy kiểm thử không mở socket:

```bash
python3 scripts/test.py --unit-only
```

Nếu ứng dụng đang chiếm cổng `8080`/`41234`, chạy test TCP độc lập trên cổng động
bằng production `ClientHandler` (bỏ qua phần UDP discovery, không dừng ứng dụng):

```bash
python3 scripts/test.py --isolated
```

Metadata offer hiện chứa SHA-256; `PEER_HELLO` chứa cả username và cổng listener.
Các peer cần cùng phiên bản giao thức này; header packet 9 byte vẫn giữ nguyên.

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng packet nhị phân,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.

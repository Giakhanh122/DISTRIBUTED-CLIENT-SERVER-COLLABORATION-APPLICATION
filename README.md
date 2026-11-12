# DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION

Bài tập môn Computer Networking: ứng dụng client–server dùng Java TCP socket.

## Hiện trạng

- Handshake HELLO/HELLO_ACK, chat nhóm qua server và logout bằng packet nhị phân.
- Server xử lý nhiều client bằng virtual thread; yêu cầu Java 21 trở lên.
- Client nhận tin bằng virtual thread trong khi người dùng nhập tin.
- Đăng nhập, peer chat, truyền file và UDP mới có khai báo opcode.

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

Script biên dịch lại vào `out/` mỗi lần chạy, dừng nếu biên dịch lỗi và chuyển các
tham số cho `Main`. Script cũng chạy được khi được gọi bằng đường dẫn từ thư mục khác.

## Giao diện người dùng
Để chạy app thì sử dụng lệnh:
```bash
./app.sh
```
Hoặc chạy trực tiếp file `DLChat.exe`

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng packet nhị phân,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.

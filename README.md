# DISTRIBUTED-CLIENT-SERVER-COLLABORATION-APPLICATION

Bài tập môn Computer Networking: ứng dụng client–server dùng Java TCP socket.

## Hiện trạng

- Handshake HELLO/HELLO_ACK, chat nhóm qua server và logout bằng packet nhị phân.
- Server xử lý nhiều client bằng virtual thread; yêu cầu Java 21 trở lên.
- Client nhận tin bằng virtual thread trong khi người dùng nhập tin.
- Đăng nhập, peer chat, truyền file và UDP mới có khai báo opcode.

## Chạy chương trình

Từ thư mục gốc repository:

```bash
mkdir -p out
javac -d out src/main/java/Main.java src/main/java/client/Client.java src/main/java/server/Server.java src/main/java/protocols/*.java
java -cp out Main
```

Chọn `s` ở terminal thứ nhất để chạy server, chạy lại lệnh `java -cp out Main` tại
terminal khác và chọn `c` để chạy client. Server dùng cổng `8080`, client kết nối `localhost`.
Mở ít nhất hai client trong các terminal khác nhau. Sau khi nhận HELLO_ACK, nhập văn bản
để gửi tới các client còn lại; nhập `logout` để thoát. Tin nhận hiển thị địa chỉ người gửi.
Dòng rỗng được bỏ qua; EOF console cũng gửi logout. Mỗi tin tối đa 64 KiB UTF-8.
Dùng Ctrl+C tại terminal server để yêu cầu dừng qua shutdown hook.

## Tài liệu

Xem [Giao thức và kiến trúc hiện tại](docs/protocol.md) để biết định dạng packet nhị phân,
bảng opcode, sơ đồ trạng thái và các phần chưa triển khai.

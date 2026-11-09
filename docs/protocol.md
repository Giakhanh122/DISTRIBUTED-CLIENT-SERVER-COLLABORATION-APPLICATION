# Giao thức hiện tại

## Chức năng

- Java 21 trở lên, TCP cổng 8080, client mặc định kết nối localhost.
- HELLO/HELLO_ACK, chat nhóm qua server và LOGOUT_REQUEST/LOGOUT_RESPONSE đều dùng PacketCodec.
- Server dùng virtual thread cho từng kết nối. Client có virtual thread riêng để nhận tin trong khi đọc console.
- Tin được chuyển tới mọi client khác đã hoàn tất handshake; server thêm địa chỉ người gửi.
- Chưa có tài khoản, tên hiển thị, chat riêng, kết nối peer trực tiếp, truyền file hoặc UDP.

## Định dạng packet

```text
OP:u8 | RequestID:i32 | PayloadLength:i32 | Data:N byte
```

Header dài 9 byte, các số nhiều byte dùng big-endian. Payload tối đa 1 MiB;
cả read và write đều kiểm tra giới hạn. Packet clone payload khi tạo và khi trả dữ liệu.
EOF trước opcode trả null; thiếu header/payload, opcode không biết hoặc độ dài sai gây IOException.

## Các packet đã xử lý

| Opcode | Payload | RequestID |
| --- | --- | --- |
| HELLO | Rỗng | Client tự cấp, hiện bắt đầu từ 1 |
| HELLO_ACK | Rỗng | Giữ nguyên ID của HELLO |
| CHAT_MESSAGE, client gửi | Nội dung UTF-8, tối đa 64 KiB | Client tăng ID trên kết nối của mình |
| CHAT_MESSAGE, server chuyển tiếp | Chuỗi UTF-8 `địa_chỉ: nội_dung` | 0, thông báo chủ động |
| LOGOUT_REQUEST | Rỗng | Client tăng ID |
| LOGOUT_RESPONSE | Rỗng | Giữ nguyên ID của LOGOUT_REQUEST |

ID không cần duy nhất giữa các client. Tin chuyển tiếp dùng ID 0, không dùng ID của
client nguồn để tránh nhầm với yêu cầu trên kết nối người nhận. Chưa có CHAT_RECEIPT
hay bảo đảm ứng dụng người nhận đã hiển thị tin.

## Vòng đời

```text
TCP connect → HELLO → HELLO_ACK → gửi/nhận CHAT_MESSAGE
                               → LOGOUT_REQUEST → LOGOUT_RESPONSE → đóng
```

Handshake có timeout đọc 5 giây. Sau handshake, server chờ packet không giới hạn thời gian.
Client chỉ có một luồng đọc socket; luồng console gửi tin và đợi kết quả logout tối đa 5 giây.
Các lần ghi tới cùng một socket server được đồng bộ để packet không bị trộn byte.
Server chỉ thêm client vào danh sách nhận chat sau khi gửi HELLO_ACK và xóa trước khi trả logout.

Nhập `logout` (không phân biệt hoa thường) hoặc EOF console để thoát. Dòng trắng được bỏ qua.
Nếu server mất kết nối khi đang chờ nhập console, client báo lỗi; nhấn Enter để thoát vòng nhập.
Packet không phù hợp trạng thái, hoặc tin vượt giới hạn, khiến server đóng kết nối đó.
Khi server dừng, các socket được đóng trước khi chờ executor kết thúc.

## Chạy thử

Từ thư mục gốc dự án:

```bash
mkdir -p out
javac --release 21 -d out src/main/java/Main.java src/main/java/client/*.java src/main/java/server/*.java src/main/java/protocols/*.java
java -cp out Main
```

Chọn `s` trong một terminal. Mở hai terminal khác trong cùng thư mục, chạy
`java -cp out Main`, chọn `c` ở mỗi terminal. Nhập tin ở client thứ nhất để thấy ở client thứ hai
và ngược lại. Nhập `logout` để đóng một client; client còn lại vẫn giữ kết nối.
Dùng Ctrl+C tại terminal server để dừng.

## Danh sách opcode

Ngoài năm opcode đã xử lý ở trên, các opcode còn lại mới là khai báo cho tính năng dự kiến.
Chiều trao đổi trong bảng là định hướng thiết kế, ngoại trừ CHAT_MESSAGE hiện dùng server chuyển tiếp.

| Nhóm | Opcode | Hex | Chiều / mục đích dự kiến |
| --- | --- | --- | --- |
| Kết nối server | HELLO | 0x01 | Client → Server: mở phiên |
| Kết nối server | HELLO_ACK | 0x02 | Server → Client: xác nhận |
| Quản lý | AUTH_REQUEST | 0x10 | Client → Server: yêu cầu đăng nhập |
| Quản lý | AUTH_RESPONSE | 0x11 | Server → Client: kết quả đăng nhập |
| Quản lý | USER_LIST_REQUEST | 0x12 | Client → Server: yêu cầu danh sách người dùng |
| Quản lý | USER_LIST_RESPONSE | 0x13 | Server → Client: danh sách người dùng |
| Quản lý | PEER_LOOKUP_REQUEST | 0x14 | Client → Server: tra cứu peer |
| Quản lý | PEER_LOOKUP_RESPONSE | 0x15 | Server → Client: kết quả tra cứu |
| Quản lý | LOGOUT_REQUEST | 0x16 | Client → Server: yêu cầu đăng xuất |
| Quản lý | LOGOUT_RESPONSE | 0x17 | Server → Client: kết quả đăng xuất |
| Quản lý | PRESENCE_UPDATE | 0x18 | Server → Client: cập nhật trạng thái người dùng |
| Kết nối peer | PEER_HELLO | 0x20 | Peer khởi tạo → Peer nhận: mở phiên |
| Kết nối peer | PEER_HELLO_ACK | 0x21 | Peer nhận → Peer khởi tạo: xác nhận |
| Chat | CHAT_MESSAGE | 0x30 | Đã tích hợp: Client → Server → các client khác |
| Chat | CHAT_RECEIPT | 0x31 | Peer → Peer: xác nhận tin nhắn |
| Chat | TYPING | 0x32 | Peer → Peer: thông báo đang nhập, tùy chọn |
| File | FILE_OFFER | 0x40 | Bên gửi → Bên nhận: đề nghị gửi file |
| File | FILE_DECISION | 0x41 | Bên nhận → Bên gửi: chấp nhận hoặc từ chối |
| File | FILE_CHUNK | 0x42 | Bên gửi → Bên nhận: một phần dữ liệu |
| File | FILE_END | 0x43 | Bên gửi → Bên nhận: kết thúc dữ liệu |
| File | FILE_RESULT | 0x44 | Bên nhận → Bên gửi: kết quả nhận file |
| File | FILE_CANCEL | 0x45 | Hai chiều: hủy lượt truyền |
| UDP control | DISCOVER_REQUEST | 0x50 | Tìm server |
| UDP control | DISCOVER_RESPONSE | 0x51 | Phản hồi tìm server |
| UDP control | HEARTBEAT | 0x52 | Báo/kiểm tra trạng thái sống |
| UDP control | HEARTBEAT_ACK | 0x53 | Phản hồi heartbeat |
| Lỗi | ERROR | 0x7F | Báo lỗi; chưa có schema payload |

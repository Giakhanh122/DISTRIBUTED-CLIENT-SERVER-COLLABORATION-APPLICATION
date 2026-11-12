# Giao diện ứng dụng Java Client Server chat Communities

## Theme chủ đạo
Màu: các màu xanh và trắng, đen của HCMUT - VNUHCM
Style: flat graphic design
Sample: hơi thiên hướng discord
Format cả thanh điều hướng của ứng dụng java theo theme


## Flow
1. User đăng nhập bằng thông tin tài khoản (hardcode)
2. User tạo community hoặc join community (nếu tìm thấy qua communities)
3. Users cùng community có thể broadcast tin nhắn
4. Users nhắn peer to peer không giới hạn trong community
5. Truyền file qua peer to peer được, người dùng bên kia accept thì tải luôn
6. Broadcast file thì lưu file trong storage của server, người dùng nào accept tải về thì mới tải qua
7. Thông tin về file phải được thể hiện một cách gọn gàng, dễ sử dụng và thống nhất
8. Tải file xong thì có thể nhấn vào để mở file
9. Tránh những description, Icon, bullets AI slop
10. Cho phép dễ dàng di chuyển giữa các peer to peer chats, di chuyển giữa các communities


## Yêu cầu đầu ra
1. Đáp ứng các nội dung trên
2. Kết nối được với các server/community trong cùng mạng
3. Ping thấp, sử dụng mượt mà, tải file nhanh
4. Chạy được trên Windows, macOS, Linux

## Sản phẩm
1. Code base sạch, những thứ không cần thiết sẽ được liệt kê trong gitignore
2. Tạo file app.sh, app.ps1
3. Đóng gói thành file exe

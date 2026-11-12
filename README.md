# DL-Chat - Distributed Collaboration Messenger

Ứng dụng chat và chia sẻ tệp phân tán Client-Server & Peer-to-Peer (P2P). Giao diện tối giản tông màu Vàng Hổ Phách & Đen (`#F59E0B` & `#09090B`).

---

## 🚀 Khởi chạy nhanh

### Cách 1: Chạy từ mã nguồn
- **Server**:
  ```powershell
  .\app.ps1 -Mode server
  ```
- **Client UI**:
  - Windows: Nhấp đúp **`run_ui.bat`** (hoặc chạy `.\app.ps1`).
  - Linux / macOS: Chạy `./app.sh`.

---

### Cách 2: Chạy trực tiếp file EXE
- Đường dẫn: **`dist\DLChat\DLChat.exe`**
- Nhấp đúp file **`DLChat.exe`** để mở ngay mà không cần cài đặt môi trường Java.

---

### Cách 3: Build lại EXE
- Chạy **`build-exe.bat`** (hoặc `.\build-exe.ps1`). Bản build mới sẽ được tạo trong `dist\DLChat\`.

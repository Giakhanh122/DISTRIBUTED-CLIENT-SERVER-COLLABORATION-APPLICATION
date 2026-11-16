# Báo cáo đánh giá hệ thống chat

Thời điểm tổng hợp: **2026-11-15T16:14:54+07:00**, múi giờ Việt Nam.
Phiên bản được kiểm tra: commit `12896c9`, protocol version 2.

## Kết quả và phạm vi kết luận

**72 ca đánh giá: 68 PASS, 4 FAIL, 0 ERROR còn lại.** Trong đó 70 ca dùng tích hợp
socket/live client–server, 1 ca kiểm tra thành phần mã hóa và 1 ca kiểm tra nguồn
về lưu credentials. Ngoài ra, **12/12 test hồi quy có sẵn PASS** qua `mvn -o test`.

Các luồng realtime cơ bản chạy được trong phạm vi đã thử: login, chat hai chiều,
đúng người nhận, membership, truyền file, thu hồi quyền tải, reconnect và heartbeat.
Kết quả chưa đủ để kết luận ứng dụng sẵn sàng đưa ra Internet hoặc đạt một chứng
nhận bảo mật. Bốn điểm không đạt nằm ở chống dò password, lưu credentials, riêng
tư metadata DM và độ bền dữ liệu nhóm.

## Cơ sở đánh giá độc lập với cách app đang viết

Không có một chuẩn duy nhất áp đặt mọi tính năng cho mọi ứng dụng chat. Bộ đánh
giá lấy yêu cầu ở mức người dùng và thuộc tính an toàn làm oracle; protocol/type
của app chỉ là adapter để kích hoạt tình huống. Không lấy “app đang làm vậy” làm
điều kiện để test pass.

- **Xác thực, phân quyền, quản lý phiên, input validation và error handling:**
  tham khảo [OWASP WSTG](https://wstg.owasp.org/stable/4-Web_Application_Security_Testing/).
  Chỉ áp dụng nguyên tắc phù hợp cho TCP/desktop, không coi app là HTTP website.
- **Lỗi login và chống thử password:** tham khảo
  [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html).
  Ngưỡng 20 lần sai trong 5 giây là ngưỡng của đợt đánh giá, không phải con số OWASP quy định.
- **Quyền upload/download, kích thước và tên file không tin cậy:** tham khảo
  [OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html).
- **Người nhận offline:** nhận lỗi rõ hoặc có cơ chế lưu/giao sau; tham khảo mô
  hình xử lý message trong [RFC 6121](https://www.rfc-editor.org/rfc/rfc6121.html#section-8).
  App trả lỗi offline nên không bị đánh FAIL chỉ vì chưa có offline queue.
- **Receipt không đồng nghĩa đã đọc:** tham khảo
  [XEP-0184](https://xmpp.org/extensions/xep-0184.html).
  Receipt là tính năng bổ sung, không được ngầm tính là đã có chỉ vì ghi socket thành công.

Các tiêu chí sản phẩm tự chọn được ghi rõ trong từng ca: DM không lộ metadata
cho người ngoài cuộc; nhóm phải tồn tại sau khi dựng lại server. Chúng có thể
khác chính sách của bài tập, nên FAIL ở đây không tự động có nghĩa là hồi quy so
với PRD hiện tại.

## Môi trường và cách chạy

- Linux, OpenJDK 25.0.4.1; mã ứng dụng build với Java release 21, Maven offline.
- Fixture TCP dùng `ClientHandler`, `AuthService`, registries, community/broadcast
  services, file storage và các `Client`/`ClientSession` thật của app.
- Mỗi ca dựng fixture riêng, dùng cổng localhost ngẫu nhiên và dữ liệu/khóa tạm.
  Không mock kết quả đăng nhập, phân quyền, tin nhắn hoặc dữ liệu file.
- Dùng ba tài khoản demo sẵn có; tối đa 3 người dùng khác nhau trong các ca chức
  năng. Có ca 6 login cùng tài khoản, 32 socket chưa hoàn tất handshake, 300 tin
  một chiều, 100 tin mỗi chiều đồng thời, 3 file song song và một file 4 MiB.
- R09 chạy **handler UDP thật** qua cổng loopback ngẫu nhiên. Không chạy discovery
  broadcast nhiều card mạng trên LAN thật. G09 dựng lại toàn bộ state server
  trong fixture mới, không phải kill/restart tiến trình OS thật.
- Các ca đối kháng gửi packet hoặc byte stream trực tiếp, không chỉ dùng validation
  của client chuẩn: giả sender, sai nhóm/type, duplicate JSON key, sequence sai,
  ciphertext không hợp lệ, token giả/replay và socket file không có khóa.
- Kết quả giữ trong [system-assessment-2026-11-15.json](system-assessment-2026-11-15.json),
  gồm expected/actual, thời gian mỗi ca và checksum nguồn/suite trước khi dọn.

Mã test tạm được biên dịch bằng `javac` vào thư mục `/tmp/dlp-system-audit-*`, chạy
Java với classpath trỏ tới `target/classes` và Gson. Không thêm test đánh giá này
vào `src/test`, không sửa implementation để làm test pass.

Năm ca âm ở lần đầu gặp lỗi null trong chính helper test khi socket trả EOF.
Đã sửa helper và chạy lại cả năm: đều PASS. Không tính lỗi runner thành lỗi app.
Bốn FAIL cũng được chạy xác nhận lại và vẫn tái hiện. Không cộng những lần chạy
lại thành ca mới trong tổng 72.

## Tổng hợp theo nhóm

| Nhóm | Số ca | PASS | FAIL |
| --- | ---: | ---: | ---: |
| Xác thực và phiên | 10 | 9 | 1 |
| Chat trực tiếp và riêng tư | 11 | 10 | 1 |
| Community | 9 | 8 | 1 |
| File P2P | 13 | 13 | 0 |
| File community | 7 | 7 | 0 |
| Kết nối, phục hồi và discovery | 9 | 9 | 0 |
| Bảo mật và dữ liệu bất thường | 13 | 12 | 1 |

## Những điểm không đạt

### A09 — Chưa có bảo vệ đủ trước dò password

**Mức ưu tiên: cao khi triển khai ngoài môi trường demo.**

Tiêu chí: 20 lần sai liên tiếp trong khoảng ngắn cần gặp throttle/lockout/challenge
hoặc độ trễ rõ ràng. Thực tế cả 20 request được kiểm tra và trả INVALID_CREDENTIALS,
không phải 20 lần đăng nhập thành công. Lần đầu mất 161 ms, lần xác nhận 255 ms.
Không quan sát thấy hạn chế trong thử nghiệm hữu hạn này; code AuthService và
luồng handleAuth cũng chưa có bộ đếm hoặc rate limiter tương ứng.

Tái hiện: trên server riêng, mở 20 kết nối, hoàn tất handshake, gửi login cùng
username với password sai; ghi status và tổng thời gian. Không chạy spray vào
server đang phục vụ người dùng.

Hướng xử lý: giới hạn theo tài khoản và nguồn kết nối, backoff phù hợp, log sự
kiện thất bại; cân nhắc nguy cơ kẻ xấu lợi dụng lockout để khóa người dùng khác.
Nguồn: [AuthService.java](../src/main/java/server/auth/AuthService.java),
[ClientHandler.java](../src/main/java/server/connection/ClientHandler.java).

### S10 — Credentials demo nằm dạng rõ trong nguồn

**Mức ưu tiên: cao trước khi dùng tài khoản thật.**

Đây là **source assertion**, không phải phép thử chứng minh lấy được password
qua mạng. AuthService có tài khoản/password hardcode và so sánh trực tiếp bằng
`expectedPassword.equals(password)`. S11 bắt traffic proxy vẫn PASS: password
không xuất hiện dạng rõ trong stream đã bắt. Bảo vệ khi truyền và bảo vệ nơi
lưu credentials là hai thuộc tính riêng.

Hướng xử lý: dùng kho tài khoản và password hash có salt bằng thuật toán dành
cho password, tách demo credentials khỏi chế độ triển khai thật. Không tự sửa
password trong đợt đánh giá.
Nguồn: [AuthService.java](../src/main/java/server/auth/AuthService.java).

### M11 — User ngoài cuộc xem được metadata DM

**Mức ưu tiên: trung bình nếu sản phẩm hứa hẹn DM riêng tư.**

Alice và Bob mở chat P2P. Tài khoản thứ ba gọi session list và nhận được sessionId,
userA, userB, state của cuộc chat. Nội dung tin nhắn không bị lộ trong ca này.
Handler trả toàn bộ `chatSessions.listSessions()` mà không lọc theo người gọi.
Tên tài khoản thứ ba là `admin1`, nhưng model phiên hiện tại không có role admin
hoặc bước kiểm tra quyền để phân biệt nó với user khác.

README hiện mô tả `/chats` là danh sách chung. Vì vậy đây là **không đạt baseline
riêng tư của đợt đánh giá**, không phải kết luận implementation làm sai hành vi
đã mô tả. Nếu muốn dashboard quản trị, cần vai trò và quyền riêng cho endpoint;
user thường chỉ thấy phiên mình tham gia.
Nguồn: [ClientHandler.java](../src/main/java/server/connection/ClientHandler.java).

### G09 — Nhóm mất khi dựng lại state server

**Mức ưu tiên: trung bình về độ tin cậy sản phẩm.**

Tạo community, đóng fixture server và tạo fixture mới với state mới, sau đó login:
community trước đó không còn. Registry hiện chỉ dùng ConcurrentHashMap. Đây là
**thiếu persistence so với baseline chat app**, phù hợp với thiết kế RAM hiện tại.
Ca này chưa kiểm chứng một quy trình crash/restart OS thật hoặc phục hồi đĩa.

Hướng xử lý: lưu community/membership vào kho bền, kiểm tra load lại khi startup
và tính nguyên tử khi cập nhật. Không tự thêm database trong đợt đánh giá.
Nguồn: [CommunityRegistry.java](../src/main/java/server/community/CommunityRegistry.java).

## Bằng chứng các thuộc tính đã đạt

- **Định tuyến:** người thứ ba không nhận DM; broadcast chỉ tới thành viên, rời
  nhóm ngừng nhận; sender giả trong DM/broadcast không thay được danh tính thật.
- **Giao tin:** Unicode/emoji/newline giữ nguyên; 300 tin một chiều và 200 tin hai
  chiều không mất/trùng/sai thứ tự trong lượt chạy. Không suy ra exactly-once qua
  reconnect hoặc hiệu năng Internet từ kết quả này.
- **File:** nhận đúng file 0, 7, 200000 byte; 3 transfer song song không lẫn;
  file cùng tên không ghi đè; traversal tên file không thoát thư mục; checksum
  sai và EOF giữa file đều FAIL, không để file đích hoặc file tạm.
- **Chống chiếm offer:** socket biết transferId nhưng không có khóa bị từ chối;
  sender hợp lệ vẫn hoàn tất cùng offer. Không accept lại offer đã hoàn tất.
- **Capability/nhóm:** token upload đã dùng bị replay thất bại; token download
  giả bị chặn; token đã cấp không vượt qua membership bị thu hồi sau leave.
- **Phiên:** duplicate login không chiếm phiên; logout vô hiệu hóa socket;
  reconnect sai password không online; reconnect đúng giữ peer đang mở.
- **Liveness:** heartbeat không ngắt sai sau hai chu kỳ; client im lặng bị thu
  hồi sau **35010 ms**; đường P2P còn sống tiếp tục chat khi kênh server mất.
- **Mã hóa:** server pin sai bị từ chối; proxy bắt 605 byte không thấy password
  hoặc LOGIN_REQUEST dạng rõ; packet ciphertext giả bị đóng; kiểm tra thành
  phần counter nonce từ chối ciphertext replay ở counter kế tiếp.
- **Robustness có giới hạn:** server vẫn nhận login sau packet malformed và khi
  có 32 socket stalled. Đây không phải phép thử chống DDoS hoặc tải lớn.

M03 ghi 213 ms cho lượt 300 tin nhưng bao gồm 150 ms chờ kiểm tra không có tin
trùng; không dùng số đó tính throughput. F10 đo thời gian nhận chat trong lúc
truyền file 4 MiB dưới độ phân giải 1 ms trên localhost; không coi đó là SLA.

## Thiếu tính năng sản phẩm — review, không cộng vào 72 ca

| Khả năng | Nhận xét |
| --- | --- |
| Lịch sử bền qua lần mở app | ConversationStore chỉ giữ map trong RAM; chưa có kho lịch sử bền. |
| Delivery/read receipts | RECEIPT được khai báo nhưng chưa có luồng xử lý. UI nói Sent (unconfirmed), không giả là Delivered. |
| Offline queue/sync | Hiện báo lỗi khi peer offline; đạt tiêu chí tránh mất tin âm thầm, chưa có giao sau hoặc đồng bộ nhiều thiết bị. |
| Vòng đời tài khoản | Tài khoản demo cố định; chưa có registration/reset/change-password hoặc role quản trị được thực thi. |
| Chống lạm dụng sản phẩm | Chưa thấy luồng block/report user; chưa đánh giá chính sách moderation/quota. |

Các tính năng này cần chốt theo mục tiêu sản phẩm; không phải mọi chat app đều
bắt buộc có đủ. Nguồn review gồm [ConversationStore.java](../src/main/java/ui/model/ConversationStore.java),
[ChatMessageModel.java](../src/main/java/ui/model/ChatMessageModel.java),
[ChatType.java](../src/main/java/protocols/ChatType.java).

## Chưa kiểm chứng

- GUI tương tác đầu-cuối, rendering/accessibility, kéo thả và dialog file. Môi
  trường không có Xvfb/display phục vụ bài test UI. Kiểm thử ClientSession không
  thay thế kiểm thử JavaFX hoặc console UX hoàn chỉnh.
- Discovery LAN nhiều interface, NAT/firewall, mạng WAN, packet loss/reordering,
  đổi Wi-Fi/IP và mất mạng một chiều. UDP R09 chỉ kiểm tra handler qua loopback.
- Tải nhiều tài khoản thật, memory leak/soak nhiều giờ, backpressure khi receiver
  cực chậm, giới hạn disk/quota, mất điện và crash thật giữa bước lưu file.
- File thực 500 MiB, token hết hạn sau đủ 5 phút, offer hết hạn sau đủ 2 phút;
  đã kiểm tra metadata âm/vượt 500 MiB nhưng không suy rộng thành test toàn biên.
- Kiểm toán mật mã độc lập, side-channel, fuzzing dài hạn, phân tích malware/file
  độc hại hoặc bảo mật của hệ điều hành nơi lưu file.
- Xác minh end-to-end read receipts, multi-device sync hoặc account recovery:
  các luồng này chưa tồn tại, không có test runtime để tuyên bố PASS.

## Danh sách ca và kết quả cuối

Kết quả chi tiết expected/actual đầy đủ nằm trong JSON đi kèm. Dưới đây là kết
quả cuối sau các lần xác nhận; không ẩn bốn FAIL và không trộn lỗi runner vào lỗi app.

| ID | Tiêu chí | Kết quả | Bằng chứng quan sát |
| --- | --- | --- | --- |
| A01 | Đăng nhập hợp lệ | **PASS** | Login + users successful |
| A02 | Sai mật khẩu | **PASS** | Rejected; no user session |
| A03 | Không dò tài khoản bằng lỗi login | **PASS** | Both return INVALID_CREDENTIALS |
| A04 | Trường login rỗng | **PASS** | Empty username rejected |
| A05 | Chặn thao tác trước login | **PASS** | Pre-auth management rejected |
| A06 | Phiên trùng không chiếm tài khoản | **PASS** | Duplicate denied, original alive |
| A07 | Logout vô hiệu hóa phiên | **PASS** | Socket closed; relogin successful |
| A08 | Đăng nhập trùng đồng thời | **PASS** | Exactly 1 of 6 accepted |
| A09 | Chống dò password | **FAIL** | 20 wrong-password attempts accepted for checking in 255ms; statuses=[INVALID_CREDENTIALS] |
| A10 | Server giả không được tin | **PASS** | Mismatched server identity rejected |
| M01 | Tin nhắn Unicode giữ nguyên | **PASS** | Exact Unicode round-trip |
| M02 | Chat hai chiều | **PASS** | Both directions delivered |
| M03 | Giữ thứ tự và không trùng | **PASS** | 300/300 ordered; elapsed=213ms (localhost) |
| M04 | Không lộ tin cho người thứ ba | **PASS** | Only intended recipient received |
| M05 | Không gửi nhầm sau chọn peer lỗi | **PASS** | Stale selection cleared |
| M06 | Người nhận offline có kết quả rõ | **PASS** | Sender receives offline failure |
| M07 | Payload lớn không phá kênh tốt | **PASS** | Oversized rejected; next message delivered |
| M08 | Biên kích thước Unicode | **PASS** | 65536 UTF-8 bytes delivered intact |
| M09 | Chat đồng thời hai chiều | **PASS** | 200/200 delivered in per-direction order |
| M10 | Không giả danh sender | **PASS** | Identity remains authenticated alice |
| M11 | Quyền riêng tư metadata DM | **FAIL** | Unrelated user sees Alice/Bob private session metadata: [{"sessionId":"fa07b055-aafd-4371-bc36-4415f82769b7","userA":"alice","userB":"bob","state":"CONNECTED"}] |
| G01 | Tạo/tham gia/xem nhóm | **PASS** | 2 members; owner preserved |
| G02 | Broadcast chỉ tới thành viên | **PASS** | Members-only fan-out |
| G03 | Người ngoài không gửi vào nhóm | **PASS** | Unauthorized group send rejected |
| G04 | Chặn xem danh sách thành viên trái quyền | **PASS** | NOT_MEMBER |
| G05 | Rời nhóm ngừng nhận tin | **PASS** | No post-leave delivery |
| G06 | Owner rời không làm nhóm mồ côi | **PASS** | Ownership transferred to remaining member |
| G07 | Validation tên nhóm | **PASS** | Invalid names rejected without session loss |
| G08 | Không giả sender broadcast | **PASS** | Sender bound to bob session |
| G09 | Dữ liệu nhóm bền qua server restart | **FAIL** | Community disappears after new server instance; only RAM registry |
| F01 | File toàn vẹn 0 byte | **PASS** | 0 exact bytes saved; sender and receiver completed |
| F02 | File toàn vẹn 7 byte | **PASS** | 7 exact bytes saved; sender and receiver completed |
| F03 | File toàn vẹn 200000 byte | **PASS** | 200000 exact bytes saved; sender and receiver completed |
| F04 | Từ chối file | **PASS** | Rejected; no output file |
| F05 | Không ghi đè file cùng tên | **PASS** | Distinct destinations preserved |
| F06 | File lỗi checksum không được công bố | **PASS** | Corrupt transfer failed; no final/temp; chat alive |
| F07 | Path traversal qua tên file | **PASS** | Sanitized and contained in receiver directory |
| F08 | Offer giả danh bị từ chối | **PASS** | Forged participant rejected; chat alive |
| F09 | Truyền file đồng thời không lẫn | **PASS** | 3 concurrent files preserved |
| F10 | Chat còn đáp ứng trong lúc gửi file | **PASS** | Chat latency=0ms during 4MiB transfer |
| F11 | Socket giả không chiếm lượt file | **PASS** | Bad-key socket rejected; legitimate same offer completed |
| F12 | Quyết định file không dùng lại | **PASS** | Completed offer cannot be accepted twice |
| F13 | File bị cắt giữa chừng không báo hoàn tất | **PASS** | Mid-file disconnect failed and cleaned output |
| B01 | Broadcast file toàn vẹn | **PASS** | 120000 bytes uploaded, downloaded and confirmed |
| B02 | Chặn upload người ngoài nhóm | **PASS** | NOT_MEMBER |
| B03 | Chặn download người ngoài nhóm | **PASS** | NOT_AUTHORIZED |
| B04 | Upload token dùng một lần | **PASS** | Initial upload OK; replay FAIL |
| B05 | Rời nhóm làm mất quyền tải đã cấp | **PASS** | Previously granted token denied after leave |
| B06 | Metadata file sai kích thước | **PASS** | Negative and over-limit sizes denied |
| B07 | Token download giả bị chặn | **PASS** | Forged capability rejected |
| R01 | P2P còn hoạt động khi mất server | **PASS** | Server connections lost; P2P message delivered |
| R02 | Reconnect không làm mất peer đang mở | **PASS** | Management restored; original peer usable |
| R03 | Reconnect sai password không hồi sinh phiên | **PASS** | Wrong rejected; later valid retry successful |
| R04 | Peer đóng được phát hiện và có thể mở lại | **PASS** | Dead peer removed; new session works |
| R05 | Heartbeat duy trì kênh yên lặng | **PASS** | 2 heartbeat intervals + management request passed |
| R06 | Thu hồi phiên im lặng | **PASS** | Unresponsive authenticated session evicted in 35010ms |
| R07 | Kết nối P2P giao nhau không làm kẹt | **PASS** | Crossed connects converge; message delivered |
| R08 | Logout không được hồi sinh | **PASS** | Closed session remains CLOSED |
| R09 | UDP discovery phản hồi endpoint hợp lệ | **PASS** | Actual server UDP handler returns live TCP endpoint (loopback, ephemeral UDP port) |
| S01 | Duplicate JSON field | **PASS** | Malformed connection rejected; subsequent login alive |
| S02 | Type thuộc nhóm khác | **PASS** | Malformed connection rejected; subsequent login alive |
| S03 | Sequence bỏ bước | **PASS** | Malformed connection rejected; subsequent login alive |
| S04 | Opcode không hợp lệ | **PASS** | Malformed connection rejected; subsequent login alive |
| S05 | Ciphertext bị sửa bị từ chối | **PASS** | Unauthenticated ciphertext rejected; no session created |
| S06 | Handshake chậm không làm cạn server ở tải nhỏ | **PASS** | Login served with 32 stalled sockets, 9ms |
| S07 | Không chấp nhận đổi username trên phiên | **PASS** | Reauthentication opcode rejected on active socket |
| S08 | Khóa peer gắn đúng danh tính | **PASS** | Wrong username/session binding rejected |
| S09 | Replay record không được xử lý lần hai | **PASS** | Record encrypted at counter 0 rejected at counter 1 (component check) |
| S10 | Không giữ password dạng rõ trong nguồn | **FAIL** | Hardcoded demo passwords and plaintext comparison in AuthService.java |
| S11 | Password và payload login không xuất hiện rõ trên dây | **PASS** | 605 bytes captured; no plaintext password/login body |
| S12 | Không downgrade giao thức cũ | **PASS** | Old protocol version rejected: UNSUPPORTED_PROTOCOL_VERSION |
| S13 | Frame quá lớn không cấp phát vô hạn | **PASS** | Oversized length rejected before payload allocation |

## Dấu vết và dọn dẹp

- SHA-256 tổng hợp các file Java main (relative path + NUL + content + NUL):
  `71100e8c9b10c051bdbde996bc44a2fe9bf0b6c7407e23b9e93571fb24448c11`.
- SHA-256 source bộ đánh giá tạm cuối cùng trước khi xóa:
  `6f49f42bee2bf45f6a2960e0d2e59890b53c3cadfd7c3ea22bda0e702880467a`.
- Đã xóa source test đánh giá, class sinh ra, thư mục chạy, file fixture, khóa
  pin fixture và raw log trong `/tmp/dlp-system-audit-*` của đợt này. JSON bằng
  chứng và báo cáo Markdown được giữ; không giữ code test đánh giá.
- Hai file test hồi quy vốn có trong repository được giữ nguyên. Không xóa
  test đã có hoặc thay implementation để làm đẹp kết quả.
- Ngoài báo cáo, đợt này xử lý lỗi setup người dùng báo: đặt public key server
  đúng vào `config/server-public.key`, bỏ qua file pin môi trường trong Git và
  bổ sung hướng dẫn README. Handshake tới `192.168.1.37:8080` đã xác nhận chữ ký
  khớp khóa; phép kiểm tra đó không gửi username/password, không nằm trong 72 ca.

Các điểm FAIL còn nguyên để người dùng review và quyết định thứ tự sửa.

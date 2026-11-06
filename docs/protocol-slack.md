# Tham khảo giao thức Slack

Ngày tra cứu: **05/11/2026**. Phạm vi: API công khai cho app/bot, tập trung Socket
Mode, Events API và Web API. Đây là tài liệu tham khảo cho [protocol.md](protocol.md),
không mô tả giao thức nội bộ của client Slack. Dữ liệu ví dụ là giả; payload có
thể đã lược bớt trường.

## 1. Các kênh trao đổi

| API | Vai trò |
| --- | --- |
| Web API qua HTTPS | Gọi thao tác như chat.postMessage |
| Events API qua HTTP | Slack POST event tới endpoint của app |
| Socket Mode qua WebSocket | Nhận event/interaction mà không cần endpoint HTTP công khai |

Events API hỗ trợ HTTP hoặc Socket Mode.
[Events API](https://docs.slack.dev/apis/events-api/).
Trong Socket Mode không có bảng opcode số tương đương Discord: phân loại bằng
`type`, xác nhận bằng `envelope_id`.
[Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/).

## 2. Mở kết nối

App gọi `apps.connections.open` bằng app-level token có scope `connections:write`;
phản hồi có `ok:boolean`, `url:string` khi thành công. Kết nối tới URL WebSocket
được cấp; không lưu URL này như địa chỉ cố định.
[apps.connections.open](https://docs.slack.dev/reference/methods/apps.connections.open/).

```text
App                                      Slack
 |--- HTTPS apps.connections.open -------->|
 |<-- ok=true, url=wss://... ---------------|
 |--- mở WebSocket ----------------------->|
 |<-- type=hello --------------------------|
 |<-- type=events_api, envelope_id ---------|
 |--- ACK envelope_id -------------------->|
```

## 3. Envelope của Socket Mode

| Trường | Kiểu | Vai trò |
| --- | --- | --- |
| `type` | string | Ví dụ events_api, interactive, slash_commands |
| `envelope_id` | string | ID lượt giao cần ACK |
| `payload` | object | Event hoặc dữ liệu tương tác |
| `accepts_response_payload` | boolean | ACK có được kèm dữ liệu phản hồi hay không |

`hello` có thể chứa `connection_info`, `num_connections`, `debug_info`.
`disconnect` có `reason`, ví dụ warning hoặc refresh_requested, để app chuẩn bị
nối lại. Các control message này không dùng chung schema envelope event.
[Envelope và vòng đời](https://docs.slack.dev/apis/events-api/using-socket-mode/).

ACK tối thiểu:

```json
{"envelope_id": "demo-envelope-01"}
```

Có thể thêm `payload` nếu accepts_response_payload cho phép. ACK xác nhận nhận
sự kiện, không phải receipt đọc tin. Cần xử lý refresh kết nối.
[ACK Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/#5-acknowledge-events).

## 4. Envelope của Events API

Socket Mode bọc object Events API bên trong `payload`; trong object đó lại có
`event` chứa dữ liệu nghiệp vụ. Các metadata chính:

| Trường | Kiểu | Vai trò |
| --- | --- | --- |
| `type` | string | event_callback |
| `team_id` | string | Workspace |
| `api_app_id` | string | App nhận event |
| `event_id` | string | ID event duy nhất toàn cục |
| `event_time` | integer | Thời điểm dispatch, Unix giây |
| `event` | object | Sự kiện nghiệp vụ |
| `event_context` | string, tùy event | Ngữ cảnh dùng để tra quyền truy cập event |
| `authorizations` | object[] | Thông tin cài đặt được phép nhận event |

Các trường thay đổi theo loại event. Verification token cũ đã deprecated;
HTTP dùng signing secret để kiểm tra nguồn gửi.
[Callback fields](https://docs.slack.dev/apis/events-api/#callback-field-overview).

Ví dụ một tin nhắn người dùng được chuyển qua Socket Mode, đã lược trường:

```json
{
  "type": "events_api",
  "envelope_id": "demo-envelope-01",
  "accepts_response_payload": false,
  "payload": {
    "type": "event_callback",
    "team_id": "TDEMO01",
    "api_app_id": "ADEMO01",
    "event_id": "EvDEMO01",
    "event_time": 1793865600,
    "event": {
      "type": "message",
      "channel": "CDEMO01",
      "user": "UDEMO01",
      "text": "Chào nhóm học mạng",
      "ts": "1793865600.000101",
      "channel_type": "channel"
    }
  }
}
```

Ba lớp type trong ví dụ: events_api chọn loại envelope Socket Mode,
event_callback chọn callback Events API, message chọn sự kiện nghiệp vụ.
[Socket Mode](https://docs.slack.dev/apis/events-api/using-socket-mode/),
[message event](https://docs.slack.dev/reference/events/message/).

## 5. Metadata của message và thao tác gửi

| Trường message event | Kiểu | Ý nghĩa |
| --- | --- | --- |
| `channel` | string | ID hội thoại |
| `user` | string | ID người gửi trong message thông thường |
| `text` | string | Nội dung |
| `ts` | string | Timestamp nhận diện tin trong từng channel |
| `subtype` | string, tùy chọn | Phân loại như message_changed, message_deleted, bot_message |

Không phải mọi subtype đều có cùng schema. Giữ `ts` dạng chuỗi; dùng cặp
(channel, ts) nhận diện tin. Một event sửa/xóa có thể có dữ liệu lồng bên trong.
[message event](https://docs.slack.dev/reference/events/message/).

Gửi tin bằng `POST https://slack.com/api/chat.postMessage`, token trong
`Authorization: Bearer ...`. Không gửi một event message lên socket để thay thế
Web API. Với tin văn bản đơn giản, body có channel và text:

```json
{
  "channel": "CDEMO01",
  "text": "Đã nhận bài tập",
  "thread_ts": "1793865600.000101"
}
```

thread_ts tùy chọn, trỏ tới tin gốc nếu trả lời theo thread. Phản hồi thành công
có ok, channel, ts, message; thất bại có ok=false và error. blocks là tùy chọn
cho nội dung có cấu trúc.
[chat.postMessage](https://docs.slack.dev/reference/methods/chat.postMessage/).

Slack còn có tham số **message metadata** dạng event_type + event_payload,
khác metadata vận chuyển như envelope_id:

```json
{
  "channel": "CDEMO01",
  "text": "Có bài tập mới",
  "metadata": {
    "event_type": "course_task_created",
    "event_payload": {"task_id": "task-demo-01"}
  }
}
```

Đây là dữ liệu nghiệp vụ app đính kèm tin; tài liệu Slack nêu metadata này có thể
được app/người dùng trong workspace truy cập.
[Tham số metadata](https://docs.slack.dev/reference/methods/chat.postMessage/#arguments).

## 6. ACK, retry và giới hạn

| ID | Cấp độ | Mục đích |
| --- | --- | --- |
| `envelope_id` | Socket Mode | ACK lần giao payload |
| `event_id` | Events API | Nhận diện event |
| `channel` + `ts` | Message | Nhận diện tin nhắn |

**Gợi ý thiết kế:** dùng event_id để chống xử lý event trùng; không coi envelope_id
là ID tin nhắn và không coi ACK là kết quả xử lý nghiệp vụ.

Với **HTTP Events API**, trả HTTP 2xx trong 3 giây; khi thất bại Slack retry tối
đa 3 lần, kèm x-slack-retry-num và x-slack-retry-reason. Đây là quy tắc HTTP,
không tự áp lịch retry đó cho Socket Mode.
[HTTP event delivery](https://docs.slack.dev/apis/events-api/#responding-to-events).

Web API bị rate limit trả 429 và Retry-After tính bằng giây; giới hạn tùy phương
thức/workspace. Đọc header để lên lịch gọi lại.
[Rate limits](https://docs.slack.dev/apis/web-api/rate-limits/).

## 7. Truyền file

Luồng upload công khai hiện dùng HTTP:

```text
files.getUploadURLExternal(filename, length)
  → nhận upload_url và file_id
  → POST byte file tới upload_url
  → files.completeUploadExternal(files, channel_id tùy chọn)
```

Bước đầu có filename và length (byte); dữ liệu file đi riêng với event JSON.
[Working with files](https://docs.slack.dev/messaging/working-with-files/).
Bước hoàn tất nhận files chứa id và title tùy chọn. Có thể chỉ định channel_id
để chia sẻ file; nếu không có thì không chia sẻ vào channel.
[files.completeUploadExternal](https://docs.slack.dev/reference/methods/files.completeUploadExternal/).

## 8. Gợi ý áp dụng cho dự án

Phần này là **nhận xét thiết kế**, không phải quy định Slack:

| Thành phần dự án | Cách tham khảo |
| --- | --- |
| `OP` | Giữ opcode số; học cách tách envelope và dữ liệu nghiệp vụ |
| `RequestID` | Ghép yêu cầu/phản hồi trên kết nối |
| `Data.eventId` nếu bổ sung | Chống xử lý lại event sau reconnect/retry |
| `Data.messageId` | Nhận diện tin, độc lập ID lần giao |
| `CHAT_RECEIPT` | Ghi rõ ACCEPTED, DELIVERED hay READ |
| `Data.channelId` nếu có phòng | Định tuyến hội thoại bằng ID ổn định |
| `FILE_CHUNK` | Có thể giữ luồng peer; upload HTTP là phương án để so sánh |

Nên tách việc nhận/ACK khỏi tác vụ xử lý lâu, nhưng chỉ ACK sau khi đã tiếp nhận
đủ tin cậy theo mức bảo đảm mong muốn. Thêm retry cần quy tắc chống trùng và
thời hạn lưu ID. Xem thêm [tham khảo Discord](protocol-discord.md).

# Tham khảo giao thức Discord

Ngày tra cứu: **05/11/2026**. Phạm vi: API công khai dành cho app/bot, chủ yếu
Gateway và HTTP API. Đây là bản tóm tắt để thiết kế [protocol.md](protocol.md),
không phải đặc tả giao thức nội bộ của ứng dụng Discord. Ví dụ dùng dữ liệu giả;
các object minh họa có thể đã lược bớt trường.

## 1. Kiến trúc và định dạng

App nhận sự kiện qua Gateway WebSocket, gửi thao tác quản lý kết nối trên Gateway;
gửi tin nhắn bằng HTTP API. Gateway hỗ trợ JSON hoặc ETF; tài liệu này dùng JSON.
Theo trang Gateway tại ngày tra cứu, payload app gửi tối đa 4096 byte.
[Gateway](https://docs.discord.com/developers/events/gateway),
[Create Message](https://docs.discord.com/developers/resources/message#create-message).

```json
{
  "op": 0,
  "d": {},
  "s": 120,
  "t": "MESSAGE_CREATE"
}
```

| Trường | Kiểu | Vai trò |
| --- | --- | --- |
| `op` | integer | Opcode điều khiển |
| `d` | JSON value hoặc null | Payload theo opcode/event |
| `s` | integer hoặc null | Sequence để heartbeat/resume |
| `t` | string hoặc null | Tên event; ngoài Dispatch thì s và t là null |

Schema envelope và các payload bên dưới theo
[Gateway Events](https://docs.discord.com/developers/events/gateway-events#payload-structure).
`?:` nghĩa là trường tùy chọn; `T/null` nghĩa là nhận giá trị T hoặc null;
`id` là Snowflake biểu diễn bằng chuỗi trong JSON.

## 2. Opcode và metadata trong d

`A` = app, `D` = Discord. Mã là **số thập phân**, thuộc Gateway chính, không phải
Voice Gateway. Danh sách tên/mã/chiều theo
[Gateway Opcodes](https://docs.discord.com/developers/topics/opcodes-and-status-codes#gateway-opcodes);
các trường payload theo [Send Events](https://docs.discord.com/developers/events/gateway-events#send-events).

| op | Tên / chiều | Schema d |
| --- | --- | --- |
| 0 | Dispatch, D → A | Object theo `t` |
| 1 | Heartbeat, hai chiều | Khi app gửi: sequence gần nhất hoặc null |
| 2 | Identify, A → D | `token:string`, `properties:object`, `intents:integer`; tùy chọn `compress:boolean`, `large_threshold:integer`, `shard:integer[2]`, `presence:object`, `capabilities:integer` |
| 3 | Presence Update, A → D | `since:integer/null`, `activities:object[]`, `status:string`, `afk:boolean` |
| 4 | Voice State Update, A → D | `guild_id:id`, `channel_id:id/null`, `self_mute:boolean`, `self_deaf:boolean` |
| 6 | Resume, A → D | `token:string`, `session_id:string`, `seq:integer` |
| 7 | Reconnect, D → A | null |
| 8 | Request Guild Members, A → D | `guild_id:id`, `query?:string`, `limit?:integer`, `presences?:boolean`, `user_ids?:id/id[]`, `nonce?:string` |
| 9 | Invalid Session, D → A | boolean: khả năng resume |
| 10 | Hello, D → A | `heartbeat_interval:integer`, đơn vị ms |
| 11 | Heartbeat ACK, D → A | null |
| 31 | Request Soundboard Sounds, A → D | `guild_ids:id[]` |
| 43 | Request Channel Info, A → D | `guild_id:id`, `fields:string[]` |

`properties` gồm `os`, `browser`, `device` dạng string. `intents` chọn nhóm event;
`capabilities` chọn hành vi Gateway. Op 8 dùng query hoặc user_ids; có query thì
cần limit. Nonce tối đa 32 byte. Op 43 hỗ trợ fields `status`, `voice_start_time`.
[Chi tiết schema](https://docs.discord.com/developers/events/gateway-events#send-events).

## 3. Handshake, heartbeat và resume

```text
App                                  Discord
 |--- mở WebSocket -------------------->|
 |<-- op 10 Hello ----------------------|
 |--- op 2 Identify ------------------->|
 |<-- op 0, t=READY --------------------|
 |--- op 1 Heartbeat ------------------>|
 |<-- op 11 Heartbeat ACK --------------|
 |<-- op 0, t=MESSAGE_CREATE -----------|
```

Heartbeat đầu dùng độ trễ ngẫu nhiên từ 0 đến heartbeat_interval; sau đó gửi theo
chu kỳ. Nếu thiếu ACK trước lần heartbeat kế tiếp, nối lại. Lưu sequence gần
nhất, session_id và resume_gateway_url từ READY để gửi op 6 khi có thể resume.
[Connection lifecycle](https://docs.discord.com/developers/events/gateway#connection-lifecycle).

Ví dụ Identify tối giản; intents phải phù hợp quyền/cấu hình app:

```json
{
  "op": 2,
  "d": {
    "token": "BOT_TOKEN_PLACEHOLDER",
    "properties": {
      "os": "linux",
      "browser": "network-course-demo",
      "device": "network-course-demo"
    },
    "intents": 513
  }
}
```

Ví dụ heartbeat sau khi nhận Dispatch có `s = 120`:

```json
{"op": 1, "d": 120}
```

Ví dụ resume:

```json
{
  "op": 6,
  "d": {
    "token": "BOT_TOKEN_PLACEHOLDER",
    "session_id": "demo-session-a",
    "seq": 120
  }
}
```

Các ví dụ ứng với [Identify, Heartbeat, Resume](https://docs.discord.com/developers/events/gateway-events#send-events).
Heartbeat ACK xác nhận kết nối sống; không phải xác nhận người dùng đã đọc tin.

## 4. Metadata tin nhắn và file

Tin nhắn được tạo bằng `POST /channels/{channel.id}/messages`; không có một
Gateway opcode CHAT_MESSAGE để app gửi nội dung chat. Phản hồi trả Message object.
[Create Message](https://docs.discord.com/developers/resources/message#create-message).

| Trường Message | Kiểu | Ý nghĩa |
| --- | --- | --- |
| `id` | id | Tin nhắn |
| `channel_id` | id | Kênh |
| `author` | object | Tác giả |
| `content` | string | Văn bản |
| `timestamp` | ISO 8601 string | Thời điểm tạo |
| `edited_timestamp` | ISO 8601 string/null | Thời điểm sửa |
| `attachments` | object[] | Metadata file đính kèm |

Attachment thường có `id`, `filename`, `size`, `url`, `proxy_url`; có thể có
`content_type`. File được upload qua HTTP multipart; Gateway mang metadata.
Một số trường nội dung phụ thuộc quyền MESSAGE_CONTENT.
[Message và Attachment](https://docs.discord.com/developers/resources/message#message-object).

Ví dụ phần dữ liệu của một sự kiện tin nhắn, đã lược trường:

```json
{
  "op": 0,
  "s": 121,
  "t": "MESSAGE_CREATE",
  "d": {
    "id": "1400000000000000101",
    "channel_id": "1400000000000000201",
    "author": {"id": "1400000000000000301", "username": "demo_an"},
    "content": "Chào nhóm học mạng",
    "timestamp": "2026-11-05T08:00:00.000Z",
    "edited_timestamp": null,
    "attachments": []
  }
}
```

Snowflake có thể dài tới 64 bit; giữ dạng chuỗi, tránh chuyển thành số thực.
Token HTTP nằm trong Authorization header; token Gateway nằm trong Identify/Resume.
[ID và xác thực](https://docs.discord.com/developers/reference#snowflakes).

## 5. Lỗi và giới hạn

Gateway dùng WebSocket close code, ví dụ 4002 cho lỗi decode và 4004 cho lỗi
xác thực. Khả năng reconnect/resume phụ thuộc close code.
[Gateway Close Codes](https://docs.discord.com/developers/topics/opcodes-and-status-codes#gateway-close-event-codes).

HTTP API có status code và body lỗi. Khi bị rate limit, nhận 429; đọc Retry-After
hoặc retry_after (giây), không retry liên tục. Metadata hữu ích gồm
X-RateLimit-Remaining, X-RateLimit-Reset-After và X-RateLimit-Bucket.
[Rate Limits](https://docs.discord.com/developers/topics/rate-limits).

## 6. Gợi ý áp dụng cho dự án

Đây là **nhận xét thiết kế**, không phải quy định Discord:

| Thành phần dự án | Cách tham khảo |
| --- | --- |
| `OP` | Phân biệt thao tác điều khiển và sự kiện nghiệp vụ |
| `RequestID` | Giữ vai trò ghép yêu cầu/phản hồi; sequence cần trường riêng |
| `Data.messageId` | Nhận diện tin độc lập với kết nối |
| `HELLO_ACK` | Công bố chu kỳ heartbeat và phiên bản |
| `AUTH_RESPONSE` | Có session ID nếu muốn bổ sung resume sau này |
| `CHAT_RECEIPT` | Phân biệt ACK kết nối với trạng thái tin nhắn |
| `FILE_CHUNK` | Có thể giữ truyền TCP peer; HTTP upload của Discord là phương án kiến trúc khác |

Nếu bổ sung resume, cần cả sequence, lưu sự kiện phía server và cơ chế phát lại;
chỉ thêm session ID chưa đủ. Xem thêm [tham khảo Slack](protocol-slack.md).

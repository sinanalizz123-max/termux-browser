# Termux Browser protocol v1 (M0)

All control traffic is JSON over `127.0.0.1` on an ephemeral port, plus one
WebSocket event stream. Token in `Authorization: Bearer`, never in URLs.

## Envelope

```json
{
  "protocolVersion": "1",
  "requestId": "req_...",
  "commandId": "cmd_...",
  "generationId": 42
}
```

- `requestId`: HTTP-level correlation, always present in responses.
- `commandId`: automation-operation identifier for mutating commands.
- `generationId`: session generation; stale callbacks carry an old generation
  and are discarded.

## Endpoints (M1 implements none yet; reserved)

- `GET /v1/status`
- `GET /v1/activity?since=<eventId>` — replay from bounded ring buffer
  (bounded by event count AND approximate bytes).
- `GET /v1/result/<command-id>`
- `POST /v1/commands/open|search|back|forward|reload|stop|read`
- `POST /v1/commands/ai-chat` — reserved in M0/M1, implemented M7+.
- `POST /v1/control/pause|resume|stop`
- `WS /v1/events` — live stream; `/v1/activity?since=` is replay, not live.

## Control states

- `AUTOMATION_RUNNING`: a Termux operation owns the WebView.
- `PAUSED`: commands halted, page untouched, queue preserved.
- `USER_TAKEOVER`: active automation cancelled, queue preserved for
  explicit resume. Resume is always explicit, never automatic.
- `STOPPED`: current op cancelled, queued automation cleared, generation++.

## Event stream (M5)

- `WS /v1/events?since=N`, Bearer-authenticated, localhost only.
- Typed events with monotonic `eventId`: command queued/started/completed/
  failed/cancelled, automation paused/resumed/stopped, user takeover,
  browser url/title/loading/error.
- Replay-then-live: IDs strictly greater than N, then live delivery with no
  gap or duplicate. N older than retained history yields an explicit
  `history_gap` event; the client resynchronizes.
- Bounded history (300 events / 256 KB); slow consumers drop oldest and
  never block publishers or command execution.
- `GET /v1/activity` remains independent of the stream.

## Command queue and results (M2)

- Queue is bounded (16 commands). A full queue answers `QUEUE_FULL`
  (HTTP 429) instead of accumulating work.
- Command endpoints are rate-limited (HTTP 429 `RATE_LIMITED`).
- Results are immutable once completed, retained bounded by count (32) and
  bytes (256 KB) with a 5-minute TTL. Expired/unknown IDs answer
  `RESULT_NOT_FOUND` (HTTP 404).
- `stop` marks drained queued commands `cancelled`.

## Error codes

- Transport: `AUTH_REQUIRED`, `INVALID_REQUEST`, `INVALID_URL`,
  `BODY_TOO_LARGE`, `RATE_LIMITED`, `QUEUE_FULL`, `SERVER_NOT_READY`,
  `NOT_FOUND`, `RESULT_NOT_FOUND`, `BUSY`, `CANCELLED`.
- Domain: `LOGIN_REQUIRED`, `CAPTCHA_DETECTED`, `USER_TAKEOVER`,
  `NAVIGATION_CHANGED`, `TIMEOUT`, `DOM_CHANGED`, `AI_ERROR`.

## Read scopes

`page | title | links | visible_text | selection | main_content`, each with `maxChars`
(default 20000). Links return `{text, href, visible}`.

## URL policy

Termux `open` accepts `http(s)` only. Rejected: `file:`, `content:`,
`javascript:`, `data:`, `intent:`, `blob:`. Manual entry of unsupported
schemes shows a user-facing rejection.

## Privacy

- Activity log defaults to ACTION + METADATA (counts, not content).
- Never exposed via API: cookies, WebView storage, cache, credentials,
  full page HTML, full AI conversations.
- No token in logs, notifications, events, shell history, or exceptions.
- Pairing is a one-time manual copy from the app screen into
  `browserctl connect` (getpass, file mode 0600). The CLI never prints it.

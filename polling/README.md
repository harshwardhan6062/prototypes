# polling

One event, four delivery mechanisms, running side by side in one page:
**short polling · long polling · SSE · WebSocket**.

## Run

```bash
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/python server.py     # http://localhost:8080
```

## What to do

1. Click **Send a message**. Watch the four latency figures.
2. Click **Drop all connections** — three mechanisms come back on their own.
3. Untick **WebSocket auto-reconnect**, drop again. Watch the one that doesn't.
4. Tick **Auto-send every 5s** and leave it running. Watch the request counts diverge.

## The numbers it produces

A typical run, ~40 seconds, two messages sent:

| | requests | latency to notice |
| --- | --- | --- |
| short polling | 20 | 560 ms |
| long polling | 4 | 8 ms |
| SSE | 1 | 8 ms |
| WebSocket | 1 | 8 ms |

Short polling's latency averages **half the poll interval** — that's not a bug to
tune away, it's the definition of the technique.

## Wire trace

The middle panel logs every message crossing the boundary. The interesting run is
after a drop:

```
7.0s ⚡ you   POST /api/drop — force-closing every open stream
7.0s ✕ sse   stream dropped — browser will reconnect itself
7.0s ✕ ws    socket closed — retrying in 995ms (backoff + jitter)
7.1s ↑ sse   reconnecting — Last-Event-ID: 6      ← the browser did this
8.0s ↑ ws    GET /api/ws — Upgrade: websocket      ← our code had to do this
8.0s ↓ ws    101 Switching Protocols — socket open
8.0s ↑ ws    {resume_from: 6} — my own bookmark
```

SSE's reconnect and resume are free — the browser sends `Last-Event-ID` with no
help from us. WebSocket's are ~10 lines of backoff, jitter, and a hand-rolled
resume protocol. That gap is the real cost of WebSocket, not the handshake.

## How the server tells them apart

```
WebSocket    Upgrade: websocket          → 101, protocol changes
SSE          Accept: text/event-stream   → 200, response never ends
long polling (nothing)                   → 200, response is just slow
```

Long polling has **no protocol marker at all**. It's an ordinary GET the server
chooses to sit on. That's why it traverses every proxy — and why every proxy
tries to time it out.

## Notes

- `X-Accel-Buffering: no` on the SSE response. Without it nginx buffers the
  stream and the client sees nothing until it ends.
- The server uses a small `Bus` of futures rather than `asyncio.Condition`.
  A `Condition()` built at import time binds to whatever loop exists then, and
  every `await` later fails with *"Future attached to a different loop"* on
  Python 3.9.
- `/api/drop` fakes a deploy by force-closing every open stream, so you can test
  recovery without restarting the process.

## Deliberate gaps

- Single process — no pub/sub backplane, so this wouldn't work across two servers
- No auth
- Long-poll timeout fixed at 30s, no per-client tuning
- Held-connection count is exposed at `/api/stats` but not charted — that's the
  number that actually takes servers down at scale

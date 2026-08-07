"""One event source, four delivery mechanisms, side by side.

  GET  /api/short?since=N   answers immediately, even with nothing new
  GET  /api/long?since=N    holds the request until there is news
  GET  /api/sse?since=N     one connection, many messages
  GET  /api/ws              bidirectional socket
  POST /api/send            publish a message
  POST /api/drop            force-close every open stream (fake a restart)
"""
import asyncio
import json
import random
import time
from aiohttp import web, WSMsgType

LONG_POLL_TIMEOUT = 30
STREAM_TTL = 20          # recycle SSE streams; short so it is watchable
STREAM_TTL_JITTER = 8    # never let every client recycle at the same instant
PORT = 8080

messages = []


class Bus:
    """Wake-up broadcast. Futures are created inside the running loop, so this
    avoids asyncio.Condition's import-time loop binding on Python 3.9."""

    def __init__(self):
        self._waiters = []

    def fire(self):
        for f in self._waiters:
            if not f.done():
                f.set_result(True)
        self._waiters.clear()

    async def wait(self, timeout=None):
        f = asyncio.get_event_loop().create_future()
        self._waiters.append(f)
        try:
            await asyncio.wait_for(f, timeout)
            return True
        except asyncio.TimeoutError:
            return False
        finally:
            if f in self._waiters:
                self._waiters.remove(f)


bus = Bus()

sse_clients = set()
ws_clients = set()
long_waiters = 0

hits = {"short": 0, "long": 0, "sse": 0, "ws": 0}


def after(since):
    return [m for m in messages if m["id"] > since]


async def publish(text):
    msg = {"id": len(messages) + 1, "text": text, "sent_at": time.time() * 1000}
    messages.append(msg)
    bus.fire()
    for ws in list(ws_clients):
        try:
            await ws.send_json({"messages": [msg], "cursor": msg["id"]})
        except Exception:
            pass
    return msg


# --- short polling: reply now, news or not -----------------------------------

async def short_poll(request):
    hits["short"] += 1
    since = int(request.query.get("since", 0))
    return web.json_response({"messages": after(since), "cursor": len(messages)})


# --- long polling: hold the response ------------------------------------------

async def long_poll(request):
    global long_waiters
    hits["long"] += 1
    since = int(request.query.get("since", 0))
    wait = min(int(request.query.get("wait", LONG_POLL_TIMEOUT)), 60)
    deadline = time.time() + wait
    long_waiters += 1
    try:
        while len(messages) <= since:
            remaining = deadline - time.time()
            if remaining <= 0:
                break
            if not await bus.wait(remaining):
                break
        return web.json_response({"messages": after(since), "cursor": len(messages)})
    finally:
        long_waiters -= 1


# --- SSE: one connection, many messages ---------------------------------------

async def sse(request):
    if not request.query.get("raw"):        # the raw viewer taps the same stream
        hits["sse"] += 1                    # without skewing the request count
    lei = request.headers.get("Last-Event-ID")
    print(f"[sse] open  Last-Event-ID={lei!r}  raw={bool(request.query.get('raw'))}", flush=True)
    since = int(lei if lei is not None else request.query.get("since", 0))

    resp = web.StreamResponse(headers={
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",     # nginx would otherwise buffer this away
    })
    await resp.prepare(request)
    sse_clients.add(resp)
    try:
        deadline = time.time() + STREAM_TTL + random.uniform(0, STREAM_TTL_JITTER)
        await resp.write(b"retry: 2000\n\n")
        for m in after(since):
            await send_sse(resp, m)
            since = m["id"]

        while True:
            left = deadline - time.time()
            if left <= 0:
                # TTL reached. Closing is safe: the browser reopens with
                # Last-Event-ID, so nothing is missed.
                await resp.write(b": ttl reached, closing - reconnect please\n\n")
                break
            # comment line every 5s of quiet: "still here, nothing to report".
            # EventSource discards these — only a raw reader sees them.
            if not await bus.wait(min(5, left)):
                await resp.write(b": keep-alive\n\n")
                continue
            for m in after(since):
                await send_sse(resp, m)
                since = m["id"]
    except (asyncio.CancelledError, ConnectionResetError):
        pass
    finally:
        sse_clients.discard(resp)
    return resp


async def llm(request):
    """Mimic an LLM: stream a few tokens, send a sentinel, then close."""
    resp = web.StreamResponse(headers={
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache",
        "X-Accel-Buffering": "no",
    })
    await resp.prepare(request)
    for tok in ["SSE", " is", " a", " download", " that", " finishes."]:
        await resp.write(f'data: {json.dumps({"delta": tok})}\n\n'.encode())
        await asyncio.sleep(0.25)
    await resp.write(b"data: [DONE]\n\n")   # layer 1: the sentinel
    await resp.write_eof()                    # layer 2: the 0-length chunk
    return resp


async def send_sse(resp, m):
    body = f"id: {m['id']}\ndata: {json.dumps(m)}\n\n"
    await resp.write(body.encode())


# --- WebSocket ----------------------------------------------------------------

async def websocket(request):
    hits["ws"] += 1
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)
    ws_clients.add(ws)
    try:
        since = 0
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                data = json.loads(msg.data)
                since = data.get("resume_from", 0)
                pending = after(since)
                if pending:
                    await ws.send_json({"messages": pending, "cursor": len(messages)})
    except Exception:
        pass
    finally:
        ws_clients.discard(ws)
    return ws


# --- control ------------------------------------------------------------------

async def send(request):
    body = await request.json()
    msg = await publish(body.get("text", "hello"))
    return web.json_response(msg)


async def drop(request):
    """Force-close every open stream — what a deploy or crash looks like."""
    n = len(sse_clients) + len(ws_clients)
    for resp in list(sse_clients):
        try:
            await resp.write_eof()
        except Exception:
            pass
        sse_clients.discard(resp)
    for ws in list(ws_clients):
        try:
            await ws.close(code=1012, message=b"restart")
        except Exception:
            pass
        ws_clients.discard(ws)
    bus.fire()
    return web.json_response({"dropped": n})


async def stats(request):
    return web.json_response({
        "hits": hits,
        "held": {"sse": len(sse_clients), "ws": len(ws_clients), "long": long_waiters},
        "messages": len(messages),
    })


async def reset(request):
    messages.clear()
    for k in hits:
        hits[k] = 0
    return web.json_response({"ok": True})


app = web.Application()
app.add_routes([
    web.get("/api/short", short_poll),
    web.get("/api/long", long_poll),
    web.get("/api/sse", sse),
    web.get("/api/llm", llm),
    web.get("/api/ws", websocket),
    web.post("/api/send", send),
    web.post("/api/drop", drop),
    web.post("/api/reset", reset),
    web.get("/api/stats", stats),
    web.get("/", lambda r: web.FileResponse("./index.html")),
])

if __name__ == "__main__":
    print(f"http://localhost:{PORT}")
    web.run_app(app, host="localhost", port=PORT, print=None)

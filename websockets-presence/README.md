# websockets-presence

An online/offline (presence) system in ~40 lines, built to understand what a
WebSocket actually is.

## Why not just HTTP?

A server cannot call a client. Clients sit behind NAT with no public IP and no
listening port, phones suspend backgrounded apps, and IPs change on every
network switch. So presence has to be **client-initiated**: either the client
polls/heartbeats, or it opens one long-lived connection the server can push down.

This prototype does the second.

## Run it

```bash
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt

./.venv/bin/python server.py          # ws://localhost:8765
python3 -m http.server 8000           # in another shell
```

Open two tabs:

```
http://localhost:8000/?user=alice
http://localhost:8000/?user=bob
```

Alice's list updates the moment Bob connects — without Alice asking for
anything. That is the entire point of a WebSocket.

## What the code shows

1. **The server pushes.** `broadcast()` calls `ws.send()` whenever it wants.
   Impossible over plain HTTP, where the client must ask first.
2. **`online = {}` is server state.** It lives in this process's memory. That one
   line is why WebSockets are "stateful" — and why it breaks with two servers.
3. **A clean disconnect is detected instantly.** `async for _ in ws` exits, the
   `finally` block runs, everyone gets told.

## The handshake, byte by byte

```bash
./.venv/bin/python raw_handshake.py
```

Connects with a plain TCP socket and performs the upgrade by hand, so every byte
is visible.

- It starts as an **ordinary HTTP GET** — which is why WebSockets traverse
  firewalls and proxies that only expect web traffic.
- `Upgrade: websocket` + `Connection: Upgrade` request the switch.
- **`101 Switching Protocols`** means "I'm done speaking HTTP on this socket."
- `Sec-WebSocket-Accept` is `base64(sha1(client_key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`.
  That GUID is hardcoded in the spec. It is **not security** — it proves the
  server genuinely understood the upgrade, so a naive proxy echoing headers can't
  fool us into writing frames at something expecting HTTP.
- After the 101 the same TCP socket becomes a raw bidirectional frame pipe. No
  more request/response pairing.

## Break it on purpose

| Do this | Expected |
| --- | --- |
| Open two tabs as `alice`, close one | stays online, log shows `1 left` |
| Close the second | `alice went offline` |
| Open a tab, then **turn off WiFi** | ⚠️ alice stays online **forever** |

That last row is the real lesson. TCP has no idea the network vanished — nothing
was closed, so `finally` never runs. "Connection open" does **not** mean online.

Fixing it needs `ping`/`pong` control frames with a timeout, which is also
mandatory because load balancers kill idle connections (AWS ALB defaults to 60s).

## Known gaps (deliberate)

- No heartbeat → dead connections linger (see above)
- Single process → `online` dict is not shared; two servers would each see half
  the users, and would need a pub/sub backplane plus a connection registry
- No auth → the client just claims a user id in its first message
- No message delivery guarantee → anything sent while disconnected is lost

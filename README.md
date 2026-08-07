# prototypes

Small, runnable prototypes built to understand systems concepts from the inside
out. Each directory is self-contained: minimal code, no frameworks, and a README
explaining what the prototype is meant to teach.

Companion to the notes in [`system-design`](https://github.com/harshwardhan6062/system-design).

## Prototypes

| Directory | Topic | What it demonstrates |
| --- | --- | --- |
| [`websockets-presence`](./websockets-presence) | WebSockets, presence | Server push, the HTTP `Upgrade` handshake, connection state, why heartbeats are needed |
| [`polling`](./polling) | Short polling, long polling, SSE, WebSocket | All four side by side: request cost, latency, and which ones reconnect on their own |
| [`connection-pool`](./connection-pool) | Connection pooling (Go) | A blocking-queue pool with `sync.Cond` — capacity limits, context cancellation, reuse |

## Ground rules

- **Minimal.** Small enough to read top to bottom in one sitting.
- **Stdlib first.** Dependencies only where the point isn't the plumbing.
- **Break it on purpose.** Each prototype ends with a failure mode to reproduce,
  because that's where the design lesson actually lives.

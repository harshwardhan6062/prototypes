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

| [`columnar-vs-widecolumn`](./columnar-vs-widecolumn) | Columnar vs wide-column | Why cheap `ADD COLUMN` is not the wide-column model, and what a `Map` column costs |

| [`capacity-ceiling`](./capacity-ceiling) | Load, saturation, Little's Law | Where a query's throughput ceiling is, and why databases fail by running out of workers rather than getting slower |

| [`redis-read-scaling`](./redis-read-scaling) | Read replicas, saturation | Where one Redis tops out, that replicas do nothing unless the client routes to them, and what async replication costs |

## Ground rules

- **Minimal.** Small enough to read top to bottom in one sitting.
- **Stdlib first.** Dependencies only where the point isn't the plumbing.
- **Break it on purpose.** Each prototype ends with a failure mode to reproduce,
  because that's where the design lesson actually lives.

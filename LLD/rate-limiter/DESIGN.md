# Rate Limiter — LLD

Interview answer, in the order to deliver it.

```bash
java RateLimiterDemo.java   # all four strategies, the boundary bug, the race
```

Asked at Amazon SDE-2 as *"design a rate limiter"*, usually with **"implement a
specific algorithm"** as the follow-up. The extensibility question is always the
same one: *now make it work across N servers.*

## 1. Requirements (state these first, ~30 seconds)

**Functional**

```
· allow or deny a request, per caller — not globally
· several algorithms, selectable      (must be extensible)
· tell the caller when to retry, not just "no"
· some requests cost more than one unit
```

**Non-functional**

```
· thread-safe: two concurrent calls must not both take the last slot
· O(1) per decision, and bounded memory
· the limiter must not become the outage
```

## 2. The contract

The first instinct is `boolean take()`. Both halves of that are wrong.

```java
interface RateLimiter {
    Decision tryAcquire(String key, int cost);
    default Decision tryAcquire(String key) { return tryAcquire(key, 1); }
}

record Decision(boolean allowed, long remaining, long retryAfterMillis) { }
```

**`key`, because nothing is limited globally.** Limits are per user, per IP, per
API key. Without it the strategy holds *a* counter; with it, a **map** of
counters — which is what creates the eviction problem in §6.

**`Decision`, because "no" is not an answer.** The caller has to return HTTP 429,
and that needs `Retry-After` and the remaining quota. Returning `boolean` throws
away information the strategy already computed.

**`cost`, because a bulk write isn't one read.** Costs nothing to add now;
changes every call site later.

**Being limited is not an error.** It is the expected answer to the question
asked, so it belongs in the return value. An *error* is the limiter itself
failing — which only becomes possible in §5.

## 3. The strategies

Strategy pattern. Each is a class; adding one is never an edit.

| Strategy | Trade-off |
| --- | --- |
| **Fixed window** | Simplest. Broken at the seam — see §8 |
| **Sliding window log** | Exact. One timestamp per request; memory grows with traffic |
| **Sliding window counter** | Weights the previous window. The usual production compromise |
| **Token bucket** | Steady refill, allows a saved-up burst. What most APIs want |
| **Leaky bucket** | Smooths output to a constant rate. Queues instead of rejecting |

Token bucket, the one to write if asked for a specific algorithm:

```java
long elapsed = now - st.lastRefillMillis;
st.tokens = Math.min(capacity, st.tokens + elapsed * refillPerMillis);
st.lastRefillMillis = now;
if (st.tokens >= cost) { st.tokens -= cost; return Decision.allow(...); }
return Decision.deny((long) Math.ceil((cost - st.tokens) / refillPerMillis));
```

**There is no background thread refilling buckets.** Tokens are computed lazily
from elapsed time on read. A timer per key would be a timer per user.

## 4. Concurrency — the guaranteed follow-up

> *Two threads call `tryAcquire("harsh")` at the same instant with one slot left.*

This is the same probe as *"two people buying the same seat in the same
millisecond."* Read-then-write is two steps, and the gap between them is the bug:

```
thread A          thread B
read  count=4     read  count=4      <- both see room
write count=5     write count=5      <- two admitted, limit was 5
```

The whole fix is that the read and the write are **one atomic step**, and the
design puts that in exactly one place — `Store.compute`:

```java
map.compute(key, (k, state) -> { ... });   // bin lock held across the function
```

Not `synchronized` on the whole limiter: that serialises every key against every
other key, so one hot user stalls everyone. Lock per key, not per limiter.

`RateLimiter.java` demonstrates both — the safe version admits exactly the limit,
the unsafe one over-admits on every run.

## 5. Where the state lives — the extensibility answer

> *"Now make it work across 10 servers."*

An in-memory map is per-process, so ten servers means ten times the limit. The
answer is that **the rule and the storage are two different interfaces**:

```
RateLimiter   decides the rule        token bucket, fixed window, …
Store         decides where numbers live   memory today, Redis tomorrow
```

```java
interface Store<S extends KeyState> {
    Decision compute(String key, long now, Function<S, Decision> mutator);
    int evictIdleBefore(long cutoffMillis);
}
```

With that split, distributed mode is a new `Store`, not a rewrite of four
strategies. **This is the thing being tested** — same shape as *"now handle
events as well as movies."*

For a Redis store, `compute` becomes one **Lua script**, because the atomicity
requirement doesn't change when the map moves across the network — it just has to
be satisfied on the other side.

**Then say the next thing:** a network hop per request means the limiter can now
fail. Do you **fail open** (allow everything, protect availability) or **fail
closed** (deny everything, protect the backend)? Configurable — default open for
a public API, closed for an expensive backend.

## 6. Memory — one entry per caller, forever

Every key that ever arrives allocates state that is never freed. That is a memory
leak with a polite name. Each state carries `lastSeenMillis`, and idle keys are
evicted on a sweep — TTL here, LRU if the bound must be hard.

## 7. Time — don't call the clock inside the strategy

Inject it. Two reasons, and the second is the one that scores:

```java
interface Clock { long nowMillis(); }
```

- You cannot test a sliding window against a real clock without `sleep`.
- **Wall-clock time moves backwards.** An NTP correction makes
  `System.currentTimeMillis()` jump, and window arithmetic goes negative.
  `System.nanoTime()` is monotonic. Use monotonic time for durations, always.

## 8. The failure to reproduce — fixed window's boundary burst

The reason fixed window is the wrong default, made visible:

```
limit = 5 per second

  0.98s  ██████  5 allowed   <- end of window 1
  1.02s  ██████  5 allowed   <- start of window 2, counter reset

  10 requests in 40ms, under a 5-per-second limit
```

Both windows are individually correct. The limit is still violated **2×** across
the seam. Sliding window counter fixes it by weighting the previous window;
`RateLimiterDemo.java` runs the same attack against both — fixed window admits
all 10, the sliding counter admits 5.

## 9. Edge cases — raise them before being asked

```
· cost > capacity            -> reject immediately, never satisfiable
· clock jumps backwards      -> monotonic clock; clamp negative elapsed to 0
· first request for a key    -> bucket starts full, not empty
· unbounded key cardinality  -> attacker rotates IPs; cap keys + evict
· limiter itself is down     -> fail open or closed, decided per deployment
· multiple limits at once    -> 10/sec AND 1000/day: compose, all must allow
· burst vs smooth            -> token bucket permits bursts by design; say so
```

## 10. Layering

```
caller / middleware     "may this request proceed?"
   |
RateLimiter             the rule            (swappable strategy)
   |
Store                   where state lives   (swappable backend)
   |
Clock                   monotonic time      (injected, fakeable)
```

Nothing above `Store` knows whether state is local or in Redis; nothing below
`RateLimiter` knows which algorithm is in use.

## The 60-second opener

> One interface, `tryAcquire(key, cost)`, returning a **Decision** — allowed,
> remaining, retry-after — because the caller has to write a 429 header and
> "false" doesn't carry that. Everything is **per key**; nothing is limited
> globally. Algorithms go behind a **Strategy** interface — token bucket by
> default, since it refills smoothly and still permits a burst. Tokens are
> computed **lazily from elapsed time**, so there's no timer per user. The
> read-modify-write is **atomic per key**, not a lock on the whole limiter, or
> two threads take the last slot. State sits behind a separate **Store**
> interface, so going multi-server is a Redis implementation rather than a
> rewrite — and at that point I'd make **fail-open vs fail-closed** a
> configuration decision. Idle keys are evicted, or it leaks one entry per
> caller forever.

## Sample output

```
── 2. FIXED WINDOW — the boundary burst (break it on purpose) ──
  t=0.980s  allowed 5 / 5     <- end of window 1
  t=1.020s  allowed 5 / 5     <- start of window 2
  10 requests admitted in 40ms under a limit of 5/sec  <- 2x the limit
```

```
── 5. CONCURRENCY — 200 threads, one key, limit 50 ───────────
  atomic  store: 50 allowed    <- exactly the limit
  unsafe  store: 200 allowed   <- read-then-write raced

── 6. EVICTION — bounded memory ──────────────────────────────
  after 1000 distinct callers: 1000 keys held
  evicted idle > 30s:          1000 removed, 1 left
```

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

// Complete rate-limiter LLD. Run:  java RateLimiterDemo.java

// ═══════════════════════════ CONTRACT ═══════════════════════════

/**
 * "No" is not an answer. The caller has to write an HTTP 429, which needs
 * Retry-After and the remaining quota — information the strategy already
 * computed and a boolean would throw away.
 */
record Decision(boolean allowed, long remaining, long retryAfterMillis) {
    static Decision allow(long remaining) { return new Decision(true, Math.max(0, remaining), 0); }
    static Decision deny(long retryAfterMillis) { return new Decision(false, 0, Math.max(0, retryAfterMillis)); }

    @Override public String toString() {
        return allowed ? "allow (remaining " + remaining + ")"
                       : "DENY  (retry in " + retryAfterMillis + "ms)";
    }
}

/**
 * Note the key: nothing is limited globally. Limits are per user, per IP, per
 * API key — which is what turns "a counter" into "a map of counters", and
 * creates the eviction problem below.
 */
interface RateLimiter {
    Decision tryAcquire(String key, int cost);
    default Decision tryAcquire(String key) { return tryAcquire(key, 1); }
}

// ═══════════════════════════ TIME ═══════════════════════════════

/**
 * Injected, never called directly inside a strategy. Two reasons: a sliding
 * window is untestable against a real clock without sleeping, and wall-clock
 * time moves BACKWARDS on an NTP correction. nanoTime is monotonic.
 */
interface Clock { long nowMillis(); }

final class SystemClock implements Clock {
    public long nowMillis() { return System.nanoTime() / 1_000_000L; }
}

final class FakeClock implements Clock {
    private long now;
    public long nowMillis() { return now; }
    void advance(long millis) { now += millis; }
}

// ═══════════════════════════ STORAGE ════════════════════════════

/** Per-key state records when it was last touched, so idle keys can be evicted. */
abstract class KeyState { long lastSeenMillis; }

/**
 * THE EXTENSIBILITY ANSWER. The strategy decides the rule; the Store decides
 * where the numbers are kept. Swap InMemoryStore for a Redis one and all four
 * strategies work across N servers unchanged — "make it distributed" becomes a
 * new implementation, not a rewrite.
 */
interface Store<S extends KeyState> {
    /** Atomic read-modify-write for ONE key. This single method is the concurrency answer. */
    Decision compute(String key, long nowMillis, Function<S, Decision> mutator);
    int size();
    int evictIdleBefore(long cutoffMillis);
}

final class InMemoryStore<S extends KeyState> implements Store<S> {
    private final ConcurrentHashMap<String, S> map = new ConcurrentHashMap<>();
    private final Supplier<S> factory;

    InMemoryStore(Supplier<S> factory) { this.factory = factory; }

    public Decision compute(String key, long nowMillis, Function<S, Decision> mutator) {
        // compute() holds the bin lock for this key across the whole function, so
        // read and write are one step. Note it locks per KEY, not per limiter —
        // a synchronized method would make one hot user stall everybody else.
        Decision[] out = new Decision[1];
        map.compute(key, (k, state) -> {
            if (state == null) state = factory.get();
            state.lastSeenMillis = nowMillis;
            out[0] = mutator.apply(state);
            return state;
        });
        return out[0];
    }

    public int size() { return map.size(); }

    public int evictIdleBefore(long cutoffMillis) {
        int before = map.size();
        map.values().removeIf(s -> s.lastSeenMillis < cutoffMillis);
        return before - map.size();
    }
}

/** Shared plumbing: a clock, a store, and the eviction sweep every strategy needs. */
abstract class KeyedLimiter<S extends KeyState> implements RateLimiter {
    protected final Clock clock;
    protected final Store<S> store;

    KeyedLimiter(Clock clock, Store<S> store) { this.clock = clock; this.store = store; }

    int trackedKeys() { return store.size(); }
    int evictIdleFor(long idleMillis) { return store.evictIdleBefore(clock.nowMillis() - idleMillis); }
}

// ═══════════════════════ TOKEN BUCKET ═══════════════════════════

final class TokenBucketState extends KeyState {
    double tokens;
    long lastRefillMillis = -1;
}

/** Steady refill, saved-up burst up to capacity. The sensible default. */
final class TokenBucket extends KeyedLimiter<TokenBucketState> {
    private final long capacity;
    private final double refillPerMillis;

    TokenBucket(long capacity, double refillPerSecond, Clock clock) {
        super(clock, new InMemoryStore<>(TokenBucketState::new));
        this.capacity = capacity;
        this.refillPerMillis = refillPerSecond / 1000.0;
    }

    public Decision tryAcquire(String key, int cost) {
        if (cost > capacity) return Decision.deny(0);          // never satisfiable
        long now = clock.nowMillis();
        return store.compute(key, now, st -> {
            if (st.lastRefillMillis < 0) { st.tokens = capacity; st.lastRefillMillis = now; }
            // Lazy refill: tokens are DERIVED from elapsed time on read. A timer
            // per key would be a timer per user.
            long elapsed = Math.max(0, now - st.lastRefillMillis);   // clamp: clock can jump
            st.tokens = Math.min(capacity, st.tokens + elapsed * refillPerMillis);
            st.lastRefillMillis = now;

            if (st.tokens >= cost) {
                st.tokens -= cost;
                return Decision.allow((long) st.tokens);
            }
            return Decision.deny((long) Math.ceil((cost - st.tokens) / refillPerMillis));
        });
    }
}

// ═══════════════════════ FIXED WINDOW ═══════════════════════════

final class FixedWindowState extends KeyState {
    long windowStartMillis = -1;
    long count;
}

/** Simplest, and wrong at the seam between windows. See demo 2. */
final class FixedWindow extends KeyedLimiter<FixedWindowState> {
    private final long limit, windowMillis;

    FixedWindow(long limit, long windowMillis, Clock clock) {
        super(clock, new InMemoryStore<>(FixedWindowState::new));
        this.limit = limit; this.windowMillis = windowMillis;
    }

    public Decision tryAcquire(String key, int cost) {
        long now = clock.nowMillis();
        long windowStart = now - Math.floorMod(now, windowMillis);
        return store.compute(key, now, st -> {
            if (st.windowStartMillis != windowStart) {         // new window: hard reset
                st.windowStartMillis = windowStart;
                st.count = 0;
            }
            if (st.count + cost <= limit) {
                st.count += cost;
                return Decision.allow(limit - st.count);
            }
            return Decision.deny(windowStart + windowMillis - now);
        });
    }
}

// ════════════════════ SLIDING WINDOW LOG ════════════════════════

final class SlidingLogState extends KeyState {
    final ArrayDeque<Long> stamps = new ArrayDeque<>();
}

/** Exact, at the cost of one timestamp per request. Memory grows with traffic. */
final class SlidingWindowLog extends KeyedLimiter<SlidingLogState> {
    private final long limit, windowMillis;

    SlidingWindowLog(long limit, long windowMillis, Clock clock) {
        super(clock, new InMemoryStore<>(SlidingLogState::new));
        this.limit = limit; this.windowMillis = windowMillis;
    }

    public Decision tryAcquire(String key, int cost) {
        long now = clock.nowMillis();
        long cutoff = now - windowMillis;
        return store.compute(key, now, st -> {
            while (!st.stamps.isEmpty() && st.stamps.peekFirst() <= cutoff) st.stamps.pollFirst();
            if (st.stamps.size() + cost <= limit) {
                for (int i = 0; i < cost; i++) st.stamps.addLast(now);
                return Decision.allow(limit - st.stamps.size());
            }
            return Decision.deny(st.stamps.peekFirst() + windowMillis - now);
        });
    }
}

// ══════════════════ SLIDING WINDOW COUNTER ══════════════════════

final class SlidingCounterState extends KeyState {
    long windowStartMillis = -1;
    long currentCount, previousCount;
}

/** Approximates the log with two counters. The usual production compromise. */
final class SlidingWindowCounter extends KeyedLimiter<SlidingCounterState> {
    private final long limit, windowMillis;

    SlidingWindowCounter(long limit, long windowMillis, Clock clock) {
        super(clock, new InMemoryStore<>(SlidingCounterState::new));
        this.limit = limit; this.windowMillis = windowMillis;
    }

    public Decision tryAcquire(String key, int cost) {
        long now = clock.nowMillis();
        long windowStart = now - Math.floorMod(now, windowMillis);
        return store.compute(key, now, st -> {
            if (st.windowStartMillis < 0) st.windowStartMillis = windowStart;
            long moved = (windowStart - st.windowStartMillis) / windowMillis;
            if (moved == 1) { st.previousCount = st.currentCount; st.currentCount = 0; }
            else if (moved > 1) { st.previousCount = 0; st.currentCount = 0; }
            st.windowStartMillis = windowStart;

            // Weight the previous window by how much of it is still in view.
            double elapsedFraction = (now - windowStart) / (double) windowMillis;
            double estimate = st.previousCount * (1 - elapsedFraction) + st.currentCount;

            if (estimate + cost <= limit) {
                st.currentCount += cost;
                return Decision.allow((long) (limit - estimate - cost));
            }
            return Decision.deny(windowStart + windowMillis - now);
        });
    }
}

// ═══════════════════ THE BROKEN ONE (on purpose) ════════════════

/**
 * Deliberately wrong: the read and the write are two steps. The race is real —
 * the pause between them only makes it reproducible on every run instead of one
 * run in a thousand. The map is concurrent, so the ONLY defect on show is
 * read-then-write.
 */
final class UnsafeFixedWindow implements RateLimiter {
    private final ConcurrentHashMap<String, long[]> counts = new ConcurrentHashMap<>();
    private final long limit;

    UnsafeFixedWindow(long limit) { this.limit = limit; }

    public Decision tryAcquire(String key, int cost) {
        long[] cell = counts.computeIfAbsent(key, k -> new long[1]);
        long seen = cell[0];                                    // ---- read
        Thread.onSpinWait();
        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (seen + cost <= limit) {
            cell[0] = seen + cost;                              // ---- write
            return Decision.allow(limit - cell[0]);
        }
        return Decision.deny(0);
    }
}

// ═══════════════════════════ DEMO ═══════════════════════════════

public class RateLimiterDemo {

    static void section(String title) {
        System.out.println("\n── " + title + " " + "─".repeat(Math.max(2, 58 - title.length())));
    }

    public static void main(String[] args) throws Exception {

        // ---- 1. token bucket: burst, drain, lazy refill -------------------
        section("1. TOKEN BUCKET — capacity 5, refill 2/sec");
        FakeClock clock = new FakeClock();
        TokenBucket bucket = new TokenBucket(5, 2, clock);

        System.out.print("  burst of 6 on a fresh key: ");
        for (int i = 0; i < 6; i++) System.out.print(bucket.tryAcquire("alice").allowed() ? "✓" : "✗");
        System.out.println("   <- starts FULL, 6th denied");

        System.out.println("  denied now:   " + bucket.tryAcquire("alice"));
        clock.advance(1000);
        System.out.println("  after 1000ms: " + bucket.tryAcquire("alice") + "   <- 2 refilled, no timer involved");

        System.out.println("  cost=3 bulk:  " + bucket.tryAcquire("alice", 3));
        System.out.println("  cost=99:      " + bucket.tryAcquire("alice", 99) + "   <- above capacity, never satisfiable");
        System.out.println("  other key:    " + bucket.tryAcquire("bob") + "   <- per-key, not global");

        // ---- 2. fixed window: the boundary burst -------------------------
        section("2. FIXED WINDOW — the boundary burst (break it on purpose)");
        FakeClock fw = new FakeClock();
        FixedWindow fixed = new FixedWindow(5, 1000, fw);

        fw.advance(980);                                        // 0.980s — end of window 1
        int first = 0;
        for (int i = 0; i < 5; i++) if (fixed.tryAcquire("carol").allowed()) first++;
        System.out.println("  t=0.980s  allowed " + first + " / 5     <- end of window 1");

        fw.advance(40);                                         // 1.020s — window 2
        int second = 0;
        for (int i = 0; i < 5; i++) if (fixed.tryAcquire("carol").allowed()) second++;
        System.out.println("  t=1.020s  allowed " + second + " / 5     <- start of window 2, counter reset");
        System.out.println("  " + (first + second) + " requests admitted in 40ms under a limit of 5/sec  <- 2x the limit");

        // ---- 3. sliding window counter survives the same attack ----------
        section("3. SLIDING WINDOW COUNTER — same attack, weighted");
        FakeClock sw = new FakeClock();
        SlidingWindowCounter sliding = new SlidingWindowCounter(5, 1000, sw);

        sw.advance(980);
        int a = 0;
        for (int i = 0; i < 5; i++) if (sliding.tryAcquire("carol").allowed()) a++;
        sw.advance(40);
        int b = 0;
        for (int i = 0; i < 5; i++) if (sliding.tryAcquire("carol").allowed()) b++;
        System.out.println("  " + a + " then " + b + " = " + (a + b) + " admitted  <- previous window still counts for 96% of its weight");

        // ---- 4. sliding window log is exact ------------------------------
        section("4. SLIDING WINDOW LOG — exact, one stamp per request");
        FakeClock lg = new FakeClock();
        SlidingWindowLog log = new SlidingWindowLog(3, 1000, lg);
        System.out.print("  3 then 1 more: ");
        for (int i = 0; i < 4; i++) System.out.print(log.tryAcquire("dave").allowed() ? "✓" : "✗");
        lg.advance(1001);
        System.out.println("   after window passes: " + (log.tryAcquire("dave").allowed() ? "✓" : "✗"));

        // ---- 5. concurrency: the whole point -----------------------------
        section("5. CONCURRENCY — 200 threads, one key, limit 50");
        System.out.println("  atomic  store: " + hammer(new FixedWindow(50, 60_000, new SystemClock())) + " allowed    <- exactly the limit");
        System.out.println("  unsafe  store: " + hammer(new UnsafeFixedWindow(50)) + " allowed   <- read-then-write raced");

        // ---- 6. eviction: one entry per caller, forever ------------------
        section("6. EVICTION — bounded memory");
        FakeClock ev = new FakeClock();
        TokenBucket many = new TokenBucket(10, 1, ev);
        for (int i = 0; i < 1000; i++) many.tryAcquire("user-" + i);
        System.out.println("  after 1000 distinct callers: " + many.trackedKeys() + " keys held");
        ev.advance(60_000);
        many.tryAcquire("user-still-active");
        System.out.println("  evicted idle > 30s:          " + many.evictIdleFor(30_000) + " removed, " + many.trackedKeys() + " left");
    }

    /** 200 threads released at once against a single key. */
    static int hammer(RateLimiter limiter) throws Exception {
        int threads = 200;
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var allowed = new java.util.concurrent.atomic.AtomicInteger();
        var done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (limiter.tryAcquire("hot-key").allowed()) allowed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();
        return allowed.get();
    }
}

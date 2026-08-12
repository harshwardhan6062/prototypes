"""Find a query's capacity ceiling, then watch Little's Law take over.

    python sweep.py                     # default query
    python sweep.py "SELECT ..."        # your own
"""
import random, sys, threading, time
import pymysql

DSN = dict(host="127.0.0.1", user="root", password="root", db="proto")
QUERY = sys.argv[1] if len(sys.argv) > 1 else \
    "SELECT id, amount, status FROM orders WHERE user_id=%s"
PARAM = lambda: (random.randrange(500000),)
LEVELS = [1, 2, 5, 10, 20, 40, 80, 150]
DURATION = 5


def run(threads):
    lat, errs, stop = [], [0], [False]
    lock = threading.Lock()

    def worker():
        mine, e = [], 0
        try:
            c = pymysql.connect(connect_timeout=5, **DSN)
        except Exception:
            with lock: errs[0] += 1
            return
        cur = c.cursor()
        while not stop[0]:
            t = time.perf_counter()
            try:
                cur.execute(QUERY, PARAM())
                cur.fetchall()
                mine.append((time.perf_counter() - t) * 1000)
            except Exception:
                e += 1
        with lock:
            lat.extend(mine); errs[0] += e
        c.close()

    ts = [threading.Thread(target=worker, daemon=True) for _ in range(threads)]
    t0 = time.perf_counter()
    for t in ts: t.start()
    time.sleep(DURATION)
    stop[0] = True
    for t in ts: t.join(10)

    el = time.perf_counter() - t0
    lat.sort()
    p = lambda q: lat[int(len(lat) * q)] if lat else float("nan")
    return len(lat) / el, p(.50), p(.99), errs[0]


print(f"{'threads':>8} {'QPS':>10} {'p50 ms':>9} {'p99 ms':>10} {'errors':>8} {'Little pred':>12}")
for n in LEVELS:
    qps, p50, p99, errs = run(n)
    predicted = n / qps * 1000 if qps else float("nan")
    print(f"{n:>8} {qps:>10,.0f} {p50:>9.2f} {p99:>10.2f} {errs:>8} {predicted:>11.2f}ms")
    time.sleep(1)

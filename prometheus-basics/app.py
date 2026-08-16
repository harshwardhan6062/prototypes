"""A metrics endpoint written by hand, with no Prometheus library.

The point of this file: a Prometheus "exporter" is not a protocol, a client, or
a connection. It is one HTTP endpoint that returns plain text. Prometheus GETs
that text every few seconds and stores the numbers. That is the entire system.

Everything below is stdlib. Run it, then open http://localhost:8000/metrics and
read the output — it is short enough to understand completely.

    python3 app.py
"""

import random
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ── the state Prometheus will read ──────────────────────────────────────────
#
# Prometheus has no idea these are Python variables. It only ever sees the text
# we render in /metrics. Where the numbers come from is entirely our business.

_lock = threading.Lock()

# COUNTER — only ever goes up. You never read a counter directly; you ask
# Prometheus for its rate() over time. Restarting the process resets it to 0,
# and rate() handles that reset for you.
requests_total: dict[tuple[str, str], int] = {}  # (path, status) -> count

# GAUGE — goes up and down. A snapshot of "right now".
in_flight = 0

# HISTOGRAM — latency, bucketed. This is the one worth studying, because the
# on-the-wire format is not obvious: buckets are CUMULATIVE (each bucket counts
# everything at or below its upper bound), and you also publish _sum and _count.
BUCKETS = [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0]
duration_buckets: dict[float, int] = {b: 0 for b in BUCKETS}
duration_inf = 0        # the +Inf bucket: everything, including the slowest
duration_sum = 0.0
duration_count = 0


def observe_duration(seconds: float) -> None:
    """Record one latency sample into the histogram."""
    global duration_inf, duration_sum, duration_count
    with _lock:
        for b in BUCKETS:
            if seconds <= b:
                duration_buckets[b] += 1   # cumulative: falls into this and every larger bucket
        duration_inf += 1
        duration_sum += seconds
        duration_count += 1


def count_request(path: str, status: str) -> None:
    with _lock:
        key = (path, status)
        requests_total[key] = requests_total.get(key, 0) + 1


# ── rendering the text Prometheus scrapes ───────────────────────────────────


def render_metrics() -> str:
    """Produce the exposition format. This is the whole protocol.

    Shape of one line:

        metric_name{label="value",label2="value2"} 42

    # HELP and # TYPE are optional metadata. Prometheus stores them but the
    # numbers work fine without them.
    """
    out: list[str] = []

    out.append("# HELP app_requests_total Requests handled, by path and status.")
    out.append("# TYPE app_requests_total counter")
    with _lock:
        for (path, status), n in sorted(requests_total.items()):
            # Labels are what let you slice later: sum by (status) (...)
            out.append(f'app_requests_total{{path="{path}",status="{status}"}} {n}')

    out.append("# HELP app_in_flight_requests Requests being handled right now.")
    out.append("# TYPE app_in_flight_requests gauge")
    out.append(f"app_in_flight_requests {in_flight}")

    out.append("# HELP app_request_duration_seconds Request latency.")
    out.append("# TYPE app_request_duration_seconds histogram")
    with _lock:
        # Note: cumulative. bucket le="0.05" includes everything in le="0.01".
        for b in BUCKETS:
            out.append(f'app_request_duration_seconds_bucket{{le="{b}"}} {duration_buckets[b]}')
        out.append(f'app_request_duration_seconds_bucket{{le="+Inf"}} {duration_inf}')
        out.append(f"app_request_duration_seconds_sum {duration_sum}")
        out.append(f"app_request_duration_seconds_count {duration_count}")

    return "\n".join(out) + "\n"


# ── the server ──────────────────────────────────────────────────────────────


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        global in_flight

        # /metrics is deliberately NOT counted or timed. Scrapes are not user
        # traffic, and counting them makes every graph wrong.
        if self.path == "/metrics":
            body = render_metrics().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        with _lock:
            in_flight += 1
        start = time.perf_counter()
        try:
            if self.path.startswith("/work"):
                # Pretend to do something, sometimes slowly.
                delay = random.choice([0.002, 0.005, 0.02, 0.05, 0.4])
                time.sleep(delay)
                status = "500" if random.random() < 0.1 else "200"
                path = "/work"
            else:
                status, path = "200", "/"

            body = f"{status} {path}\n".encode()
            self.send_response(int(status))
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        finally:
            observe_duration(time.perf_counter() - start)
            count_request(path, status)
            with _lock:
                in_flight -= 1

    def log_message(self, *args) -> None:
        pass  # quiet


def traffic() -> None:
    """Generate a little load so the graphs are not flat."""
    import urllib.request

    time.sleep(1)
    while True:
        try:
            urllib.request.urlopen("http://127.0.0.1:8000/work", timeout=5).read()
        except Exception:
            pass
        time.sleep(random.uniform(0.05, 0.3))


if __name__ == "__main__":
    threading.Thread(target=traffic, daemon=True).start()
    print("app      http://localhost:8000/work")
    print("metrics  http://localhost:8000/metrics")
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()

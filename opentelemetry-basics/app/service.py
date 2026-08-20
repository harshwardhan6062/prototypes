"""One file, run twice as two different services, to produce a real distributed trace.

    ROLE=gateway    →  POST /order   calls the inventory service, then "charges a card"
    ROLE=inventory  →  GET  /check   pretends to look up stock, sometimes fails

Read this file for three things:

1. THE ZERO-CODE PART. Flask and `requests` are traced automatically because the
   process is launched with `opentelemetry-instrument`. There is no tracing code
   in the request handlers. The trace id is put into an HTTP header on the way
   out and read back out on the way in, by the instrumentation, not by us.

2. THE MANUAL PART. `tracer.start_as_current_span` is how you add your own spans
   for work the libraries cannot see — a calculation, a cache lookup, a business
   step you want on the timeline.

3. THE METRICS PART. Same SDK, same exporter, different signal. This is the
   argument for OpenTelemetry: one instrumentation, many kinds of telemetry,
   and the backend is a config file rather than a code change.
"""

import os
import random
import time

import requests
from flask import Flask, jsonify
from opentelemetry import metrics, trace

ROLE = os.getenv("ROLE", "gateway")
INVENTORY_URL = os.getenv("INVENTORY_URL", "http://inventory:5000")

app = Flask(__name__)

# These come from the SDK that `opentelemetry-instrument` set up for us.
tracer = trace.get_tracer("shop")
meter = metrics.get_meter("shop")

# Metrics, defined once. Note they look nothing like Prometheus client
# libraries — this is the OTel API, and the Collector translates it later.
orders_counter = meter.create_counter(
    "orders_placed", unit="1", description="Orders accepted or rejected."
)
order_value = meter.create_histogram(
    "order_value_rupees", unit="INR", description="Value of accepted orders."
)


@app.route("/order", methods=["GET", "POST"])
def order():
    """Gateway: call inventory, then charge. Two network hops, one trace."""
    item = random.choice(["laptop", "phone", "keyboard"])

    # No tracing code here — the `requests` instrumentation adds a span for this
    # call AND injects the `traceparent` header so the inventory service can
    # attach its spans to the same trace.
    try:
        r = requests.get(f"{INVENTORY_URL}/check", params={"item": item}, timeout=3)
        in_stock = r.status_code == 200 and r.json().get("in_stock", False)
    except requests.RequestException as e:
        # Recording the exception puts a red marker on the span in Jaeger.
        trace.get_current_span().record_exception(e)
        orders_counter.add(1, {"outcome": "inventory_unreachable", "item": item})
        return jsonify(error="inventory unreachable"), 503

    if not in_stock:
        orders_counter.add(1, {"outcome": "out_of_stock", "item": item})
        return jsonify(status="out_of_stock", item=item), 409

    # A MANUAL span. The libraries know nothing about "charging a card", so if we
    # want it on the timeline we have to say so. This is the whole reason the
    # manual API exists.
    with tracer.start_as_current_span("charge_card") as span:
        amount = random.randint(500, 50000)
        # Attributes are searchable in Jaeger — this is how you find "the slow
        # request for THIS order", which a metric can never tell you.
        span.set_attribute("payment.amount_inr", amount)
        span.set_attribute("payment.provider", "razorpay")
        time.sleep(random.choice([0.01, 0.03, 0.25]))  # sometimes slow on purpose

    orders_counter.add(1, {"outcome": "placed", "item": item})
    order_value.record(amount, {"item": item})
    return jsonify(status="placed", item=item, amount_inr=amount)


@app.route("/check")
def check():
    """Inventory: its spans automatically become children of the gateway's."""
    item = request_arg("item")

    with tracer.start_as_current_span("stock_lookup") as span:
        span.set_attribute("inventory.item", item)
        time.sleep(random.choice([0.005, 0.02, 0.12]))
        in_stock = random.random() > 0.2
        span.set_attribute("inventory.in_stock", in_stock)

    if random.random() < 0.05:
        return jsonify(error="inventory db timeout"), 500
    return jsonify(item=item, in_stock=in_stock)


def request_arg(name: str) -> str:
    from flask import request

    return request.args.get(name, "unknown")


@app.route("/health")
def health():
    return "ok"


if __name__ == "__main__":
    print(f"starting as ROLE={ROLE}")
    app.run(host="0.0.0.0", port=5000, threaded=True)

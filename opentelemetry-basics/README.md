# opentelemetry-basics

Two services, one trace. Shows the thing Prometheus cannot do: given a slow
request, say *which stage* was slow.

## Run

```bash
docker compose up -d --build
open http://localhost:16686        # Jaeger — pick service "gateway", Find Traces
open http://localhost:9096         # Prometheus
```

A load container calls `/order` continuously, so there is always something to see.

## What you get

```
TRACE 769105043e329066  total 380.8 ms

  gateway    GET /order                     0.0ms + 380.8ms
  gateway      GET                          0.4ms + 124.9ms
  inventory    GET /check                   1.0ms + 122.5ms
  inventory      stock_lookup               1.3ms + 121.9ms
                 inventory.item = laptop
  gateway      charge_card                125.6ms + 254.1ms
                 payment.amount_inr = 45772
```

380 ms, and you can see exactly where it went: 122 ms in inventory, 254 ms
charging the card. A metric would only have told you p95 was 380 ms.

## The three things worth reading the code for

**1 · Most of it is zero-code.** There is no tracing in the request handlers.
`opentelemetry-instrument` wraps the process, patches Flask and `requests`, and
does the rest — including putting the trace id in a `traceparent` header on the
way out and reading it back in on the other side. That header is why
`inventory`'s spans nest under `gateway`'s.

**2 · Manual spans are for what libraries cannot see.** `charge_card` is a
`tracer.start_as_current_span` block, because no library knows that charging a
card is a step. Attributes on it (`payment.amount_inr`) are searchable in Jaeger,
which is how you find *this* slow request rather than a percentile.

**3 · The backend is configuration, not code.** The app names no vendor. Three
env vars point it at the Collector, and `otel-collector.yaml` decides where data
lands. Swapping Jaeger for Datadog is an edit to that file.

## The pipeline, and the direction flip

```
apps ──PUSH via OTLP──→ Collector ──→ Jaeger        (traces)
                                  └──→ /metrics :8889
                                            ↑
                                    Prometheus PULLS
```

Apps push; Prometheus pulls. The Collector is the adapter between the two
models — which is a large part of why it exists.

## Gotcha: OTel renames your metrics

`create_histogram("order_value_rupees", unit="INR")` arrives in Prometheus as
**`order_value_rupees_INR`** — the unit is appended to the name. Counters get
`_total`. Check `curl localhost:8889/metrics` before writing PromQL against
names you assumed.

## Verified queries

```promql
sum by (outcome) (rate(orders_placed_total[2m]))
histogram_quantile(0.95, sum by (le) (rate(order_value_rupees_INR_bucket[2m])))
```

```
outcome=placed        1.426 /sec
outcome=out_of_stock  0.426 /sec
```

## Break it on purpose

**Stop inventory** — `docker compose stop inventory`. The gateway span gets a red
error marker with the exception recorded on it, and you can see in the trace that
the failure was the *call*, not the gateway itself. That distinction is invisible
in metrics.

**Look at a fast trace next to a slow one.** Same shape, and the difference is
entirely one span. That is the whole argument for tracing.

## Note

Python 3.12 dropped bundled setuptools, and `opentelemetry-instrument` still
imports `pkg_resources`. Without `setuptools` in requirements the container
exits immediately with `ModuleNotFoundError`.

```bash
docker compose down
```

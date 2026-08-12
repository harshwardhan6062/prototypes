# capacity-ceiling

Databases do not get gradually slower under load. They hit a throughput ceiling,
and past it every extra client only adds queueing. This measures where that
ceiling is and shows Little's Law predicting the latency beyond it.

## Run

```bash
docker run -d --name proto-mysql -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=proto -p 3306:3306 mysql:8.4 --max-connections=50
docker exec -i proto-mysql mysql -uroot -proot proto < setup.sql   # ~600 MB
pip install pymysql
python sweep.py
```

## Result

2M rows, indexed point lookup, `max_connections=50`:

```
threads      QPS    p50 ms   p99 ms   errors
      1    2,001      0.47     0.97        0
      2    3,790      0.50     0.99        0
      5    6,281      0.73     1.82        0   <- ceiling
     10    5,823      1.57     3.92        0
     20    5,761      3.14     8.52        0
     40    5,757      6.16    18.27        0
     80    5,626      7.98    24.12       29   <- connections exhausted
    150    5,439      8.15    26.48       99
```

Throughput stops improving after 5 concurrent clients. From there, 8x the
concurrency buys 8.4x the latency and no extra work done.

## Little's Law is not a rule of thumb

```
latency = concurrency / throughput

 20 threads:  20 / 5,761 = 3.47 ms   measured 3.14
 40 threads:  40 / 5,757 = 6.95 ms   measured 6.16
150 threads:  capped at 50 connections
              50 / 5,439 = 9.19 ms   measured 8.15
```

## When it "stops responding"

Never from slowness. Two hard walls:

1. **connections run out** - errors began at 80 threads against 50 connections
2. **client timeout < current latency** - every call fails while the server is fine

At 150 threads the database was still serving 5,439 QPS. Perfectly healthy, and
a third of clients could not get in. From the application's side that is an
outage.

## Two things this disproved

**"A buffer pool miss is ~1000x slower."** Folklore from spinning disks. Measured
on NVMe with 601 MB of data:

| buffer pool | QPS |
| --- | --- |
| 1 GB (fits) | 6,817 |
| 128 MB | 6,110 |
| 128 MB + `O_DIRECT` | 6,312 |

**`Innodb_buffer_pool_reads` does not mean "disk read."** It reported a 13% miss
rate, but those reads were served from the OS page cache, still in RAM. Only
`innodb_flush_method=O_DIRECT` makes that counter mean what people assume - and
even then, NVMe absorbed it.

RAM does not create a latency cliff. It sets the **height of the ceiling**.
The collapse happens when traffic crosses the ceiling, wherever it happens to be.

## What to monitor

```
don't alert on latency          symptom, and it lags
alert on QPS flat while concurrency climbs
alert on connection pool utilisation      <- the real early warning
```

Run this sweep against your own hot query. The number worth knowing is
"what QPS does this cap out at" - everything else follows from it.

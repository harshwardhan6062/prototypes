# redis-read-scaling

Adding read replicas is the standard answer to "too many reads." This runs that
experiment in three steps: saturate one Redis, add a replica, saturate it again,
add two more.

Two things it is really here to teach, because both surprise people:

1. **Replicas do nothing on their own.** Redis does not route reads to them. The
   *client* has to, and most clients do not by default. Set
   `READ_FROM_PRIMARY=true` in `docker-compose.yml` and you can watch replicas
   sit completely idle while the primary drowns.
2. **Replication is async.** Write to the primary, read from a replica, and you
   can get the old value. `./watch.sh stale` shows it happening.

Every Redis node is capped at **0.25 CPU** on purpose. Uncapped, one Redis does
100k+ reads/sec and you would break the load generator long before Redis. The
cap makes the ceiling low, reachable, and obvious.

## Setup

```bash
docker compose up -d --build
curl -X GET localhost:8090/seed          # 10k keys, ~1s
open http://localhost:3001               # Grafana, dashboard "Redis read scaling"
```

Then in separate panes:

```bash
./watch.sh pool      # what the app believes its read pool is
./watch.sh stats     # CPU per container — watch it pin at 25%
```

## The three steps

Each step is one command. Read the numbers during the hold phase.

### Step 1 — one primary, no replicas

```bash
docker compose up -d --scale redis-replica=0
docker run --rm -i --network redis-read-scaling_lab -e MAX_RPS=6000 \
  grafana/k6 run - < load/read-test.js
```

`./watch.sh pool` shows `"replica_count": 0` and reads going to the primary.
Push the rate until errors appear and p95 climbs. The primary's CPU sits pinned
at its 0.25 cap — that is the ceiling.

### Step 2 — add one replica

```bash
docker compose up -d --scale redis-replica=1
```

Within ~3 seconds `./watch.sh pool` picks it up and the app moves **all** reads
onto it. Rerun the same load: errors should go to zero.

Then push harder until it breaks again:

```bash
docker run --rm -i --network redis-read-scaling_lab -e MAX_RPS=12000 \
  grafana/k6 run - < load/read-test.js
```

### Step 3 — scale to three replicas

```bash
docker compose up -d --scale redis-replica=3
```

Rerun the `MAX_RPS=12000` load. The "Reads/sec per Redis node" panel splits
three ways and the error rate drops.

## What to look at

| Where | What it shows |
| --- | --- |
| Grafana → **Reads/sec per Redis node** | Load moving off the primary onto replicas. The clearest single view |
| Grafana → **Error rate** / **p95** | The failure appearing and then going away |
| Grafana → **Redis CPU per node** | A node pinned at 0.25 is saturated |
| `./watch.sh pool` | Replicas joining the pool live, no restart |
| `./watch.sh repl` | The primary's own `connected_slaves` count |
| k6 terminal summary | Authoritative error rate and latency percentiles |

## Break it on purpose

**Replicas that change nothing.** Set `READ_FROM_PRIMARY: "true"` in
`docker-compose.yml`, `docker compose up -d app`, and rerun step 3. Three
replicas, fully attached and replicating — and the error rate is identical to
step 1, because every read still goes to the primary. This is the most common
real-world version of this mistake.

**Stale reads.** With replicas attached:

```bash
./watch.sh stale
```

Writes to the primary and immediately reads the same key from a replica. Under
load you will see `"stale": true` — the replica had not caught up yet. This is
the cost of read scaling, and why you cannot put every read on a replica.

**Kill the primary.** `docker compose stop redis-primary`. Reads keep working
(replicas serve them), writes fail immediately, and nothing promotes a replica —
plain replication has no failover. That is what Sentinel or Cluster is for.

## Notes

- The app discovers replicas by polling `INFO replication` on the primary every
  3s, which is roughly what a Sentinel-aware client does. Nothing is hardcoded,
  so `--scale` is picked up automatically.
- `redis_exporter` runs in multi-target mode and Prometheus finds nodes via
  Docker's DNS, so scaling replicas needs no config change anywhere.
- Grafana is anonymous-admin on `localhost:3001`. Prometheus is on `:9091`.

```bash
docker compose down -v      # tear it all down
```

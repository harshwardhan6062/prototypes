#!/usr/bin/env bash
# Live views of the lab. Pick one per terminal pane.
#
#   ./watch.sh pool       what the app thinks its read pool is   <- clearest
#   ./watch.sh repl       the primary's own view of its replicas
#   ./watch.sh ps         containers, appearing and disappearing
#   ./watch.sh stats      CPU per container (watch it pin at 25%)
#   ./watch.sh ops        ops/sec on the primary, one line per second
#   ./watch.sh ops-all    ops/sec on every node, side by side
#   ./watch.sh stale      write to primary, read from replica, is it stale?

set -euo pipefail
cd "$(dirname "$0")"

replicas() {
  docker compose ps --format '{{.Name}}' | grep -E 'redis-replica' || true
}

case "${1:-pool}" in
  pool)
    watch -n1 'curl -s localhost:8090/pool | python3 -m json.tool'
    ;;

  repl)
    watch -n1 "docker compose exec -T redis-primary redis-cli INFO replication \
      | grep -E 'role|connected_slaves|^slave[0-9]'"
    ;;

  ps)
    watch -n1 'docker compose ps --format "table {{.Name}}\t{{.Service}}\t{{.Status}}"'
    ;;

  stats)
    docker stats --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
    ;;

  ops)
    docker compose exec redis-primary redis-cli --stat
    ;;

  ops-all)
    # One --stat per node, interleaved. Crude but very effective: you can see
    # the primary's number fall as the replicas' numbers rise.
    echo "primary + $(replicas | wc -l | tr -d ' ') replica(s). Ctrl-C to stop."
    for c in rrs-primary $(replicas); do
      ( docker exec "$c" redis-cli --stat 2>/dev/null | sed "s/^/$(printf '%-18s' "$c")/" ) &
    done
    wait
    ;;

  stale)
    watch -n1 'curl -s localhost:8090/staleness | python3 -m json.tool'
    ;;

  *)
    grep -E '^#   ' "$0" | sed 's/^#   //'
    exit 1
    ;;
esac

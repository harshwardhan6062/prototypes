#!/usr/bin/env bash
# Columnar ADD COLUMN vs a Map "bag" vs true wide-column.
# Needs: docker run -d --name proto-ch -p 8123:8123 -e CLICKHOUSE_PASSWORD=ch clickhouse/clickhouse-server
set -e
ch() { curl -s "http://localhost:8123/?user=default&password=ch" --data-binary "$1"; }

ch "DROP TABLE IF EXISTS big"
ch "CREATE TABLE big (
      id UInt64,
      browser String,               -- a real column
      attrs Map(String, String)     -- the same value, in a bag
    ) ENGINE = MergeTree ORDER BY id"

ch "INSERT INTO big
    SELECT number,
           ['chrome','safari','firefox'][number % 3 + 1],
           map('browser', ['chrome','safari','firefox'][number % 3 + 1],
               'os','mac','region','eu','plan','pro','seats','50')
    FROM numbers(2000000)"

echo "== on disk =="
ch "SELECT column, formatReadableSize(sum(column_data_compressed_bytes)) AS on_disk
    FROM system.parts_columns WHERE table='big' AND active
      AND column IN ('browser','attrs')
    GROUP BY column ORDER BY column FORMAT PrettyCompactMonoBlock"

echo "== same predicate, two storage shapes =="
echo "real column:"; ch "SELECT count() FROM big WHERE browser='chrome' FORMAT JSON" | grep '"elapsed"'
echo "map bag:    "; ch "SELECT count() FROM big WHERE attrs['browser']='chrome' FORMAT JSON" | grep '"elapsed"'

echo "== ADD COLUMN on 2M rows =="
time ch "ALTER TABLE big ADD COLUMN country String DEFAULT 'unknown'"
ch "SELECT column, formatReadableSize(sum(column_data_compressed_bytes)) sz
    FROM system.parts_columns WHERE table='big' AND active AND column='country'
    GROUP BY column FORMAT PrettyCompactMonoBlock"
echo "(no rows above = zero bytes written; the DEFAULT is applied on read)"

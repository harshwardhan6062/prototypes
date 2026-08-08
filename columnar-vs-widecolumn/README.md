# columnar-vs-widecolumn

Three things that all look like "flexible columns" and are not the same thing.

```bash
docker run -d --name proto-ch -p 8123:8123 \
  -e CLICKHOUSE_PASSWORD=ch clickhouse/clickhouse-server
./demo.sh
```

## Measured on 2M rows

| | on disk | query time |
| --- | --- | --- |
| real column | 128 KiB | 0.0074 s |
| same value in a `Map` | 1.03 MiB | 0.0627 s |

**8× the disk, 8.5× the time, identical data.**

A `Map` is stored as two arrays per row — keys and values. Evaluating
`attrs['browser']` reads *every key and every value of every row* and searches
them. A real column is its own file, so the same predicate reads one file. You
also lose per-column compression: `browser` has 3 distinct values across 2M rows
and squeezes to 128 KiB; mixed keys and values in one array don't.

`ALTER TABLE ADD COLUMN` on those 2M rows is instant and writes **zero bytes** —
the column doesn't even appear in `system.parts_columns`. The default is applied
on read. That is genuinely a columnar superpower: columns are separate files, so
adding one touches nothing. In a row store, columns are interleaved inside each
row, so adding one can mean rewriting every row.

## The distinction that started this

Cheap `ADD COLUMN` is easy to mistake for the wide-column data model. They are
different properties:

```
cheap ADD COLUMN   columnar physical layout    ClickHouse, Parquet
bag column         Map / JSON — flexible, 8x slower
wide-column        rows genuinely have DIFFERENT columns   Cassandra, HBase
```

The test that separates the last one: **can two rows in the same table have
different columns?** ClickHouse: no — the schema is fixed at three columns no
matter what you put in the map. HBase/Bigtable: yes — column qualifiers are
arbitrary bytes, created by writing to them.

Worth noting that modern Cassandra sits between: CQL tables *do* declare a fixed
schema, and its "wideness" is really a partition holding many clustering rows.
Missing values are free (sparse), but the set of legal column names is fixed.
The genuinely ragged case is HBase/Bigtable.

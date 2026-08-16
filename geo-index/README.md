# geo-index

Quadtree and geohash answering the same question — *"which points are within R of
here?"* — over the same 1,330 points, so the work each does is directly visible.

```bash
python3 server.py        # http://localhost:8090, stdlib only
python3 geohash.py       # the encoding, on its own
```

Click either map. Points are clustered around five Bangalore neighbourhoods with a
sparse background, so density varies — which is the whole point.

## Geohash: bits interleaved, then base32

```
12.9716, 77.5946  (Bangalore)

prec  geohash      cell height   cell width
   1  t            5,009,400 m  5,009,400 m
   3  tdr            156,544 m    156,544 m
   5  tdr1v            4,892 m      4,892 m
   6  tdr1v9             611 m      1,223 m
   7  tdr1v9q            153 m        153 m
   9  tdr1v9qtj            5 m          5 m
```

Nearby points share a prefix, which is the only reason geohash is useful — a
range query becomes a string-prefix match:

```
same spot         tdr1v9qt   shares 8 chars
500 m away        tdr1vdnw   shares 5
5 km away         tdr4j9qx   shares 3
Mumbai (840 km)   te7ud2ev   shares 1
```

## The measurement

One query, radius 1500 m, from a sparse spot between clusters:

| | points tested | of 1,330 | found |
| --- | --- | --- | --- |
| brute force | 1,330 | 100% | 4 |
| **quadtree** | **7** | **1%** | 4 |
| geohash (p5) | 417 | 31% | 4 |

The quadtree pruned almost everything. Geohash had to scan nine 4.9 km cells,
two of which happened to contain dense clusters — it cannot know that in advance,
because every cell is the same size.

## Geohash's real trap

Same query in a dense area, radius 1500 m, sweeping precision:

```
precision 4  (19 km cells)    158 found  ✅   tested 1,286 of 1,330  — no benefit
precision 5  (4.9 km cells)   158 found  ✅   tested   509           — sweet spot
precision 6  (611 m cells)    126 found  ❌   MISSED 32
precision 7  (153 m cells)      8 found  ❌   MISSED 150
```

**Wrong answers, silently.** The standard "check this cell plus its 8 neighbours"
approach only works while the cell is larger than the search radius. Go finer and
the circle spills outside the 3x3 block; go coarser and you scan everything.

So geohash precision must be chosen against your query radius. There is a narrow
correct band, and being outside it either costs you the speedup or costs you
correctness.

The quadtree has no such knob. Same query, dense area: 188 points tested. Sparse
area: 4. It adapts because the structure follows the data.

## When each is actually right

**Geohash** wins on being a *string*. It needs no special index type, so it drops
into any B-tree, any key-value store, any `LIKE 'tdr1v%'`:

```sql
CREATE INDEX ON places (geohash);
SELECT * FROM places WHERE geohash LIKE 'tdr1v%';
```

Redis `GEOADD` is geohash in a sorted set. It also shards and shortens trivially.

**Quadtree** wins on adaptivity and correctness — no precision to tune, and no
cell-boundary edge cases. The cost is a real in-memory structure that must be
built, maintained, and cannot be range-scanned by a plain B-tree.

```
need a string key / existing index / sharding   -> geohash
need adaptive density and no tuning             -> quadtree
production, one machine, PostGIS available      -> R-tree (GiST), usually
```

## Gaps (deliberate)

- Distances treat degrees as flat for pruning, then verify with haversine. Fine at
  city scale, wrong near the poles.
- Quadtree is in-memory and not persisted, and insert-only — no deletes or
  rebalancing.
- Geohash query uses the 3x3 neighbourhood only; production code picks precision
  from the radius, or queries multiple precisions.
- No kNN — only radius queries. kNN needs a priority queue over nodes.

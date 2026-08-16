"""Serves the same point set three ways so the work each method does is visible:
brute force, quadtree, and geohash-prefix buckets."""
import json
import math
import random
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import geohash
from quadtree import Box, QuadTree, haversine_m

PORT = 8090
random.seed(7)

# Bangalore, with a few dense neighbourhoods and a sparse background.
CENTER = (12.9716, 77.5946)
SPAN = 0.28
CLUSTERS = [
    ("Koramangala",     12.9352, 77.6245, 0.010, 260),
    ("Indiranagar",     12.9784, 77.6408, 0.008, 220),
    ("Whitefield",      12.9698, 77.7500, 0.014, 180),
    ("Electronic City", 12.8452, 77.6602, 0.012, 150),
    ("Hebbal",          13.0358, 77.5970, 0.009, 120),
]

BOUNDS = Box(CENTER[0] - SPAN, CENTER[1] - SPAN, CENTER[0] + SPAN, CENTER[1] + SPAN)


def make_points():
    pts, pid = [], 0
    for name, lat, lon, sigma, n in CLUSTERS:
        for _ in range(n):
            p = dict(id=pid, area=name,
                     lat=random.gauss(lat, sigma), lon=random.gauss(lon, sigma))
            if BOUNDS.contains(p["lat"], p["lon"]):
                pts.append(p); pid += 1
    for _ in range(400):                                   # sparse background
        p = dict(id=pid, area="scattered",
                 lat=random.uniform(BOUNDS.lat_lo, BOUNDS.lat_hi),
                 lon=random.uniform(BOUNDS.lon_lo, BOUNDS.lon_hi))
        pts.append(p); pid += 1
    return pts


POINTS = make_points()

TREE = QuadTree(BOUNDS, capacity=4)
for p in POINTS:
    TREE.insert(p)

# geohash buckets: prefix -> points, for every precision we might use
BUCKETS = {prec: defaultdict(list) for prec in range(4, 9)}
for p in POINTS:
    for prec in BUCKETS:
        BUCKETS[prec][geohash.encode(p["lat"], p["lon"], prec)].append(p)


def query_bruteforce(lat, lon, radius_m):
    hits, tested = [], 0
    for p in POINTS:
        tested += 1
        if haversine_m(lat, lon, p["lat"], p["lon"]) <= radius_m:
            hits.append(p)
    return hits, dict(points_tested=tested)


def query_quadtree(lat, lon, radius_m):
    r_deg = radius_m / 111_320.0
    cand, stats = TREE.query_circle(lat, lon, r_deg)
    hits = [p for p in cand
            if haversine_m(lat, lon, p["lat"], p["lon"]) <= radius_m]
    return hits, stats


def query_geohash(lat, lon, radius_m, precision):
    """Look in this cell plus its 8 neighbours — the standard approach."""
    cell = geohash.encode(lat, lon, precision)
    cells = [cell] + list(geohash.neighbours(cell).values())
    bucket = BUCKETS[precision]

    cand, tested = [], 0
    for c in cells:
        for p in bucket.get(c, ()):
            tested += 1
            cand.append(p)
    hits = [p for p in cand
            if haversine_m(lat, lon, p["lat"], p["lon"]) <= radius_m]
    return hits, dict(points_tested=tested, cells_scanned=len(cells),
                      cell=cell, cells=cells)


def geohash_grid(precision):
    """Every geohash cell overlapping the map, for drawing."""
    seen, out = set(), []
    steps = 60
    for i in range(steps + 1):
        for j in range(steps + 1):
            lat = BOUNDS.lat_lo + (BOUNDS.lat_hi - BOUNDS.lat_lo) * i / steps
            lon = BOUNDS.lon_lo + (BOUNDS.lon_hi - BOUNDS.lon_lo) * j / steps
            gh = geohash.encode(lat, lon, precision)
            if gh in seen:
                continue
            seen.add(gh)
            a, b, c, d = geohash.bbox(gh)
            out.append(dict(gh=gh, lat_lo=a, lon_lo=b, lat_hi=c, lon_hi=d,
                            n=len(BUCKETS[precision].get(gh, ()))))
    return out


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}

        if u.path == "/":
            body = open("index.html", "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        elif u.path == "/api/data":
            self._json(dict(
                bounds=BOUNDS.as_dict(),
                points=POINTS,
                quadtree=TREE.boxes(),
                tree_stats=TREE.stats(),
                clusters=[dict(name=c[0], lat=c[1], lon=c[2]) for c in CLUSTERS],
            ))

        elif u.path == "/api/geohash":
            prec = int(q.get("precision", 6))
            h, w = geohash.cell_size_m(prec)
            self._json(dict(precision=prec, cells=geohash_grid(prec),
                            cell_h_m=h, cell_w_m=w))

        elif u.path == "/api/query":
            lat, lon = float(q["lat"]), float(q["lon"])
            radius = float(q.get("radius_m", 1500))
            prec = int(q.get("precision", 6))

            bf, bf_s = query_bruteforce(lat, lon, radius)
            qt, qt_s = query_quadtree(lat, lon, radius)
            gh, gh_s = query_geohash(lat, lon, radius, prec)

            self._json(dict(
                total_points=len(POINTS),
                truth=len(bf),
                bruteforce=dict(found=len(bf), **bf_s),
                quadtree=dict(found=len(qt), ids=[p["id"] for p in qt], **qt_s),
                geohash=dict(found=len(gh), ids=[p["id"] for p in gh], **gh_s),
            ))
        else:
            self.send_error(404)


if __name__ == "__main__":
    print(f"points     : {len(POINTS)}")
    print(f"quadtree   : {TREE.stats()}")
    print(f"http://localhost:{PORT}")
    ThreadingHTTPServer(("localhost", PORT), Handler).serve_forever()

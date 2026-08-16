"""Point quadtree. A box splits into four when it holds too many points, so
the structure gets fine where the data is dense and stays coarse where it isn't.
That adaptivity is the whole difference from a fixed grid like geohash.
"""
import math


class Box:
    __slots__ = ("lat_lo", "lon_lo", "lat_hi", "lon_hi")

    def __init__(self, lat_lo, lon_lo, lat_hi, lon_hi):
        self.lat_lo, self.lon_lo = lat_lo, lon_lo
        self.lat_hi, self.lon_hi = lat_hi, lon_hi

    def contains(self, lat, lon):
        return self.lat_lo <= lat < self.lat_hi and self.lon_lo <= lon < self.lon_hi

    def intersects_circle(self, lat, lon, r_deg):
        """Closest point on the box to the centre, then compare distance."""
        cx = min(max(lon, self.lon_lo), self.lon_hi)
        cy = min(max(lat, self.lat_lo), self.lat_hi)
        return (cx - lon) ** 2 + (cy - lat) ** 2 <= r_deg ** 2

    def as_dict(self):
        return dict(lat_lo=self.lat_lo, lon_lo=self.lon_lo,
                    lat_hi=self.lat_hi, lon_hi=self.lon_hi)


class QuadTree:
    def __init__(self, box, capacity=4, depth=0, max_depth=12):
        self.box = box
        self.capacity = capacity
        self.depth = depth
        self.max_depth = max_depth
        self.points = []
        self.children = None

    def insert(self, p):
        if not self.box.contains(p["lat"], p["lon"]):
            return False

        if self.children is None:
            if len(self.points) < self.capacity or self.depth >= self.max_depth:
                self.points.append(p)
                return True
            self._split()

        for c in self.children:
            if c.insert(p):
                return True
        return False

    def _split(self):
        b = self.box
        mlat = (b.lat_lo + b.lat_hi) / 2
        mlon = (b.lon_lo + b.lon_hi) / 2
        d, cap, md = self.depth + 1, self.capacity, self.max_depth
        self.children = [
            QuadTree(Box(mlat, b.lon_lo, b.lat_hi, mlon), cap, d, md),   # NW
            QuadTree(Box(mlat, mlon, b.lat_hi, b.lon_hi), cap, d, md),   # NE
            QuadTree(Box(b.lat_lo, b.lon_lo, mlat, mlon), cap, d, md),   # SW
            QuadTree(Box(b.lat_lo, mlon, mlat, b.lon_hi), cap, d, md),   # SE
        ]
        moving, self.points = self.points, []
        for p in moving:
            for c in self.children:
                if c.insert(p):
                    break

    def query_circle(self, lat, lon, r_deg, stats=None):
        """Points within r_deg. stats counts the work, which is the point."""
        if stats is None:
            stats = {"nodes_visited": 0, "nodes_pruned": 0, "points_tested": 0}

        if not self.box.intersects_circle(lat, lon, r_deg):
            stats["nodes_pruned"] += 1
            return [], stats

        stats["nodes_visited"] += 1

        if self.children is None:
            found = []
            for p in self.points:
                stats["points_tested"] += 1
                if (p["lat"] - lat) ** 2 + (p["lon"] - lon) ** 2 <= r_deg ** 2:
                    found.append(p)
            return found, stats

        found = []
        for c in self.children:
            got, _ = c.query_circle(lat, lon, r_deg, stats)
            found.extend(got)
        return found, stats

    def boxes(self, out=None):
        """Every node's box, for drawing."""
        if out is None:
            out = []
        out.append(dict(depth=self.depth, leaf=self.children is None,
                        n=len(self.points), **self.box.as_dict()))
        if self.children:
            for c in self.children:
                c.boxes(out)
        return out

    def stats(self):
        nodes = leaves = pts = 0
        deepest = 0
        stack = [self]
        while stack:
            n = stack.pop()
            nodes += 1
            deepest = max(deepest, n.depth)
            if n.children is None:
                leaves += 1
                pts += len(n.points)
            else:
                stack.extend(n.children)
        return dict(nodes=nodes, leaves=leaves, points=pts, max_depth=deepest)


def haversine_m(lat1, lon1, lat2, lon2):
    R = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * R * math.asin(math.sqrt(a))

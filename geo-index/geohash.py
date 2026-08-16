"""Geohash: interleave the bits of lat and lon, then base32 the result.

The whole idea is that nearby points share a prefix, so a substring match
becomes a bounding-box query.
"""

BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"


def encode(lat, lon, precision=7):
    lat_lo, lat_hi = -90.0, 90.0
    lon_lo, lon_hi = -180.0, 180.0

    out, bit, ch, even = [], 0, 0, True
    while len(out) < precision:
        if even:                                   # longitude on even bits
            mid = (lon_lo + lon_hi) / 2
            if lon >= mid:
                ch = (ch << 1) | 1
                lon_lo = mid
            else:
                ch <<= 1
                lon_hi = mid
        else:                                      # latitude on odd bits
            mid = (lat_lo + lat_hi) / 2
            if lat >= mid:
                ch = (ch << 1) | 1
                lat_lo = mid
            else:
                ch <<= 1
                lat_hi = mid
        even = not even

        bit += 1
        if bit == 5:                               # 5 bits = one base32 char
            out.append(BASE32[ch])
            bit, ch = 0, 0

    return "".join(out)


def bbox(gh):
    """Decode a geohash back to the box it covers."""
    lat_lo, lat_hi = -90.0, 90.0
    lon_lo, lon_hi = -180.0, 180.0
    even = True

    for c in gh:
        idx = BASE32.index(c)
        for shift in (4, 3, 2, 1, 0):
            bit = (idx >> shift) & 1
            if even:
                mid = (lon_lo + lon_hi) / 2
                lon_lo, lon_hi = (mid, lon_hi) if bit else (lon_lo, mid)
            else:
                mid = (lat_lo + lat_hi) / 2
                lat_lo, lat_hi = (mid, lat_hi) if bit else (lat_lo, mid)
            even = not even

    return lat_lo, lon_lo, lat_hi, lon_hi


def decode(gh):
    lat_lo, lon_lo, lat_hi, lon_hi = bbox(gh)
    return (lat_lo + lat_hi) / 2, (lon_lo + lon_hi) / 2


def neighbours(gh):
    """The 8 surrounding cells, found by nudging just outside each edge."""
    lat_lo, lon_lo, lat_hi, lon_hi = bbox(gh)
    dlat, dlon = lat_hi - lat_lo, lon_hi - lon_lo
    lat, lon = (lat_lo + lat_hi) / 2, (lon_lo + lon_hi) / 2

    out = {}
    for name, (dy, dx) in {
        "n": (1, 0), "ne": (1, 1), "e": (0, 1), "se": (-1, 1),
        "s": (-1, 0), "sw": (-1, -1), "w": (0, -1), "nw": (1, -1),
    }.items():
        out[name] = encode(lat + dy * dlat, lon + dx * dlon, len(gh))
    return out


def cell_size_m(precision):
    """Approximate cell dimensions in metres, at the equator."""
    lat_lo, lon_lo, lat_hi, lon_hi = bbox("0" * precision)
    return round((lat_hi - lat_lo) * 111_320), round((lon_hi - lon_lo) * 111_320)


if __name__ == "__main__":
    lat, lon = 12.9716, 77.5946                    # Bangalore
    print(f"{lat}, {lon}\n")
    print(f"{'prec':>4}  {'geohash':<12} {'cell height':>12} {'cell width':>11}")
    for p in range(1, 10):
        h, w = cell_size_m(p)
        print(f"{p:>4}  {encode(lat, lon, p):<12} {h:>10,} m {w:>9,} m")

    print("\nprefix property — nearby points share leading characters:")
    for name, (a, b) in {
        "same spot":        (12.9716, 77.5946),
        "500 m away":       (12.9760, 77.5946),
        "5 km away":        (13.0160, 77.5946),
        "Mumbai (840 km)":  (19.0760, 72.8777),
    }.items():
        gh = encode(a, b, 8)
        base = encode(lat, lon, 8)
        shared = 0
        for x, y in zip(gh, base):
            if x != y:
                break
            shared += 1
        print(f"  {name:<18} {gh}   shares {shared} chars")

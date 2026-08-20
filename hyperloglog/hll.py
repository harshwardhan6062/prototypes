"""HyperLogLog from scratch.

The whole trick: hash each item, look at how many leading zeros the hash has.
Leading zeros are rare in random bits — k zeros happens once per 2^k items — so
the longest run you've seen is evidence of how many items went past.
"""
import hashlib
import math


class HLL:
    def __init__(self, p=14):
        self.p = p                      # bits used to pick a register
        self.m = 1 << p                 # number of registers
        self.regs = bytearray(self.m)   # each holds "longest run seen here"
        self.bits = 64 - p

        if self.m == 16:
            self.alpha = 0.673
        elif self.m == 32:
            self.alpha = 0.697
        elif self.m == 64:
            self.alpha = 0.709
        else:
            self.alpha = 0.7213 / (1 + 1.079 / self.m)

    def add(self, item):
        h = int.from_bytes(hashlib.sha1(str(item).encode()).digest()[:8], "big")
        idx = h >> self.bits                      # top p bits -> which register
        w = h & ((1 << self.bits) - 1)            # the rest -> count zeros in it
        rho = self.bits - w.bit_length() + 1 if w else self.bits + 1
        if rho > self.regs[idx]:
            self.regs[idx] = rho                  # keep only the maximum

    def count(self):
        # harmonic mean across registers — HLL's improvement on LogLog, it stops
        # one unlucky register from dominating the estimate
        Z = sum(2.0 ** -r for r in self.regs)
        est = self.alpha * self.m * self.m / Z

        empty = self.regs.count(0)
        if est <= 2.5 * self.m and empty:         # small-cardinality correction
            return self.m * math.log(self.m / empty)
        return est

    def merge(self, other):
        """Two HLLs combine by taking the max per register — which is why they
        can be summed across servers, and a plain counter cannot."""
        assert self.p == other.p
        out = HLL(self.p)
        out.regs = bytearray(max(a, b) for a, b in zip(self.regs, other.regs))
        return out

    def memory_bytes(self):
        return math.ceil(self.m * 6 / 8)          # 6 bits per register, packed

    @property
    def expected_error(self):
        return 1.04 / math.sqrt(self.m)


if __name__ == "__main__":
    print("=== why leading zeros work ===")
    for k in range(1, 6):
        print(f"  hash starts with {k} zeros -> you have probably seen ~{2**k} items")

    print("\n=== accuracy against a real set ===")
    print(f"{'true':>12} {'estimated':>12} {'error':>8}")
    hll, seen, n = HLL(p=14), set(), 0
    for target in (1_000, 10_000, 100_000, 1_000_000):
        while n < target:
            item = f"user:{n}"
            hll.add(item)
            seen.add(item)
            n += 1
        est = hll.count()
        err = abs(est - len(seen)) / len(seen) * 100
        print(f"{len(seen):>12,} {est:>12,.0f} {err:>7.2f}%")

    print(f"\nregisters      : {hll.m:,}")
    print(f"memory (packed): {hll.memory_bytes():,} bytes")
    print(f"expected error : {hll.expected_error*100:.2f}%")

    import sys
    real = sys.getsizeof(seen) + sum(sys.getsizeof(s) for s in list(seen)[:1000]) / 1000 * len(seen)
    print(f"the exact set   : ~{real/1024/1024:,.0f} MB for the same 1M items")

    print("\n=== merging, which a counter cannot do ===")
    a, b = HLL(14), HLL(14)
    for i in range(500_000):
        a.add(f"user:{i}")
    for i in range(250_000, 750_000):              # deliberate 250k overlap
        b.add(f"user:{i}")
    m = a.merge(b)
    print(f"  A           : {a.count():>10,.0f}   (true 500,000)")
    print(f"  B           : {b.count():>10,.0f}   (true 500,000)")
    print(f"  A merge B   : {m.count():>10,.0f}   (true 750,000)")
    print("  the 250,000 overlap was not double counted")

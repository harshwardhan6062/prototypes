import java.util.*;
import java.util.stream.*;

// A better contract for splitting. Run: java SplitV2.java

record User(String id, String name) {
    @Override public String toString() { return name; }
}
record Split(User user, long amount) {
    @Override public String toString() { return user + " " + Money.of(amount); }
}
class Money {
    static String of(long p) { return "₹" + p / 100 + "." + String.format("%02d", Math.abs(p % 100)); }
}

// ═════════════════════════════════════════════════════════════════════
// THE INSIGHT: all three split types are the SAME operation —
// "hand out `total` in proportion to some weights". Only the weights differ.
//
//   EQUAL    weights are all 1
//   PERCENT  weights are the percentages
//   EXACT    weights are the amounts themselves
//   SHARES   weights are the share counts
//
// So the contract is one method returning weights, and rounding is solved
// once, centrally, instead of being re-derived in every strategy.
// ═════════════════════════════════════════════════════════════════════

sealed interface SplitRequest
        permits SplitRequest.Equally, SplitRequest.ByAmount,
                SplitRequest.ByPercent, SplitRequest.ByShares {

    /** Relative weights per participant. Absolute scale is irrelevant. */
    Map<User, Long> weights(long total);

    /** Participants, in a stable order so rounding is reproducible. */
    List<User> participants();

    // ---- EQUAL ----------------------------------------------------------
    record Equally(List<User> users) implements SplitRequest {
        public Equally {
            if (users == null || users.isEmpty())
                throw new IllegalArgumentException("need at least one participant");
            users = List.copyOf(users);
        }
        public List<User> participants() { return users; }
        public Map<User, Long> weights(long total) {
            return users.stream().collect(Collectors.toMap(u -> u, u -> 1L,
                    (a, b) -> a, LinkedHashMap::new));
        }
    }

    // ---- EXACT (in paise, not rupees, not Double) ------------------------
    record ByAmount(LinkedHashMap<User, Long> paise) implements SplitRequest {
        public ByAmount {
            if (paise == null || paise.isEmpty())
                throw new IllegalArgumentException("need at least one participant");
            if (paise.values().stream().anyMatch(v -> v < 0))
                throw new IllegalArgumentException("amounts cannot be negative");
            paise = new LinkedHashMap<>(paise);
        }
        public List<User> participants() { return List.copyOf(paise.keySet()); }
        public Map<User, Long> weights(long total) {
            long sum = paise.values().stream().mapToLong(Long::longValue).sum();
            if (sum != total)                       // validated HERE, at the boundary
                throw new IllegalArgumentException(
                    "exact amounts sum to " + Money.of(sum) + ", expected " + Money.of(total));
            return paise;
        }
    }

    // ---- PERCENT ---------------------------------------------------------
    record ByPercent(LinkedHashMap<User, Double> pct) implements SplitRequest {
        public ByPercent {
            if (pct == null || pct.isEmpty())
                throw new IllegalArgumentException("need at least one participant");
            double sum = pct.values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(sum - 100.0) > 1e-9)       // validated in the CONSTRUCTOR:
                throw new IllegalArgumentException( // an invalid request cannot exist
                    "percentages sum to " + sum + ", must be 100");
            pct = new LinkedHashMap<>(pct);
        }
        public List<User> participants() { return List.copyOf(pct.keySet()); }
        public Map<User, Long> weights(long total) {
            // scale to integers so the distributor stays in exact arithmetic
            var out = new LinkedHashMap<User, Long>();
            pct.forEach((u, p) -> out.put(u, Math.round(p * 1_000_000)));
            return out;
        }
    }

    // ---- SHARES (added later — note nothing above had to change) ---------
    record ByShares(LinkedHashMap<User, Integer> shares) implements SplitRequest {
        public ByShares {
            if (shares == null || shares.isEmpty())
                throw new IllegalArgumentException("need at least one participant");
            if (shares.values().stream().anyMatch(s -> s <= 0))
                throw new IllegalArgumentException("shares must be positive");
            shares = new LinkedHashMap<>(shares);
        }
        public List<User> participants() { return List.copyOf(shares.keySet()); }
        public Map<User, Long> weights(long total) {
            var out = new LinkedHashMap<User, Long>();
            shares.forEach((u, s) -> out.put(u, (long) s));
            return out;
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
// Rounding lives in exactly ONE place now. Largest-remainder method:
// everyone gets the floor of their share, then the leftover paise go to
// whoever was rounded down hardest. Deterministic, and always sums exactly.
// ═════════════════════════════════════════════════════════════════════
final class Distributor {
    static List<Split> distribute(long total, SplitRequest req) {
        Map<User, Long> w = req.weights(total);
        long W = w.values().stream().mapToLong(Long::longValue).sum();
        if (W <= 0) throw new IllegalArgumentException("weights must sum to something positive");

        record Part(User user, long base, long remainder) {}
        List<Part> parts = new ArrayList<>();
        long assigned = 0;

        for (User u : req.participants()) {
            long num = total * w.get(u);
            long base = num / W;
            parts.add(new Part(u, base, num % W));
            assigned += base;
        }

        long leftover = total - assigned;                 // always 0 <= leftover < participants
        List<Part> byRemainder = new ArrayList<>(parts);
        byRemainder.sort(Comparator
                .comparingLong(Part::remainder).reversed()
                .thenComparing(p -> p.user().id()));      // deterministic tie-break

        Set<User> getsExtra = byRemainder.stream().limit(leftover)
                .map(Part::user).collect(Collectors.toSet());

        return parts.stream()
                .map(p -> new Split(p.user(), p.base() + (getsExtra.contains(p.user()) ? 1 : 0)))
                .toList();
    }
}

public class SplitV2 {

    /** Map.of() does NOT preserve order — build the LinkedHashMap explicitly. */
    @SafeVarargs
    static <V> LinkedHashMap<User, V> ordered(Map.Entry<User, V>... entries) {
        var m = new LinkedHashMap<User, V>();
        for (var e : entries) m.put(e.getKey(), e.getValue());
        return m;
    }

    static void show(String label, SplitRequest req, long total) {
        try {
            List<Split> splits = Distributor.distribute(total, req);
            long sum = splits.stream().mapToLong(Split::amount).sum();
            System.out.printf("  %-34s %s%n", label, splits);
            System.out.printf("  %-34s sums to %s  %s%n", "", Money.of(sum),
                    sum == total ? "✓" : "✗ MISMATCH");
        } catch (IllegalArgumentException e) {
            System.out.printf("  %-34s rejected: %s%n", label, e.getMessage());
        }
    }

    public static void main(String[] args) {
        User a = new User("u1", "Alice"), b = new User("u2", "Bob"), c = new User("u3", "Carol");

        System.out.println("── ₹100 four ways ──────────────────────────────────────");
        show("Equally(3)", new SplitRequest.Equally(List.of(a, b, c)), 10000);
        show("ByPercent(50/25/25)", new SplitRequest.ByPercent(
                ordered(Map.entry(a, 50.0), Map.entry(b, 25.0), Map.entry(c, 25.0))), 10000);
        show("ByAmount(60/30/10)", new SplitRequest.ByAmount(
                ordered(Map.entry(a, 6000L), Map.entry(b, 3000L), Map.entry(c, 1000L))), 10000);
        show("ByShares(2:1:1)", new SplitRequest.ByShares(
                ordered(Map.entry(a, 2), Map.entry(b, 1), Map.entry(c, 1))), 10000);

        System.out.println("\n── nasty rounding: ₹0.10 split 3 ways ──────────────────");
        show("Equally(3) of ₹0.10", new SplitRequest.Equally(List.of(a, b, c)), 10);

        System.out.println("\n── ₹100 by 33.33/33.33/33.34 percent ───────────────────");
        show("ByPercent", new SplitRequest.ByPercent(
                ordered(Map.entry(a, 33.33), Map.entry(b, 33.33), Map.entry(c, 33.34))), 10000);

        System.out.println("\n── invalid requests can't even be constructed ──────────");
        try {
            new SplitRequest.ByPercent(ordered(Map.entry(a, 50.0), Map.entry(b, 30.0)));
        } catch (IllegalArgumentException e) { System.out.println("  " + e.getMessage()); }
        try {
            new SplitRequest.ByShares(ordered(Map.entry(a, 0), Map.entry(b, 1)));
        } catch (IllegalArgumentException e) { System.out.println("  " + e.getMessage()); }
        show("ByAmount(60/30) of ₹100", new SplitRequest.ByAmount(
                ordered(Map.entry(a, 6000L), Map.entry(b, 3000L))), 10000);

        System.out.println("\n── the old contract's bug this makes impossible ────────");
        System.out.println("  old: calculate(amount, [alice,bob,carol], [50.0,25.0,25.0])");
        System.out.println("       reorder the participant list and the percentages");
        System.out.println("       silently attach to the wrong people.");
        System.out.println("  new: the User IS the map key. They cannot desync.");
    }
}

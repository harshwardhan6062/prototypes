import java.util.*;
import java.util.stream.*;

// Complete Splitwise LLD. Run:  java Splitwise.java

// ═══════════════════════════ MODEL ═══════════════════════════

record User(String id, String name) {
    @Override public String toString() { return name; }
}

class Group {
    final String id, name;
    final Set<User> members = new LinkedHashSet<>();
    Group(String id, String name, List<User> members) {
        this.id = id; this.name = name; this.members.addAll(members);
    }
}

record Split(User user, long amount) {
    @Override public String toString() { return user + " " + Money.of(amount); }
}

class Money {
    static String of(long paise) {
        String sign = paise < 0 ? "-" : "";
        long a = Math.abs(paise);
        return sign + "₹" + a / 100 + "." + String.format("%02d", a % 100);
    }
}

/** Immutable record of one event. Validates itself; cannot exist in a bad state. */
final class Expense {
    final String id, description;
    final long amount;
    final User paidBy;
    final List<Split> splits;
    final Group group;

    Expense(String id, String description, long amount, User paidBy, List<Split> splits, Group group) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        long sum = splits.stream().mapToLong(Split::amount).sum();
        if (sum != amount)
            throw new IllegalArgumentException("splits sum to " + Money.of(sum) + ", expected " + Money.of(amount));
        this.id = id; this.description = description; this.amount = amount;
        this.paidBy = paidBy; this.splits = List.copyOf(splits); this.group = group;
    }
}

// ═══════════════════════ HOW TO SPLIT ════════════════════════
//
// Every split type is the SAME operation: hand out `total` in proportion to
// some weights. Only the weights differ. So the contract is one method that
// returns weights, and rounding is solved once, centrally.
//
//   Equally    weights all 1
//   ByPercent  weights are the percentages
//   ByAmount   weights are the amounts themselves
//   ByShares   weights are the share counts
//
sealed interface SplitRequest
        permits SplitRequest.Equally, SplitRequest.ByAmount,
                SplitRequest.ByPercent, SplitRequest.ByShares {

    /** Relative weights per participant. Absolute scale is irrelevant. */
    Map<User, Long> weights(long total);

    /** Participants in a stable order, so rounding is reproducible. */
    List<User> participants();

    /** Map.of() does NOT preserve order — build ordered maps with this. */
    @SafeVarargs
    static <V> LinkedHashMap<User, V> of(Map.Entry<User, V>... entries) {
        var m = new LinkedHashMap<User, V>();
        for (var e : entries) m.put(e.getKey(), e.getValue());
        return m;
    }

    record Equally(List<User> users) implements SplitRequest {
        public Equally {
            if (users == null || users.isEmpty())
                throw new IllegalArgumentException("need at least one participant");
            users = List.copyOf(users);
        }
        public List<User> participants() { return users; }
        public Map<User, Long> weights(long total) {
            return users.stream().collect(Collectors.toMap(
                    u -> u, u -> 1L, (a, b) -> a, LinkedHashMap::new));
        }
    }

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
            if (sum != total)                        // needs the total, so validated here
                throw new IllegalArgumentException(
                    "exact amounts sum to " + Money.of(sum) + ", expected " + Money.of(total));
            return paise;
        }
    }

    record ByPercent(LinkedHashMap<User, Double> pct) implements SplitRequest {
        public ByPercent {
            if (pct == null || pct.isEmpty())
                throw new IllegalArgumentException("need at least one participant");
            double sum = pct.values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(sum - 100.0) > 1e-9)        // validated in the CONSTRUCTOR:
                throw new IllegalArgumentException(  // an invalid request cannot exist
                    "percentages sum to " + sum + ", must be 100");
            pct = new LinkedHashMap<>(pct);
        }
        public List<User> participants() { return List.copyOf(pct.keySet()); }
        public Map<User, Long> weights(long total) {
            var out = new LinkedHashMap<User, Long>();
            pct.forEach((u, p) -> out.put(u, Math.round(p * 1_000_000)));   // stay integral
            return out;
        }
    }

    /** Added after the other three. Nothing existing had to change. */
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

/**
 * Rounding lives here and nowhere else. Largest-remainder: everyone gets the
 * floor of their share, then leftover paise go to whoever was rounded down
 * hardest. Deterministic, and the parts always sum back to the whole.
 */
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
            parts.add(new Part(u, num / W, num % W));
            assigned += num / W;
        }

        long leftover = total - assigned;            // 0 <= leftover < participants
        Set<User> getsExtra = parts.stream()
                .sorted(Comparator.comparingLong(Part::remainder).reversed()
                        .thenComparing(p -> p.user().id()))     // deterministic tie-break
                .limit(leftover)
                .map(Part::user)
                .collect(Collectors.toSet());

        return parts.stream()
                .map(p -> new Split(p.user(), p.base() + (getsExtra.contains(p.user()) ? 1 : 0)))
                .toList();
    }
}

// ═══════════════════════ BALANCE SHEET ═══════════════════════

/** Derived state. Net pairwise balances, so reads are O(1). */
class BalanceSheet {
    private final Map<User, Map<User, Long>> b = new LinkedHashMap<>();

    private long get(User from, User to) { return b.getOrDefault(from, Map.of()).getOrDefault(to, 0L); }

    private void set(User from, User to, long v) {
        if (v <= 0) { var m = b.get(from); if (m != null) m.remove(to); }
        else b.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(to, v);
    }

    synchronized void record(User debtor, User creditor, long amount) {
        if (debtor.equals(creditor) || amount == 0) return;
        long opposite = get(creditor, debtor);        // do they already owe us?
        if (opposite >= amount) set(creditor, debtor, opposite - amount);
        else { set(creditor, debtor, 0); set(debtor, creditor, get(debtor, creditor) + amount - opposite); }
    }

    /** From one user's perspective: positive = owed to them, negative = they owe. */
    synchronized Map<User, Long> balancesFor(User u) {
        Map<User, Long> out = new LinkedHashMap<>();
        b.getOrDefault(u, Map.of()).forEach((to, amt) -> out.merge(to, -amt, Long::sum));
        b.forEach((from, m) -> { if (m.containsKey(u)) out.merge(from, m.get(u), Long::sum); });
        return out;
    }

    synchronized Map<User, Long> netBalances() {
        Map<User, Long> net = new LinkedHashMap<>();
        b.forEach((from, m) -> m.forEach((to, amt) -> {
            net.merge(from, -amt, Long::sum);
            net.merge(to, amt, Long::sum);
        }));
        return net;
    }

    /** Greedy min-cash-flow. At most n-1 transfers; the exact minimum is NP-hard. */
    synchronized List<String> simplify() {
        var debtors   = new PriorityQueue<Map.Entry<User, Long>>(Comparator.comparingLong(Map.Entry::getValue));
        var creditors = new PriorityQueue<Map.Entry<User, Long>>(
                Comparator.comparingLong((Map.Entry<User, Long> e) -> e.getValue()).reversed());

        netBalances().forEach((u, v) -> {
            if (v < 0) debtors.add(Map.entry(u, v));
            else if (v > 0) creditors.add(Map.entry(u, v));
        });

        List<String> plan = new ArrayList<>();
        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            var d = debtors.poll();
            var c = creditors.poll();
            long pay = Math.min(-d.getValue(), c.getValue());
            plan.add("  " + d.getKey() + " pays " + c.getKey() + " " + Money.of(pay));
            long dLeft = d.getValue() + pay, cLeft = c.getValue() - pay;
            if (dLeft < 0) debtors.add(Map.entry(d.getKey(), dLeft));
            if (cLeft > 0) creditors.add(Map.entry(c.getKey(), cLeft));
        }
        return plan;
    }

    synchronized void print() {
        boolean any = false;
        for (var e : b.entrySet())
            for (var x : e.getValue().entrySet()) {
                System.out.println("    " + e.getKey() + " owes " + x.getKey() + " " + Money.of(x.getValue()));
                any = true;
            }
        if (!any) System.out.println("    (all settled)");
    }
}

// ═══════════════════════════ SERVICE ═════════════════════════

class SplitwiseService {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final List<Expense> log = new ArrayList<>();       // append-only history
    private final BalanceSheet sheet = new BalanceSheet();     // derived state
    private int seq = 0;

    User addUser(String id, String name) { var u = new User(id, name); users.put(id, u); return u; }

    Group createGroup(String id, String name, List<User> members) {
        var g = new Group(id, name, members); groups.put(id, g); return g;
    }

    Expense addExpense(String desc, long amount, User paidBy, SplitRequest how, Group group) {
        List<Split> splits = Distributor.distribute(amount, how);                     // 1. compute splits
        Expense e = new Expense("e" + (++seq), desc, amount, paidBy, splits, group);  // 2. build + validate
        log.add(e);                                                                   // 3. append, then frozen
        applyDeltas(e, +1);                                                           // 4. update derived state
        return e;
    }

    void deleteExpense(Expense e) { log.remove(e); applyDeltas(e, -1); }

    private void applyDeltas(Expense e, int sign) {
        for (Split s : e.splits) {
            if (s.user().equals(e.paidBy)) continue;             // payer owes themselves nothing
            if (sign > 0) sheet.record(s.user(), e.paidBy, s.amount());
            else          sheet.record(e.paidBy, s.user(), s.amount());
        }
    }

    void settleUp(User from, User to, long amount) { sheet.record(to, from, amount); }

    Map<User, Long> balancesFor(User u) { return sheet.balancesFor(u); }
    List<String> simplify()             { return sheet.simplify(); }
    void printSheet()                   { sheet.print(); }
    List<Expense> history()             { return List.copyOf(log); }
}

// ═════════════════════════════ MAIN ══════════════════════════

public class Splitwise {

    static void header(String s) { System.out.println("\n── " + s + " " + "─".repeat(Math.max(0, 56 - s.length()))); }

    public static void main(String[] args) {
        SplitwiseService svc = new SplitwiseService();

        header("1. setup");
        User alice = svc.addUser("u1", "Alice");
        User bob   = svc.addUser("u2", "Bob");
        User carol = svc.addUser("u3", "Carol");
        Group trip = svc.createGroup("g1", "Goa Trip", List.of(alice, bob, carol));
        System.out.println("  group '" + trip.name + "' with " + trip.members);

        header("2. Equally — Alice pays ₹300 dinner");
        var dinner = svc.addExpense("Dinner", 30000, alice,
                new SplitRequest.Equally(List.of(alice, bob, carol)), trip);
        System.out.println("  splits: " + dinner.splits);
        svc.printSheet();

        header("3. ByAmount — Bob pays ₹500, uneven shares");
        var lunch = svc.addExpense("Lunch", 50000, bob,
                new SplitRequest.ByAmount(SplitRequest.of(
                        Map.entry(alice, 15000L), Map.entry(bob, 25000L), Map.entry(carol, 10000L))), trip);
        System.out.println("  splits: " + lunch.splits);
        svc.printSheet();

        header("4. ByPercent — Carol pays ₹100 taxi (50/25/25)");
        var taxi = svc.addExpense("Taxi", 10000, carol,
                new SplitRequest.ByPercent(SplitRequest.of(
                        Map.entry(alice, 50.0), Map.entry(bob, 25.0), Map.entry(carol, 25.0))), trip);
        System.out.println("  splits: " + taxi.splits);
        svc.printSheet();

        header("5. ByShares — Alice pays ₹90, Bob counts double");
        var snacks = svc.addExpense("Snacks", 9000, alice,
                new SplitRequest.ByShares(SplitRequest.of(
                        Map.entry(alice, 1), Map.entry(bob, 2), Map.entry(carol, 1))), trip);
        System.out.println("  splits: " + snacks.splits);

        header("6. balances from each person's view");
        for (User u : List.of(alice, bob, carol)) {
            System.out.println("  " + u + ":");
            var bal = svc.balancesFor(u);
            if (bal.isEmpty()) System.out.println("    (settled)");
            bal.forEach((other, amt) -> System.out.println("    " +
                (amt > 0 ? other + " owes them " + Money.of(amt)
                         : "they owe " + other + " " + Money.of(-amt))));
        }

        header("7. settle up — Carol pays Alice ₹50");
        svc.settleUp(carol, alice, 5000);
        svc.printSheet();

        header("8. simplify debts (greedy min cash flow)");
        svc.simplify().forEach(System.out::println);

        header("9. delete the taxi expense — reverse the deltas");
        svc.deleteExpense(taxi);
        svc.printSheet();
        System.out.println("  expenses in log: " + svc.history().size());

        header("10. rounding always reconciles");
        for (long amt : new long[]{10000, 10, 100, 1}) {
            var splits = Distributor.distribute(amt, new SplitRequest.Equally(List.of(alice, bob, carol)));
            long sum = splits.stream().mapToLong(Split::amount).sum();
            System.out.printf("  %-8s / 3 = %-44s sums to %-8s %s%n",
                    Money.of(amt), splits, Money.of(sum), sum == amt ? "✓" : "✗");
        }

        header("11. invalid requests can't be constructed");
        try {
            new SplitRequest.ByPercent(SplitRequest.of(Map.entry(alice, 50.0), Map.entry(bob, 30.0)));
        } catch (IllegalArgumentException e) { System.out.println("  rejected: " + e.getMessage()); }
        try {
            new SplitRequest.ByShares(SplitRequest.of(Map.entry(alice, 0), Map.entry(bob, 1)));
        } catch (IllegalArgumentException e) { System.out.println("  rejected: " + e.getMessage()); }
        try {
            svc.addExpense("Bad", 10000, alice, new SplitRequest.ByAmount(
                    SplitRequest.of(Map.entry(alice, 6000L), Map.entry(bob, 3000L))), trip);
        } catch (IllegalArgumentException e) { System.out.println("  rejected: " + e.getMessage()); }

        System.out.println("  balance sheet untouched by the failures:");
        svc.printSheet();
    }
}

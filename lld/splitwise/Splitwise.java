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

// ═══════════════════════ SPLIT STRATEGIES ════════════════════

enum SplitType { EQUAL, EXACT, PERCENT }

interface SplitStrategy {
    List<Split> calculate(long amount, List<User> participants, List<Double> values);
}

class EqualSplit implements SplitStrategy {
    public List<Split> calculate(long amount, List<User> users, List<Double> ignored) {
        long base = amount / users.size(), rem = amount % users.size();
        List<Split> out = new ArrayList<>();
        for (int i = 0; i < users.size(); i++)
            out.add(new Split(users.get(i), base + (i < rem ? 1 : 0)));   // spread the remainder
        return out;
    }
}

class ExactSplit implements SplitStrategy {
    public List<Split> calculate(long amount, List<User> users, List<Double> values) {
        if (values == null || values.size() != users.size())
            throw new IllegalArgumentException("need one exact amount per participant");
        List<Split> out = new ArrayList<>();
        for (int i = 0; i < users.size(); i++)
            out.add(new Split(users.get(i), Math.round(values.get(i) * 100)));
        return out;                       // Expense's constructor enforces the sum
    }
}

class PercentSplit implements SplitStrategy {
    public List<Split> calculate(long amount, List<User> users, List<Double> pct) {
        if (pct == null || pct.size() != users.size())
            throw new IllegalArgumentException("need one percentage per participant");
        double total = pct.stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(total - 100.0) > 1e-9)
            throw new IllegalArgumentException("percentages sum to " + total + ", must be 100");

        List<Split> out = new ArrayList<>();
        long assigned = 0;
        for (int i = 0; i < users.size() - 1; i++) {
            long share = Math.round(amount * pct.get(i) / 100.0);
            assigned += share;
            out.add(new Split(users.get(i), share));
        }
        out.add(new Split(users.getLast(), amount - assigned));  // last absorbs rounding
        return out;
    }
}

class SplitStrategyFactory {
    private static final Map<SplitType, SplitStrategy> REGISTRY = Map.of(
        SplitType.EQUAL,   new EqualSplit(),
        SplitType.EXACT,   new ExactSplit(),
        SplitType.PERCENT, new PercentSplit());

    static SplitStrategy get(SplitType t) { return REGISTRY.get(t); }
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
        long opposite = get(creditor, debtor);
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

    /** Greedy min-cash-flow. At most n-1 transfers; exact minimum is NP-hard. */
    synchronized List<String> simplify() {
        var debtors  = new PriorityQueue<Map.Entry<User, Long>>(Comparator.comparingLong(Map.Entry::getValue));
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
    private final List<Expense> log = new ArrayList<>();          // append-only history
    private final BalanceSheet sheet = new BalanceSheet();        // derived state
    private int seq = 0;

    User addUser(String id, String name) {
        User u = new User(id, name); users.put(id, u); return u;
    }

    Group createGroup(String id, String name, List<User> members) {
        Group g = new Group(id, name, members); groups.put(id, g); return g;
    }

    Expense addExpense(String desc, long amount, User paidBy, List<User> participants,
                       SplitType type, List<Double> values, Group group) {
        List<Split> splits = SplitStrategyFactory.get(type).calculate(amount, participants, values);
        Expense e = new Expense("e" + (++seq), desc, amount, paidBy, splits, group);
        log.add(e);
        applyDeltas(e, +1);
        return e;
    }

    void deleteExpense(Expense e) { log.remove(e); applyDeltas(e, -1); }

    private void applyDeltas(Expense e, int sign) {
        for (Split s : e.splits) {
            if (s.user().equals(e.paidBy)) continue;              // payer owes themselves nothing
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

        // ---- 1. users and a group
        header("1. setup");
        User alice = svc.addUser("u1", "Alice");
        User bob   = svc.addUser("u2", "Bob");
        User carol = svc.addUser("u3", "Carol");
        Group trip = svc.createGroup("g1", "Goa Trip", List.of(alice, bob, carol));
        System.out.println("  group '" + trip.name + "' with " + trip.members);

        // ---- 2. EQUAL: Alice pays ₹300 dinner, split 3 ways
        header("2. EQUAL split — Alice pays ₹300 dinner");
        var dinner = svc.addExpense("Dinner", 30000, alice, List.of(alice, bob, carol),
                                    SplitType.EQUAL, null, trip);
        System.out.println("  splits: " + dinner.splits);
        svc.printSheet();

        // ---- 3. EXACT: Bob pays ₹500, but Carol only ate ₹100 of it
        header("3. EXACT split — Bob pays ₹500, uneven shares");
        var lunch = svc.addExpense("Lunch", 50000, bob, List.of(alice, bob, carol),
                                   SplitType.EXACT, List.of(150.0, 250.0, 100.0), trip);
        System.out.println("  splits: " + lunch.splits);
        svc.printSheet();

        // ---- 4. PERCENT: Carol pays ₹100 taxi, 50/25/25
        header("4. PERCENT split — Carol pays ₹100 taxi (50/25/25)");
        var taxi = svc.addExpense("Taxi", 10000, carol, List.of(alice, bob, carol),
                                  SplitType.PERCENT, List.of(50.0, 25.0, 25.0), trip);
        System.out.println("  splits: " + taxi.splits);
        svc.printSheet();

        // ---- 5. per-user view
        header("5. balances from each person's view");
        for (User u : List.of(alice, bob, carol)) {
            System.out.println("  " + u + ":");
            var bal = svc.balancesFor(u);
            if (bal.isEmpty()) System.out.println("    (settled)");
            bal.forEach((other, amt) -> System.out.println("    " +
                (amt > 0 ? other + " owes them " + Money.of(amt)
                         : "they owe " + other + " " + Money.of(-amt))));
        }

        // ---- 6. settle up
        header("6. settle up — Carol pays Alice ₹50");
        svc.settleUp(carol, alice, 5000);
        svc.printSheet();

        // ---- 7. simplify
        header("7. simplify debts (greedy min cash flow)");
        svc.simplify().forEach(System.out::println);

        // ---- 8. delete reverses the deltas
        header("8. delete the taxi expense");
        svc.deleteExpense(taxi);
        svc.printSheet();
        System.out.println("  expenses in log: " + svc.history().size());

        // ---- 9. validation
        header("9. invalid expense is rejected at construction");
        try {
            svc.addExpense("Bad", 10000, alice, List.of(alice, bob),
                           SplitType.EXACT, List.of(30.0, 30.0), trip);   // sums to ₹60, not ₹100
        } catch (IllegalArgumentException ex) {
            System.out.println("  rejected: " + ex.getMessage());
        }
        try {
            svc.addExpense("Bad%", 10000, alice, List.of(alice, bob),
                           SplitType.PERCENT, List.of(50.0, 30.0), trip);
        } catch (IllegalArgumentException ex) {
            System.out.println("  rejected: " + ex.getMessage());
        }
        System.out.println("  balance sheet is untouched by the failures:");
        svc.printSheet();
    }
}

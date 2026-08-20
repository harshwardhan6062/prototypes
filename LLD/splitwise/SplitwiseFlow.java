import java.util.*;

// Run: java SplitwiseFlow.java
// Traces exactly who creates what when an expense is added.

record User(String id, String name) {
    @Override public String toString() { return name; }
}

record Split(User user, long amount) {           // immutable
    @Override public String toString() { return user + ":" + money(amount); }
    static String money(long paise) { return "₹" + (paise / 100) + "." + String.format("%02d", paise % 100); }
}

// ── The EXPENSE: an immutable record of one event. Created once, never edited.
final class Expense {
    final String id, description;
    final long amount;
    final User paidBy;
    final List<Split> splits;

    Expense(String id, String description, long amount, User paidBy, List<Split> splits) {
        long sum = splits.stream().mapToLong(Split::amount).sum();
        if (sum != amount)                                       // the key invariant
            throw new IllegalArgumentException("splits sum to " + sum + ", expected " + amount);
        this.id = id; this.description = description;
        this.amount = amount; this.paidBy = paidBy;
        this.splits = List.copyOf(splits);                       // defensive copy -> immutable
    }

    @Override public String toString() {
        return "Expense{" + description + ", " + Split.money(amount)
             + ", paidBy=" + paidBy + ", splits=" + splits + "}";
    }
}

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

// ── The BALANCE SHEET: long-lived, mutable, DERIVED from expenses.
class BalanceSheet {
    // debtor -> creditor -> amount   (always positive, one direction only)
    private final Map<User, Map<User, Long>> b = new LinkedHashMap<>();

    private long get(User from, User to) {
        return b.getOrDefault(from, Map.of()).getOrDefault(to, 0L);
    }
    private void set(User from, User to, long v) {
        if (v == 0) { var m = b.get(from); if (m != null) m.remove(to); }
        else b.computeIfAbsent(from, k -> new LinkedHashMap<>()).put(to, v);
    }

    synchronized void record(User debtor, User creditor, long amount) {
        if (debtor.equals(creditor) || amount == 0) return;      // you never owe yourself
        long opposite = get(creditor, debtor);                   // do they already owe US?
        if (opposite >= amount) {
            set(creditor, debtor, opposite - amount);            // just cancel some of it
        } else {
            set(creditor, debtor, 0);
            set(debtor, creditor, get(debtor, creditor) + amount - opposite);
        }
    }

    void print(String label) {
        System.out.println("  BalanceSheet " + label + ":");
        if (b.values().stream().allMatch(Map::isEmpty)) { System.out.println("    (all settled)"); return; }
        b.forEach((from, m) -> m.forEach((to, amt) ->
            System.out.println("    " + from + " owes " + to + " " + Split.money(amt))));
    }
}

// ── The SERVICE: orchestrates. This is where the flow lives.
class SplitwiseService {
    private final List<Expense> log = new ArrayList<>();          // append-only history
    private final BalanceSheet sheet = new BalanceSheet();        // derived state
    private int seq = 0;

    Expense addExpense(String desc, long amount, User paidBy,
                       List<User> participants, SplitStrategy strategy) {

        System.out.println("addExpense(\"" + desc + "\", " + Split.money(amount) + ", paidBy=" + paidBy + ")");

        // STEP 1 — the strategy computes the splits. No Expense exists yet.
        List<Split> splits = strategy.calculate(amount, participants, null);
        System.out.println("  1. strategy produced splits: " + splits);

        // STEP 2 — construct the Expense. Its constructor validates the invariant.
        Expense e = new Expense("e" + (++seq), desc, amount, paidBy, splits);
        System.out.println("  2. created  " + e);

        // STEP 3 — append to the log. Never mutated after this.
        log.add(e);
        System.out.println("  3. appended to log (now " + log.size() + " expenses)");

        // STEP 4 — apply the deltas to the balance sheet.
        System.out.println("  4. applying to balance sheet:");
        for (Split s : e.splits) {
            if (s.user().equals(e.paidBy)) {
                System.out.println("       " + s.user() + " is the payer — skip");
                continue;
            }
            System.out.println("       " + s.user() + " owes " + e.paidBy + " " + Split.money(s.amount()));
            sheet.record(s.user(), e.paidBy, s.amount());
        }
        sheet.print("after");
        System.out.println();
        return e;
    }

    void settle(User from, User to, long amount) {
        System.out.println("settleUp(" + from + " pays " + to + " " + Split.money(amount) + ")");
        sheet.record(to, from, amount);        // reverse direction cancels the debt
        sheet.print("after");
        System.out.println();
    }

    void deleteExpense(Expense e) {
        System.out.println("deleteExpense(" + e.description + ") — reverse the deltas, don't recompute");
        for (Split s : e.splits)
            if (!s.user().equals(e.paidBy))
                sheet.record(e.paidBy, s.user(), s.amount());     // reversed direction
        log.remove(e);
        sheet.print("after");
        System.out.println();
    }
}

public class SplitwiseFlow {
    public static void main(String[] args) {
        User alice = new User("u1", "Alice"), bob = new User("u2", "Bob"), carol = new User("u3", "Carol");
        SplitwiseService svc = new SplitwiseService();
        SplitStrategy equal = new EqualSplit();

        svc.addExpense("Dinner", 30000, alice, List.of(alice, bob, carol), equal);
        svc.addExpense("Coffee", 15000, bob,   List.of(alice, bob),        equal);
        Expense taxi = svc.addExpense("Taxi", 10000, carol, List.of(alice, bob, carol), equal);

        svc.settle(bob, alice, 2500);
        svc.deleteExpense(taxi);
    }
}

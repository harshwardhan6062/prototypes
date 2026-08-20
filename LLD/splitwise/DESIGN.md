# Splitwise — LLD

Interview answer, in the order to deliver it.

```bash
java Splitwise.java        # the full design
java SplitwiseFlow.java    # traced, shows object lifecycle step by step
```

## 1. Requirements (state these first, ~30 seconds)

**Functional**

```
· add users; create groups
· add an expense: payer, amount, participants, split type
· split types: equal, exact, percent, shares   (must be extensible)
· show balances — who owes whom
· settle up
· (stretch) simplify debts
```

**Non-functional**

```
· exact money arithmetic — no floating point
· adding a split type must not modify existing code
· thread-safe balance updates
```

## 2. Model

```java
record User(String id, String name);
class  Group  { String id, name; Set<User> members; }
record Split(User user, long amount);          // paise

final class Expense {                          // immutable event
    String id, description;
    long amount;                               // paise
    User paidBy;
    List<Split> splits;                        // must sum to amount
    Group group;                               // nullable = 1:1 expense
}
```

**Money is `long` in paise, never `double`.** `0.1 + 0.2 != 0.3`, and a lost
paisa per expense compounds. `BigDecimal` is the alternative — correct, heavier.

## 3. How to split

Every split type is the **same operation** — hand out `total` in proportion to
some weights. Only the weights differ:

```
Equally    weights all 1
ByPercent  weights are the percentages
ByAmount   weights are the amounts themselves
ByShares   weights are the share counts
```

So the contract is one method returning weights, and rounding gets solved once
rather than re-derived per strategy:

```java
sealed interface SplitRequest {
    Map<User, Long> weights(long total);   // relative; absolute scale irrelevant
    List<User> participants();             // stable order, reproducible rounding
}
```

Each variant is a record holding a `Map` keyed by `User`, so the person and
their value are one thing and cannot desync. `ByPercent` validates in its
compact constructor, so an invalid request cannot be constructed at all.

```java
record ByPercent(LinkedHashMap<User, Double> pct) implements SplitRequest {
    public ByPercent {
        double sum = pct.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 100.0) > 1e-9)
            throw new IllegalArgumentException("percentages sum to " + sum + ", must be 100");
    }
    ...
}
```

**Rounding, once, in the distributor.** Largest-remainder: everyone gets the
floor of their share, the leftover paise go to whoever was rounded down hardest,
ties broken by user id so it is deterministic.

```
₹100 / 3   ->  [₹33.34, ₹33.33, ₹33.33]   sums exactly
₹0.10 / 3  ->  [₹0.04, ₹0.03, ₹0.03]      sums exactly
₹0.01 / 3  ->  [₹0.01, ₹0.00, ₹0.00]      sums exactly
```

Interviewers look for the rounding answer specifically. See
[`SPLIT-CONTRACT.md`](./SPLIT-CONTRACT.md) for the interface this replaced and
why.

## 4. Balances

Store **net pairwise** balances, not a list of expenses to replay.

```java
synchronized void record(User debtor, User creditor, long amount) {
    if (debtor.equals(creditor) || amount == 0) return;
    long opposite = get(creditor, debtor);          // do they already owe us?
    if (opposite >= amount) set(creditor, debtor, opposite - amount);
    else { set(creditor, debtor, 0);
           set(debtor, creditor, get(debtor, creditor) + amount - opposite); }
}
```

Two people never hold debts in both directions. `getBalance(A,B)` is O(1)
instead of replaying every expense. Keep the expense log too — log for history,
balances for reads.

## 5. The flow when an expense is added

This is the part people get fuzzy on. Order matters:

```
1. distributor turns weights into splits  <- no Expense exists yet
2. Expense constructed FROM them       <- constructor validates sum == amount
3. appended to the log                 <- frozen forever, never edited
4. deltas applied to the balance sheet
```

The splits are an **input** to the `Expense`, not something set afterwards. That
is why the `Expense` can be immutable and validate itself in its constructor —
by the time it exists, it is already correct.

**The Expense is never updated.** Two objects, two lifecycles:

```
Expense       immutable record of one event.  Created once, appended, never touched.
BalanceSheet  long-lived derived state.       One object, mutated by every expense.
```

Delete applies the same deltas with the sign flipped — O(participants), never a
recompute.

## 6. Simplify debts (the stretch question)

```
A owes B ₹100, B owes C ₹100   ->   A pays C ₹100.  Two transfers become one.
```

```
1. compute NET balance per person  (paid − owed)
2. max-heap of creditors, max-heap of debtors
3. match the biggest of each, settle min(both), push back the remainder
```

Greedy gives at most n−1 transfers. **Say that the exact minimum is NP-hard** —
greedy is the practical answer. That line scores.

## 7. Patterns — name them explicitly

```
Polymorphic request  each SplitRequest variant supplies its own weights;
                     adding one is a new record, not an edit anywhere
Facade               SplitwiseService hides the subsystem
Observer             notifications on expense added   (mention as an extension)
```

Note this is deliberately **not** textbook GoF Strategy. The varying behaviour is
still behind an interface, it has just been attached to the data it needs, so the
two cannot be mismatched. Worth saying out loud — see
[`SPLIT-CONTRACT.md`](./SPLIT-CONTRACT.md).

## 8. Edge cases — raise them before being asked

```
· splits don't sum to the total       -> reject at construction
· payer is also a participant         -> they owe themselves nothing; skip
· settle more than owed               -> reject, or create a reverse balance
· user in two groups                  -> balances are per-pair; group is metadata
· concurrent expenses in one group    -> lock per group, or atomic balance updates
· deleting an expense                 -> reverse the deltas, don't recompute
· currency                            -> store on the expense; never mix
```

Validation lives in the `Expense` **constructor**, so an invalid expense never
exists as an object and therefore cannot reach the balance sheet — atomicity by
construction, with no rollback path to get wrong.

## 9. Layering

```
main / API          "add a ₹300 dinner, split equally"
   |
SplitwiseService    orchestrates: distribute -> Expense -> log -> deltas
   |
SplitRequest        supplies weights             (one record per split type)
Distributor         weights -> exact paise       (rounding, in one place)
   |
Expense             validates + freezes          (immutable event)
   |
BalanceSheet        accumulates net deltas       (derived state)
```

Nothing above `BalanceSheet` knows how balances are stored; nothing below
`SplitwiseService` knows about split types.

## The 60-second opener

> Users, Groups, Expenses. An expense has a payer, an amount, and a list of
> splits that must sum exactly to the amount. Every split type is really the
> same operation — hand out the total in proportion to weights — so each type is
> just a **request object supplying weights**, and rounding is solved once in a
> shared distributor. Money is a **long in paise**, never a double, and leftover
> paise go to whoever was rounded down hardest so splits always reconcile.
> Balances are stored
> **net per pair** for O(1) reads, with the expense log kept for history. Debt
> simplification is greedy, since the exact minimum is NP-hard.

## Sample output

```
── 3. ByAmount — Bob pays ₹500, uneven shares ──────────────
  splits: [Alice ₹150.00, Bob ₹250.00, Carol ₹100.00]
    Carol owes Alice ₹100.00
    Carol owes Bob ₹100.00
    Alice owes Bob ₹50.00        <- NOT ₹150: netted against dinner
```

```
── 9. invalid expense is rejected at construction ──────────
  rejected: splits sum to ₹60.00, expected ₹100.00
  rejected: percentages sum to 80.0, must be 100
  balance sheet is untouched by the failures
```

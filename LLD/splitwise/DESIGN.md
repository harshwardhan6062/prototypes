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
· split types: EQUAL, EXACT, PERCENT   (must be extensible)
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

## 3. Split strategies

The extensibility question. Strategy pattern:

```java
interface SplitStrategy {
    List<Split> calculate(long amount, List<User> participants, List<Double> values);
}
```

```java
class EqualSplit implements SplitStrategy {
    public List<Split> calculate(long amount, List<User> users, List<Double> ignored) {
        long base = amount / users.size(), rem = amount % users.size();
        List<Split> out = new ArrayList<>();
        for (int i = 0; i < users.size(); i++)
            out.add(new Split(users.get(i), base + (i < rem ? 1 : 0)));   // spread the remainder
        return out;
    }
}
```

**The rounding answer:** ₹100 split three ways is 33.34 / 33.33 / 33.33. Someone
must absorb the extra paisa, so distribute the remainder one paisa at a time and
the splits always sum exactly to the total. Interviewers look for this.

`SplitStrategyFactory` maps `SplitType -> SplitStrategy` so the enum-to-class
knowledge lives in one place.

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
1. strategy computes the splits        <- no Expense exists yet
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
Strategy   split algorithms — new one is a new class, not an edit
Factory    SplitType -> SplitStrategy
Facade     SplitwiseService hides the subsystem
Observer   notifications on expense added        (mention as an extension)
```

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
SplitwiseService    orchestrates: strategy -> Expense -> log -> deltas
   |
SplitStrategy       computes the splits          (swappable)
   |
Expense             validates + freezes          (immutable event)
   |
BalanceSheet        accumulates net deltas       (derived state)
```

Nothing above `BalanceSheet` knows how balances are stored; nothing below
`SplitwiseService` knows about split types.

## The 60-second opener

> Users, Groups, Expenses. An expense has a payer, an amount, and a list of
> splits that must sum exactly to the amount. Split types go behind a
> **Strategy** interface so adding one is a new class, not an edit. Money is a
> **long in paise** — never a double — and on uneven divides I distribute the
> remainder paisa by paisa so the splits always reconcile. Balances are stored
> **net per pair** for O(1) reads, with the expense log kept for history. Debt
> simplification is greedy, since the exact minimum is NP-hard.

## Sample output

```
── 3. EXACT split — Bob pays ₹500, uneven shares ───────────
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

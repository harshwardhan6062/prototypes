# lld

Runnable low-level-design answers, for interview practice. Single-file Java —
no build step:

```bash
brew install openjdk
export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"
java splitwise/Splitwise.java
java AbstractFactoryDemo.java
```

| File | Pattern / problem |
| --- | --- |
| [`splitwise/Splitwise.java`](./splitwise/Splitwise.java) | Splitwise — Strategy, Factory, Facade, immutable events + derived state |
| [`AbstractFactoryDemo.java`](./AbstractFactoryDemo.java) | Abstract Factory — families of matching products |

## Splitwise — the points that score

**Money is `long` in paise, never `double`.** `0.1 + 0.2 != 0.3`, and a lost
paisa per expense compounds.

**Rounding is distributed, not dropped.** `₹100 / 3` becomes `33.34 / 33.33 /
33.33` — the remainder is handed out a paisa at a time so splits always
reconcile to the total.

**The Expense is an immutable event; the BalanceSheet is a materialised view.**
Adding applies deltas, deleting applies the reverse. The sheet is never
recomputed from the log. Same shape as WAL to memtable, or Kafka log to consumer
state.

**Validation lives in the `Expense` constructor.** An invalid expense never
becomes an object, so it can never reach the balance sheet — atomicity by
construction, with no rollback path to get wrong.

**Balances are net and one-directional.** If Alice owes Bob ₹150 and Bob owes
Alice ₹100, only `Alice owes Bob ₹50` is stored. Never both directions.

**Debt simplification is greedy** — at most n−1 transfers. Say out loud that the
exact minimum is NP-hard.

## Extensibility, the usual follow-up

Adding a split type is one new class plus one registry entry; nothing existing
changes. That is the Strategy pattern earning its place, and it is why the
`SplitStrategy` interface exists rather than a `switch` inside the service.

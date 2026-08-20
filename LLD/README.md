# LLD

Low-level design notes and runnable answers, for interview practice.
Single-file Java — no build step.

```bash
brew install openjdk
export PATH="/opt/homebrew/opt/openjdk/bin:$PATH"

java splitwise/Splitwise.java          # full design
java splitwise/SplitwiseFlow.java      # traced, prints state at each step
java patterns/AbstractFactoryDemo.java
```

## Contents

| | |
| --- | --- |
| [`splitwise/DESIGN.md`](./splitwise/DESIGN.md) | The interview answer, in delivery order |
| [`splitwise/Splitwise.java`](./splitwise/Splitwise.java) | Complete — 3 split types, balances, settle, simplify, validation |
| [`splitwise/SplitwiseFlow.java`](./splitwise/SplitwiseFlow.java) | Same design, traced, to see the object lifecycle |
| [`patterns/builder.md`](./patterns/builder.md) | Why the entity can't be its own builder |
| [`patterns/abstract-factory.md`](./patterns/abstract-factory.md) | Families of matching products |
| [`patterns/AbstractFactoryDemo.java`](./patterns/AbstractFactoryDemo.java) | Runnable |

## The recurring ideas

**Immutable event + derived state.** An `Expense` is a frozen record of something
that happened; the `BalanceSheet` is a materialised view of those events. Adding
applies deltas, deleting applies the reverse, and the view is never recomputed
from the log. The same shape as WAL to memtable, Kafka log to consumer state, and
Redis AOF to in-memory data.

**Validate in the constructor.** If an invalid object cannot be constructed, it
cannot reach anything downstream — which removes the rollback path entirely, one
fewer thing to get wrong. It is also the argument for a separate builder: the
half-built state lives in the builder, so the entity has none.

**Put the varying part behind an interface.** Split types behind `SplitStrategy`,
UI families behind `UIFactory`. Adding one becomes a new class plus a registry
entry, never an edit to working code.

**Money is an integer in the smallest unit.** Never a float. And when a division
doesn't come out even, distribute the remainder rather than dropping it, so the
parts always sum back to the whole.

## Splitwise — the specific points that score

**Money is `long` in paise.** `0.1 + 0.2 != 0.3`, and a lost paisa per expense
compounds.

**Rounding is distributed.** `₹100 / 3` becomes `33.34 / 33.33 / 33.33`, handed
out a paisa at a time, so splits always reconcile to the total.

**Balances are net and one-directional.** If Alice owes Bob ₹150 and Bob owes
Alice ₹100, only `Alice owes Bob ₹50` is stored. Never both directions at once.

**Delete reverses deltas.** O(participants), not a replay of the whole log.

**Debt simplification is greedy** — at most n−1 transfers. Say out loud that the
exact minimum is NP-hard.

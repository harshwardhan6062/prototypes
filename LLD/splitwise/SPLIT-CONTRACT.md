# Rethinking the split contract

The first version's interface:

```java
List<Split> calculate(long amount, List<User> participants, List<Double> values);
```

What's wrong with it:

```
1. `values` is a grab-bag     null for EQUAL, rupees for EXACT, percents for PERCENT
2. parallel lists             values[i] must match participants[i] — reorder one, silent bug
3. Double for money           we said "long paise, never double", then broke it in EXACT
4. callers pass null          you must know which strategies ignore the third argument
5. validation scattered       PERCENT checks its sum, EXACT doesn't bother
6. no type safety             nothing stops percentages reaching ExactSplit
```

## The insight

All the split types are the **same operation** — hand out `total` in proportion
to some weights. Only the weights differ:

```
EQUAL    weights are all 1
PERCENT  weights are the percentages
EXACT    weights are the amounts themselves
SHARES   weights are the share counts
```

So there is one algorithm, and each request only supplies weights. Rounding gets
solved once instead of being re-derived in every strategy.

## The new contract

```java
sealed interface SplitRequest {
    Map<User, Long> weights(long total);   // relative weights; absolute scale irrelevant
    List<User> participants();             // stable order, for reproducible rounding
}
```

with `Equally`, `ByAmount`, `ByPercent`, `ByShares` as records implementing it,
and a single `Distributor.distribute(total, request)`.

## What improved

| | old | new |
| --- | --- | --- |
| third argument | `List<Double>` meaning three things | each request carries its own typed data |
| EQUAL's argument | `null` | does not exist |
| user to value link | parallel lists, index-matched | `User` is the map key — cannot desync |
| money in EXACT | `Double` rupees | `long` paise |
| percent validation | inside the strategy | in the record's constructor |
| rounding logic | written three times | written once |
| type safety | percentages could reach `ExactSplit` | compiler prevents it |

## The bug this makes impossible

```java
// old
calculate(amount, [alice, bob, carol], [50.0, 25.0, 25.0])
```

Reorder the participant list and the percentages silently attach to the wrong
people. No error, wrong money.

```java
// new
new ByPercent(ordered(entry(alice, 50.0), entry(bob, 25.0), entry(carol, 25.0)))
```

The user and their value are one thing. There is nothing to keep in sync.

## Rounding

Largest-remainder: everyone gets the floor of their share, the leftover paise go
to whoever was rounded down hardest, ties broken by user id so it is
deterministic.

```
₹100 / 3             [₹33.34, ₹33.33, ₹33.33]   sums exactly
₹0.10 / 3            [₹0.04, ₹0.03, ₹0.03]      sums exactly
33.33/33.33/34.34%   [₹33.33, ₹33.33, ₹33.34]   sums exactly
```

## Two honest caveats

**This is no longer textbook GoF Strategy.** It is "replace conditional with
polymorphism" applied to a request object. If asked: the varying behaviour is
still behind an interface, it has just been attached to the data it needs so the
two cannot be mismatched. That is the better answer anyway.

**One asymmetry remains.** `ByPercent` validates in its constructor, so an
invalid one cannot exist. `ByAmount` cannot — it needs the total to check
against, and the request does not know it — so it throws from `weights(total)`
instead. Passing the total into the constructor would make it symmetric, at the
cost of a request that can no longer be reused across totals.

## Extending it

`ByShares` was added after the fact. It required no change to any existing type —
one new record, and it works with the same distributor. That is the test of
whether the abstraction was right.

# Candies — two directions, folded into one pass

Notes for [`src/java/dynamic/Candies.java`](../../src/java/dynamic/Candies.java),
tested by [`test/java/dynamic/CandiesTest.java`](../../test/java/dynamic/CandiesTest.java).

**Problem** Children stand in a line with ratings `arr[n]`. Every child gets at least one candy, and
a child rated **higher than an immediate neighbour** must get strictly more candies than that
neighbour. Minimise the total. Constraints `1 ≤ n ≤ 10⁵`, `1 ≤ arr[i] ≤ 10⁵`; the answer is returned
as a `long`, and [the reason](#the-answer-does-not-fit-in-an-int) is not decoration.

Only the higher child is constrained. Equal ratings constrain nobody, and a lower rating imposes
nothing on its neighbour — which is why `[1, 2, 2]` costs `1 + 2 + 1 = 4` and not 6.

## The recurrence

Every rule is a *lower bound* on one child's count, and the bounds split by direction:

```
L[i] = ratings[i] > ratings[i-1] ? L[i-1] + 1 : 1        L[0] = 1     left-to-right
R[i] = ratings[i] > ratings[i+1] ? R[i+1] + 1 : 1        R[n-1] = 1   right-to-left

candy[i] = max(L[i], R[i])                               answer = Σ candy[i]
```

| Part | Meaning |
|---|---|
| **State** | `L[i]` — the answer for `ratings[0..i]` if the right neighbour did not exist; `R[i]` the mirror for the suffix |
| **Transition** | a child rated above its predecessor must outbid it, so it takes that child's answer plus one; otherwise the chain breaks and it drops back to the floor |
| **Base case** | `1` — the floor every child is owed, and the reason a broken chain restarts rather than continues |
| **Answer** | `Σ max(L[i], R[i])` |

Sample 1, `[2, 4, 2, 6, 1, 7, 8, 9, 2, 1]` → 19:

```
 i        0  1  2  3  4  5  6  7  8  9
 rating   2  4  2  6  1  7  8  9  2  1
 L        1  2  1  2  1  2  3  4  1  1     <- climbs on a rise, resets otherwise
 R        1  2  1  2  1  1  1  3  2  1     <- the same rule walked backwards
 candy    1  2  1  2  1  2  3  4  2  1     = 19
                                 ^  ^
                     L carries the climb 1,7,8,9 at indices 4..7; only R knows
                     that index 8 heads a descent and cannot be left at 1.
```

Both directions are needed, and each is blind on its own: `L` alone underfeeds every descent, `R`
alone every ascent.

## Why the maximum is right

This is the step that has to be argued — the rest is bookkeeping.

**It is legal.** Take a rise, `ratings[i] > ratings[i-1]`. Then `L[i] = L[i-1] + 1`, and
`R[i-1] = 1` because `ratings[i-1] > ratings[i]` is false, so:

```
candy[i] ≥ L[i] = L[i-1] + 1 > max(L[i-1], R[i-1]) = candy[i-1]        (R[i-1] = 1 ≤ L[i-1])
```

The falling case is the mirror, through `R`. Equal neighbours constrain nothing. So every rule holds.

**It is minimal.** Let `c` be *any* legal handout. Then `c[i] ≥ L[i]` by induction along the ascent:
`c[i] ≥ 1 = L[i]` where the chain breaks, and `c[i] > c[i-1] ≥ L[i-1]` where it does not, so
`c[i] ≥ L[i-1] + 1 = L[i]`. Symmetrically `c[i] ≥ R[i]`, hence `c[i] ≥ max(L[i], R[i])` at every
index. A legal handout that meets every one of those bounds exactly is therefore *the* cheapest, and
the previous paragraph showed this one is legal. No exchange argument needed: the minimum is forced
pointwise, not just in total.

## The DP underneath: longest chain in a constraint graph

Draw an edge `j → i` for each adjacent pair where `ratings[i] > ratings[j]` — read as "`i` must
outbid `j`". The rules are then exactly "`candy[i]` exceeds every predecessor", so

```
candy[i] = 1 + (length of the longest chain of edges ending at i)
```

which is longest-path-in-a-DAG, the canonical shape of a dynamic program. Ratings strictly increase
along every edge, so no chain can revisit an index; on a line, a chain that never revisits is a
contiguous run in one direction. **That is why two prefix scans suffice** — a chain cannot turn
around, so it is either entirely leftward or entirely rightward, and `L` and `R` measure exactly
those two.

The overlap is the usual one: the chain ending at `i` contains the chain ending at `i-1`. Recomputed
from scratch per index the scan is Θ(n²) on a monotone line; remembering one number per index makes
it Θ(n).

## What it is not

**Not one greedy pass.** Walking left to right and handing out `previous + 1` on a rise, `1`
otherwise, is precisely `L` — and it is wrong wherever the line falls:

| Input | one forward pass | answer |
|---|---|---|
| `[3, 2, 1]` | `[1, 1, 1]` = 3 | `[3, 2, 1]` = 6 |
| `[1, 5, 4, 3, 2, 1]` | `[1, 2, 1, 1, 1, 1]` = 7 | `[1, 5, 4, 3, 2, 1]` = 16 |

**Not "local minima get 1".** A child can be owed more than the floor without being a peak:
`[3, 2, 2, 1]` → `[2, 1, 2, 1]`. The second `2` is neither a local maximum nor rising, but it heads a
descent, so `R` lifts it.

**Not usefully solved by sorting**, though it is correct: process indices in rating order and set
`candy[i] = 1 + max` over already-assigned lower-rated neighbours. Every strictly lower neighbour is
settled by then, so it gives the same handout — at O(n log n), the sort being the only reason it is
not linear.

## The memo table

Written as the recurrence reads, the two tables are kept and summed — `candiesWithTable(int[])`:

```java
for (int i = 0; i < n; i++)      left[i]  = i > 0     && r[i] > r[i - 1] ? left[i - 1] + 1  : 1;
for (int i = n - 1; i >= 0; i--) right[i] = i < n - 1 && r[i] > r[i + 1] ? right[i + 1] + 1 : 1;
for (int i = 0; i < n; i++)      total   += Math.max(left[i], right[i]);
```

Θ(n) time, O(n) memory — two `int[]` of the line's length. This is the form to *read*; it is the
recurrence transcribed, and the individual shares survive the call, which the fold discards as it
goes.

**What the memo is worth, and what it is not.** The tables are what turn Θ(n²) into Θ(n): without
them, `L[i]` and `R[i]` have to be rescanned from `i` back along their runs, and on a monotone line
every one of those rescans is the full length. [Measured](#measured-ad-hoc), that is 575 ms against
0.25 ms at n = 80 000.

But that is the *whole* of what remembering buys here, and it is worth being exact about why. The
classic memo picture is Fibonacci: one subproblem requested from many places, the table folding an
exponential tree down to `n` entries. Nothing like that happens on this recurrence. Every entry has
exactly one dependent — `L[i]` feeds only `L[i+1]`, `R[i]` only `R[i-1]` — so the dependency graph is
two chains rather than a tree, and no entry is ever *requested* twice. What the table removes is not
repeated lookups but repeated **derivation**: re-walking the chain from scratch at every index. That
is the difference between this drill and [Max Subset Sum](max-subset-sum.md), where the recurrence
branches and memoising is worth an exponential rather than a factor of `n`.

Which is also why the table can go. A dependency window that is one index wide does not need an
array to hold it.

## Rolling the table up

The memo has O(n) entries but only O(1) of them are ever live: `R` is filled right to left, so it
cannot be computed on the same forward pass as `L`. It does not have to be *stored*, though — only its
contribution to the sum, and `R[i] > L[i]` **only inside a descending run**. So walk the descent and
pay as you go:

- extending a descent to length `d` re-indexes the children already in it — each needs one more —
  and the newcomer takes 1. That is `d` extra candies in total, the gap between two triangular
  numbers, `d(d+1)/2 − (d−1)d/2`;
- the peak the descent hangs off needs `d + 1`, but it already holds `ascent + 1` from the climb, so
  it costs **one more only at the moment the descent outgrows that ascent**, and nothing after.

Five numbers carry it: the previous rating, the current `ascent`, the current `descent`, the
`peakAscent` the descent fell from, and the running total. `[1, 5, 4, 3, 2, 1]`:

```
 i  rating  step   ascent descent peakAscent | delta   total   handout so far
 0       1  first       0       0          0 |    +1       1   [1]
 1       5  rise        1       0          1 |    +2       3   [1,2]
 2       4  fall        0       1          1 |    +1       4   [1,2,1]      peak still tall enough
 3       3  fall        0       2          1 |    +3       7   [1,3,2,1]    descent outgrew it: +1
 4       2  fall        0       3          1 |    +4      11   [1,4,3,2,1]
 5       1  fall        0       4          1 |    +5      16   [1,5,4,3,2,1]
```

A plateau resets all three counters — equal neighbours constrain neither child, so a run cannot span
one. That single line is what keeps `[3, 2, 2, 1]` at 6: the descent restarts after the plateau
instead of running through it, which would bill it as a four-step fall and charge 10.

## Complexity ladder

| Approach | Time | Space | |
|---|---|---|---|
| Search every handout in `{1..n}ⁿ` | Θ(nⁿ) | O(n) | the test's oracle, `n ≤ 6` |
| Start at 1s, repair violations until stable | O(n²) | O(n) | the same least fixed point, reached by sweeps |
| Sort by rating, assign in order | O(n log n) | O(n) | correct; the sort is the whole cost |
| Rescan each run, nothing remembered | Θ(n²) | O(1) | what the memo table removes |
| Two passes, `L` and `R` kept | Θ(n) | O(n) | `candiesWithTable` — the readable form |
| **One pass, counters only** | **Θ(n)** | **O(1)** | **`candies`** — the same memo, rolled up |

Θ(n) is optimal: changing one rating can change the total, so every rating must be read.

## The answer does not fit in an `int`

The bound `n ≤ 10⁵` is already past `Integer.MAX_VALUE`. A strictly increasing line of 100 000
children costs `100000·100001/2 = 5 000 050 000` candies — 2.3× an `int`. HackerRank's signature
returns `long` for this reason, and an `int` accumulator would silently wrap to `705 082 704`.
Per-child counts stay small (at most `n`); only the sum overflows.

## Measured, ad hoc

Not reproduced by the build — taken once on this machine, JDK 25, `-Xmx512m`, after warm-up.

**What the memo table buys**, on the worst case for going without it — one ascending run, so every
rescan is the full length. The first two columns are the same recurrence, remembered and not
(one timed run each, so the sub-millisecond columns are mostly timer):

| n (one ascending run) | no memo, rescan each run | `candiesWithTable` | `candies` |
|---:|---:|---:|---:|
| 20 000 | 42.0 ms | 0.15 ms | 0.27 ms |
| 40 000 | 143.3 ms | 0.37 ms | 0.07 ms |
| 80 000 | 574.8 ms | 0.25 ms | 0.03 ms |

The first column quadruples per doubling — Θ(n²), cleanest across the last two rows at 4.01×.

**What dropping the table buys**, once both are linear (best of 9):

| n = 10 000 000, random walk | Time | Table memory |
|---|---:|---:|
| `candiesWithTable` | 108 ms | 80 MB |
| `candies` | 42 ms | 0 |

**2.5×**, from one pass over 40 MB of input instead of three passes moving 240 MB between RAM and the
two tables — the recurrence is memory bound, so the table the fold does not allocate is also the time
it does not spend. And since it holds no state proportional to `n`, a line can be priced straight off
a stream: 200 000 000 children in 97 ms, on a heap that could not have held the input array (762 MB)
at all.

## Test oracles

Three independent checks, the house pattern:

- **exhaustive** — every line of up to 6 children is priced by searching all of `{1..n}ⁿ` for the
  cheapest legal handout. No minimal handout gives a child more than `n` (no chain is longer than the
  line), so the search is complete, and it checks the *problem statement* rather than a rephrasing of
  the recurrence. Two enumerations feed it: all lines over 3 rating values, and — since three values
  cannot build a run longer than three — one representative of each of the `3ⁿ⁻¹` rise/level/fall
  shapes, which is where the long runs and the peak rule get their exhaustive check;
- **two-pass table** — the same recurrence with `L` and `R` actually materialised, on 40 000 random
  lines and twice at 10 000 000 (uniform, and a ±1 walk so runs are long and the peak bookkeeping is
  under load). Never storing `R` is the one step that could plausibly break at scale. The test keeps
  its own copy of this rather than calling `candiesWithTable`, so that the oracle stays independent
  of the code under test; both shipped forms are then checked against it and against each other;
- **closed forms** — a strictly increasing line of `n` costs `n(n+1)/2`, checked at 10⁵ for the
  `long` boundary and at 2·10⁸ through a stream, where passing at all is the evidence that the state
  really is O(1).

## See also

- [Max Subset Sum](max-subset-sum.md) — the same "roll the table up to a couple of variables" move,
  there because the transition reaches back only two states, here because one of the two directions
  can be paid off as it is walked.

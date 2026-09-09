# Max Subset Sum — why it is dynamic programming

Notes for [`src/java/dynamic/MaxSubsetSum.java`](../../src/java/dynamic/MaxSubsetSum.java),
tested by [`test/java/dynamic/MaxSubsetSumTest.java`](../../test/java/dynamic/MaxSubsetSumTest.java).

**Problem** Given `int arr[n]`, pick a subset containing no two
adjacent elements, maximising its sum. The empty subset counts, so the answer is never negative —
an all-negative input returns `0`.

## The recurrence

```
best[i] = max( best[i - 1] , best[i - 2] + arr[i] )        best[-1] = best[-2] = 0
```

| Part | Meaning |
|---|---|
| **State** | `best[i]` — the answer for the prefix `arr[0..i]`, a smaller instance of the same problem |
| **Transition** | skip `arr[i]` and carry the prefix answer over, or take it, which bars `arr[i-1]` and leaves the prefix ending at `i-2` |
| **Base case** | `0` for both empty prefixes — this is what puts the empty subset in the running |
| **Answer** | `best[n-1]` |

The base case of `0` is load-bearing. It makes the state monotone non-decreasing
(`best[i] = max(best[i-1], …) ≥ best[i-1] ≥ … ≥ 0`), so the answer can never come out negative and
an all-negative input needs no special case. The `best[i-2] + arr[i]` term *can* be negative; the
`max` discarding it is exactly the decision "taking this element is worse than skipping it":

```
[-2, 1, 3, -4, 5]
  i  value | best[i-2]+value   best[i-1] | best[i]  taken?
  0     -2 |              -2           0 |       0  skip     <- negative candidate discarded
  1      1 |               1           0 |       1  take
  2      3 |               3           1 |       3  take
  3     -4 |              -3           3 |       3  skip     <- negative candidate discarded
  4      5 |               8           3 |       8  take
```

## Condition 1: optimal substructure

Every valid subset of `arr[0..i]` falls into exactly two cases, and in each the remainder is itself
an optimal solution to a smaller prefix:

- **omits `arr[i]`** → it is a valid subset of `arr[0..i-1]`, and the best such is `best[i-1]`;
- **takes `arr[i]`** → `arr[i-1]` is barred, so the rest is a valid subset of `arr[0..i-2]`, and the
  best such is `best[i-2]`.

The exchange argument closes it: if the remainder in the second case were not optimal for
`arr[0..i-2]`, substituting that prefix's optimum would give a strictly larger total while staying
non-adjacent — a contradiction. This is what *licenses* the recurrence; without it, it would be a
guess that happens to pass the samples.

## Condition 2: overlapping subproblems

`best[i]` needs `best[i-1]`, which needs `best[i-2]` — which the other branch needs too. Solved by
plain recursion, the same prefixes get re-derived exponentially often. Measured call counts for the
**identical** recurrence, with and without a memo (same answers, verified per row):

| n | naive recursion calls | memoized calls | blowup |
|---:|---:|---:|---:|
| 10 | 287 | 21 | 14× |
| 20 | 35 421 | 41 | 864× |
| 30 | 4 356 617 | 61 | 71 420× |
| 36 | 78 176 337 | 73 | 1 070 909× |

Both columns are exact closed forms, matching all four rows:

- **memoized** = `2n + 1` — each of the *n* distinct subproblems is solved once.
- **naive** = `2·Fib(n+2) − 1`, growing like φⁿ (φ ≈ 1.618; the 30→36 ratio is 17.9 = φ⁶).
  `Fib(n+2)` is precisely the number of non-adjacent subsets of *n* elements, so the naive recursion
  is brute-force enumeration wearing a recurrence's clothes.

Collapsing Fibonacci-many subsets into *n* subproblems is what makes this *dynamic* programming
rather than merely recursion. Harvesting that gap **is** the technique.

## What it is not

**Not greedy.** Taking the largest still-legal element is not safe — one local pick can block two
better ones:

| Input | greedy | DP |
|---|---:|---:|
| `[4, 5, 4]` | 5 | **8** |
| `[10, 11, 10, 11, 10]` | 22 | **30** |
| `[3, 5, -7, 8, 10]` | 15 | 15 (greedy gets lucky) |

**Not divide-and-conquer.** D&C needs *disjoint* subproblems. These overlap by construction, and
that is exactly the fork in the road where D&C becomes DP.

## Complexity ladder

| Approach | Time | Space | Note |
|---|---|---|---|
| Enumerate subsets (bitmask) | O(2ⁿ · n) | O(1) | the test oracle for n ≤ 12 |
| Naive recursion on the recurrence | O(φⁿ) | O(n) stack | right recurrence, no reuse |
| Top-down + memo | O(n) | O(n) + O(n) stack | **overflows the stack around n ≈ 10⁴–10⁵** |
| Bottom-up table | O(n) | O(n) | `tableDp` in the test, the oracle for n = 10⁷ |
| Bottom-up, two rows | **O(n)** | **O(1)** | shipped in `MaxSubsetSum` |

## Rolling the table up

There is no `dp[]` array in `MaxSubsetSum`, but the table has not gone away — the transition reaches
back at most two rows, so only two are ever live. The private `Best` class *is* those two rows
(`forPrefixBeforeLast` = `best[i-2]`, `forPrefix` = `best[i-1]`). Table elimination is a space
optimisation applied *after* the DP, never an alternative to it, and it is available here only
because the transition's reach is bounded by 2. `tableDp` in the test is the same algorithm with the
array materialised, which is why it works as an oracle: not a different approach, the unrolled form
of the identical DP.

## Consequences for very large sets

Three properties of this DP matter once *n* leaves the problem's stated 10⁵:

1. **O(1) memory means the input need not exist.** Two scalars are the whole table, so
   `maxSubsetSumAsLong(IntStream)` folds a set straight off a file, cursor or generator. The test
   folds 2·10⁸ elements (~800 MB as ints) under a 512 MB heap — only possible without
   materialising them.
2. **`int` overflows long before the algorithm strains.** 10⁷ random elements in ±10 000 sum to
   **18 631 707 043**, 8.7× past `Integer.MAX_VALUE`. State is accumulated in a `long`;
   `maxSubsetSum(int[])` keeps the HackerRank signature and throws `ArithmeticException` via
   `Math.toIntExact` rather than wrapping silently. Checking only the final value is sound because
   the state is monotone — if the answer fits, every intermediate did. With `int` inputs the `long`
   accumulator needs ~10¹⁵ elements to overflow.
3. **Bottom-up has no stack depth.** The memoized top-down form of the same recurrence dies of
   `StackOverflowError` first, which is the trap this problem sets.

Measured single-threaded, warm JIT, JDK 17.0.17 (Temurin), Intel i7-14650HX:

| Workload | Time |
|---|---:|
| 10⁷ elements, two-row DP | 4–8 ms |
| 10⁷ elements, O(n) table DP | 17–21 ms |
| 10⁸ elements, streamed | 40–49 ms (~0.4 ns/element) |

Further: the recurrence is a (max, +) tropical matrix product, which is associative — so a parallel
prefix scan over multiple cores is possible. Not implemented; single-threaded is already ~2.5·10⁹
elements/s.

## Variant worth knowing

If a variant required a **non-empty** subset, this code would be wrong: `[-2, -4, -6, -1]` would
answer `-1` (the least-bad single element), not `0`. That needs different base cases — a
"no legal choice yet" sentinel of `Long.MIN_VALUE / 2` forcing the first pick, or simply
`max(result, max(arr))` when the result is 0. HackerRank's statement explicitly allows the empty
subset, so the shipped behaviour matches the problem as given.

## Test oracles

Three independent checks at three scales, so nothing rests on the implementation being its own
witness:

| Oracle | Scale | Catches |
|---|---|---|
| Bitmask brute force | 2 000 random arrays, n ≤ 12 | a wrong recurrence |
| O(n)-memory `tableDp` | 10⁷ random elements | a wrong two-row collapse |
| Analytic answers | 10⁷ alternating, 2·10⁸ streamed | overflow, order, memory |

Mutation-checked — every deliberate break is caught: allowing adjacency → 12 failures + 1 error, a silent
`(int)` cast → 2, `int` state instead of `long` → 5, `forEach` instead of `forEachOrdered` → exactly
the parallel-stream test.

```
mvn test        # 24 tests, ~0.3 s
```

## Reproducing the call counts

```java
static long calls;

static long naive(int[] a, int i) {                 // no memo: O(φⁿ)
    calls++;
    if (i < 0) return 0;
    return Math.max(naive(a, i - 1), naive(a, i - 2) + a[i]);
}

static long memo(int[] a, int i, long[] cache, boolean[] done) {   // same recurrence: O(n)
    calls++;
    if (i < 0) return 0;
    if (done[i]) return cache[i];
    done[i] = true;
    return cache[i] = Math.max(memo(a, i - 1, cache, done), memo(a, i - 2, cache, done) + a[i]);
}
```

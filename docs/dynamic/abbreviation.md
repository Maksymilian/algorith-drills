# Abbreviation — the DP and where its time goes

Notes for [`src/java/dynamic/Abbreviation.java`](../../src/java/dynamic/Abbreviation.java),
tested by [`test/java/dynamic/AbbreviationTest.java`](../../test/java/dynamic/AbbreviationTest.java).

**Problem**Given `a` (mixed case) and `b` (uppercase), decide whether
`a` can be made equal to `b` by capitalizing zero or more of its lowercase letters and deleting
every lowercase letter left over. Constraints: `q ≤ 10`, `|a|, |b| ≤ 1000`.

The asymmetry between the two kinds of letter is the entire problem. A lowercase letter has a
choice — capitalize it and spend it on `b`, or drop it — while an **uppercase letter has none**:
no operation removes one, so every uppercase letter of `a` must be matched, in order.

## The recurrence

```
c lowercase:  reachable[i][j] = reachable[i-1][j]                     // drop c
                              | reachable[i-1][j-1] && upper(c) == d  // capitalize c
c uppercase:  reachable[i][j] = reachable[i-1][j-1] && c == d         // c must be spent
```

| Part | Meaning |
|---|---|
| **State** | `reachable[i][j]` — the first `i` letters of `a` can produce the first `j` of `b` |
| **Transition** | `c = a[i-1]`, `d = b[j-1]`; a lowercase `c` keeps both options alive, an uppercase `c` has one |
| **Base case** | `reachable[i][0]` is true only while `a[0..i-1]` is all lowercase; `reachable[0][j>0]` is false |
| **Answer** | `reachable[n][m]` |

**Why not greedy.** `a = "aA"`, `b = "A"`. Taking the first letter that fits capitalizes the `'a'`
and strands the `'A'` → NO; the answer is YES, by *dropping* the `'a'` and matching the `'A'`.
Whether spending a lowercase letter pays off depends on the whole remaining string, so both branches
must stay alive. That is what the table is for.

## Efficiency

### Why it is Θ(n·m)

The state space is the product of the two prefix lengths — `(n+1)(m+1)` cells — and each transition
reads at most two neighbours and does O(1) character work. Nothing more, nothing less:

```
Θ(n·m + n)      time        n·m cells, plus O(1) per row for charAt / isLowerCase / toUpperCase
Θ(min(n, m))    space       one row of the table
```

The `+ n` term is not pedantry: at `m = 2` the per-row work is comparable to the row itself, which
is why the measured cost per cell rises sharply for very short targets (table below).

### There is no best case

This DP is **data-oblivious**: every cell is computed for every input, with no early exit and no
data-dependent branch on the loop bounds. Best case, average case and worst case are all Θ(n·m).
That is unusual and worth naming, because it means the only ways to go faster are (a) rejecting the
query before the loop starts, or (b) shrinking the constant per cell. Both are covered below.

### Measured scaling

`|a| = |b| = k`, alphabet `{a,b}` vs `{A,B}`, best of 5 warm runs, single-threaded, JDK 17.0.17
(Temurin), Intel i7-14650HX:

| \|a\| | \|b\| | cells | time | vs previous | ns/cell |
|---:|---:|---:|---:|---:|---:|
| 1 250 | 1 250 | 1 562 500 | 0.56 ms | — | 0.359 |
| 2 500 | 2 500 | 6 250 000 | 2.20 ms | 3.92× | 0.352 |
| 5 000 | 5 000 | 25 000 000 | 8.90 ms | 4.05× | 0.356 |
| 10 000 | 10 000 | 100 000 000 | 35.07 ms | 3.94× | 0.351 |
| 20 000 | 20 000 | 400 000 000 | 139.54 ms | 3.98× | 0.349 |

Doubling both strings multiplies the time by 3.92–4.05×, and the cost per cell stays flat within
3%. That is textbook Θ(n·m) — the quadratic is in the *product*, not in either length.

### The product is what matters, not the lengths

| \|a\| | \|b\| | cells | time | note |
|---:|---:|---:|---:|---|
| 1 000 000 | 2 | 2 000 000 | 1.84 ms | 0.92 ns/cell — the `+ n` term dominates |
| 1 000 000 | 20 | 20 000 000 | 17.64 ms | 0.88 ns/cell — still row-overhead bound |
| 100 000 | 200 | 20 000 000 | 8.44 ms | 0.42 ns/cell |
| 10 000 | 2 000 | 20 000 000 | 7.16 ms | 0.36 ns/cell — inner loop long enough to amortize |
| 2 000 | 10 000 | — | 0.00 ms | `n < m` fast path: rejected without touching the table |

A million-letter `a` against a two-letter `b` is cheaper than 10 000 × 2 000, despite `a` being 100×
longer. Only `n·m` counts.

### Space: O(min(n, m))

The transition reads only row `i - 1`, so one row survives — `m + 1` booleans, ~10 KB at
`m = 10⁴`, against ~100 MB for the full `boolean[n+1][m+1]`. The row is updated **descending in
`j`** so that `reachable[j]` and `reachable[j-1]` still hold row `i - 1` when read; ascending
overwrites them first, which is the classic bug in this family (mutation-tested: 8 failures).

Orientation matters for the bound, not for the answer. Sweeping `j` outer and keeping a *column* of
`n + 1` booleans works too — with one carried scalar for the diagonal — giving O(n) space. Since the
`n < m` fast path guarantees `m ≤ n`, the row shipped here is already the smaller of the two
dimensions, so the space is O(min(n, m)) as it stands.

### Complexity ladder

`L` = number of lowercase letters in `a`, `w` = machine word (64).

| Approach | Time | Space | At n = m = 10⁴ |
|---|---|---|---|
| Enumerate capitalizations (the test's oracle) | O(2^L · n) | O(n) | 2^5000 — hopeless |
| Recursion on the recurrence, no memo | O(2^L) | O(n+m) stack | hopeless |
| Top-down + memo | O(n·m) | O(n·m) + O(n+m) stack | 100 MB + deep stack |
| Bottom-up table | O(n·m) | O(n·m) | 100 MB |
| **Bottom-up, one row** | **Θ(n·m + n)** | **O(min(n,m))** | **35 ms, 10 KB** |
| Bit-parallel row | Θ(n·m/w + n) | O(m/w) | ~64× fewer word ops |

The bit-parallel form is the one real constant-factor win left. Pack the row into `long[]` and the
whole transition becomes two word operations per 64 columns:

```
row_i = ((row_{i-1} << 1) & match[c])  |  (droppable ? row_{i-1} : 0)
```

where `match[c]` has bit `j` set iff `b[j-1] == upper(c)` — a 26-entry table built once in O(m·26/w).
Same Θ(n·m/w) class as bit-parallel LCS. Not implemented here: 0.35 ns/cell is already fast enough
that the drill is about the recurrence, not the word tricks.

### Is quadratic optimal?

Unknown, and worth being precise about. The closely related LCS and edit-distance DPs have
SETH-based conditional lower bounds ruling out strongly subquadratic algorithms, but that is not a
proof about *this* problem, and no reduction is claimed here. There is real exploitable structure —
`a`'s uppercase letters are forced anchors that must appear in `b` in order, and between consecutive
anchors a lowercase run can produce any subsequence of itself, which is a prefix-closed condition —
so a faster algorithm is not obviously impossible. The DP does not need it: at the stated
constraints a query is 10⁶ cells ≈ 0.4 ms.

## Prefilters: sound, cheap, and they do not move the bound

Two O(n+m) necessary conditions can reject a query before the table is touched:

- **counts** — `count_b(X) ≤ count_a(X) + count_a(x)` for every letter, case-insensitively;
- **subsequence** — `a`'s uppercase letters must appear in `b`, in order.

Measured at 10⁴ × 10⁴ (single-shot, warm):

| Case | DP | counts | subseq | fires? |
|---|---:|---:|---:|---|
| YES | 98 ms | 0.38 ms | 0.18 ms | neither |
| easy NO — stray `Z` in `a` | 39 ms | 0.22 ms | 0.32 ms | **subseq** |
| hard NO — right letters, wrong order | 39 ms | 0.44 ms | 0.18 ms | neither |

~0.5% overhead, ~100× saving when a filter fires. But a filter can **never** fire on a YES (no
necessary condition fails there) and never on the expensive NOs — the inputs where the DP grinds
are exactly the ones whose letters do line up. **Worst case stays Θ(n·m).** Coverage also collapses
as the alphabet narrows, which is when a NO stops being obvious:

| Random inputs | actually NO | counts reject | subseq reject |
|---|---:|---:|---:|
| 26-letter alphabet | 99.4% | 95.4% | 77.2% |
| 4-letter | 94.7% | 59.6% | 59.6% |
| 2-letter | 82.0% | 37.2% | 42.5% |

Neither filter is shipped. Two traps make them cost more than they look:
`b.charAt(j) - 'A'` is 32 for a lowercase letter, so the naive count filter throws
`ArrayIndexOutOfBoundsException` where the DP correctly answers NO (the suite catches this), and a
*case-sensitive* letter comparison wrongly rejects `a = "abc"`, `b = "ABC"` — a YES with no
uppercase in `a` at all. The `n < m` line clears the bar because it is one comparison with nothing
to get wrong; removing it leaves the suite green, confirming it is a pure fast path.

## Test oracles

| Oracle | Scale | Catches |
|---|---|---|
| Brute force over 2^L capitalizations | 5 000 random queries, \|a\| ≤ 10 | a wrong recurrence |
| O(n·m)-memory table, no fast path | \|a\| = 2 000, reachable targets | a wrong single-row collapse |
| Analytic | 10⁴ × 10⁴, and 10⁶ × 2 | scale, and the `+ n` regime |

The 21 known queries are checked against all three at once. The random cross-check asserts it found
more than 500 reachable cases, so it cannot degenerate into comparing NO against NO. Large targets
are built to be reachable by construction (keep every uppercase, capitalize some lowercase), then
`+ "Z"` makes a guaranteed NO — no letter of a `{a,b,A,B}` string can produce a `Z`.

Mutation-checked, all caught: uppercase made droppable → 4 failures; forgetting that an uppercase
letter cannot be left unspent → 3; ascending inner loop → 8; inverted `droppable` test → 13.
Removing the `n < m` fast path → still green, as it should be.

```
mvn test        # 51 tests across both drills, ~1 s
```

## See also

- [Max Subset Sum — why it is dynamic programming](max-subset-sum.md), for the 1D case and the
  optimal-substructure / overlapping-subproblems argument in full.

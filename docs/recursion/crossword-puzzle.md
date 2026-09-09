# Crossword Puzzle — backtracking, and what the pruning is worth

Notes for [`src/java/recursion/CrosswordPuzzle.java`](../../src/java/recursion/CrosswordPuzzle.java),
tested by [`test/java/recursion/CrosswordPuzzleTest.java`](../../test/java/recursion/CrosswordPuzzleTest.java).

**Problem** A grid of `+` walls and `-` blanks arrives with a
semicolon-separated word list, one word per slot. Fill every blank. Constraints: 10×10 grid,
at most 10 words, exactly one solution.

## Slots

A **slot** is a maximal run of two or more blanks, across or down. The grid is scanned once,
row-major, recording each run's start, length and direction. Every blank belongs to at least one
slot; a blank that no run of two reaches (walls on all four sides) is recorded as a one-letter slot
of its own, so no blank can be silently left unfilled.

The count is checked before the search starts: `slots.size() != words.length` is a malformed query,
not a puzzle with no solution, and it says so.

## The search

Placing a word rewrites the letters every crossing slot has to agree with, so no slot can be filled
by looking at it alone. That makes this a search, in the three-step backtracking shape:

```java
char[] overwritten = write(grid, slot, words[w]);              // choose
used[w] = true;
if (fill(grid, slots, index + 1, words, used)) return true;    // explore
used[w] = false;                                               // un-choose
restore(grid, slot, overwritten);
```

A word is a candidate when it matches the slot's length **and** agrees with every letter already
crossing that slot. The grid *is* the state: `fits` reads the current partial assignment straight
out of it, which is why undoing has to be exact.

### The one detail that is easy to get wrong

`restore` puts back the characters the word displaced — **not** `-`. Those characters are not all
blanks: a crossing word placed earlier may already have written letters into this slot, and blanking
them erases part of a word that is still placed. The grid then lies about the partial assignment,
`fits` starts accepting words that contradict letters already on the board, and the search can
complete with a grid that holds words nobody asked for.

That bug is invisible on every published puzzle. See the node counts below for why.

## Why this is not dynamic programming

The DP drills in [`docs/dynamic`](../dynamic) win by *reuse*: overlapping subproblems, each solved
once. Nothing overlaps here. A node is "these words are spent, in these slots", and two different
partial assignments are two different states that share no future — a memo table would grow as fast
as the search tree and never hit. All the leverage is in **pruning**: cutting branches before they
are explored, rather than remembering branches already explored.

## Cost

The search space is an assignment of `w` words to `w` slots, so **`w!` complete assignments**, and
the tree over partial assignments has `Σ P(w,k) ≈ e·w!` nodes. Each node scans `w` words and each
`fits` costs O(L) for slot length L, giving a worst case of `O(w! · w · L)` — on the order of 10⁹
character comparisons at the problem's bounds if pruning achieved nothing.

Measured (JDK 17.0.17, Intel i7-14650HX; `nodes` counts `fill` invocations; the minimum possible is
`w + 1`, one per slot plus the terminal call; `nodes (no sol)` replaces one word with an unusable
one so the search must exhaust instead of succeeding):

| puzzle | w | w! (naive) | nodes | nodes (no sol) | time |
|---|---:|---:|---:|---:|---:|
| statement example | 4 | 24 | **5** | 4 | 42 µs |
| sample 0 | 4 | 24 | **5** | 5 | 34 µs |
| sample 1 | 4 | 24 | **5** | 3 | 31 µs |
| sample 2 | 4 | 24 | **5** | 4 | 38 µs |
| 3×3 word square, 3-letter alphabet | 6 | 720 | 11 | 34 | 30 µs |
| 10×10, 10 slots, 26-letter alphabet | 10 | 3 628 800 | 19 | 40 | 64 µs |
| 10×10, 10 slots, 4-letter alphabet | 10 | 3 628 800 | 33 | 268 | 87 µs |
| 10×10, 10 slots, 2-letter alphabet | 10 | 3 628 800 | 214 | 3 755 | 97 µs |

Three things fall out of that table.

**Pruning does essentially all the work.** Ten words into ten slots is 3.6 million permutations; the
search visits 19 nodes. Length plus crossing-letter agreement is enough to make the tree nearly a
single path, a ~190 000× reduction — and the *bound* is untouched, exactly as with the abbreviation
prefilters. Nothing here proves a better worst case, only a better typical one.

**Pruning is only as strong as the alphabet.** Same grid, same 10 slots, same 10 words by count:
26 letters → 19 nodes, 2 letters → 214 nodes to solve and 3 755 to prove unsolvable. Fewer distinct
letters means more words agree at each crossing, so more branches survive. This is the same shape of
finding as the abbreviation prefilters losing their bite on a narrow alphabet: constraint strength,
not problem size, is what governs a pruned search.

**The published puzzles never backtrack.** `nodes = 5` for `w = 4` is the minimum — one call per
slot plus the terminal call. Every first candidate is correct; no slot is ever retried. So the
published puzzles cannot exercise the un-choose step *at all*, and a broken `restore` passes them
unnoticed. That is not a hypothesis: it is what the mutation testing found, and what the node counts
explain.

### Space

O(w) recursion depth plus the O(rows·cols) grid — and the grid is shared, not copied. Undoing in
place costs O(L) per node, where snapshotting the grid at every node would cost O(rows·cols): 10
characters against 100 at these bounds, and a full copy per node besides. The un-choose step is what
buys that, which is the second reason to get `restore` exactly right.

## How the missing test was found

The suite had 79 passing tests and five deliberate breaks were injected. Four died immediately:

| Mutation | Result |
|---|---|
| Never restore after a failed branch | 7 errors |
| Restore by blanking the slot instead of putting back what it displaced | **all 79 passed** |
| Forget to mark the word unused on backtrack | 7 errors |
| `fits` checks length only, ignoring crossing letters | 8 failures |
| Drop the one-letter-slot pass | 1 error |

The survivor is precisely the bug described above, and the node counts say why: none of the four
published puzzles retries a slot, so nothing ever calls `restore` while a crossing word is placed.

So the search for a separating input was automated instead of guessed — random 3–4 row grids over a
3-letter alphabet, half of them solvable by construction, running both versions and comparing. It
took **11 inputs**:

```
grid  = ---        words = ABA;CCA;ACA;ACC;CAC;BAC
        ---
        ---
correct           blanking restore
  ABA               ABC   <- not one of the six words
  CAC               CCA
  CCA               ACC
```

A 3×3 word square: six slots, every cell shared by two of them, so a retried slot always crosses a
placed one. It is now a regression test over all 720 orderings of the word list — whether the bug
shows depends on which word the retried slot is offered first — checked by the property validator
rather than one expected grid, since the square's transpose is a second valid solution.

## Test oracles

| Oracle | Scale | Catches |
|---|---|---|
| Regex slot finder over rows and transposed columns | every assertion | a wrong slot scan |
| Permutation counter, no pruning | the 5 fixed puzzles | non-unique puzzles, a wrong answer |
| Property validator: walls intact, no blank left, words match as a multiset | random + word square | corruption of any kind |

The counter confirms each published puzzle has **exactly one** solution, which is what makes the
expected-grid assertions meaningful. Order-independence is checked across every permutation of every
word list, and 200 randomly lettered puzzles are generated by filling a grid, reading the words back
out of it, then shuffling — a solution exists by construction, and the answer is validated on its own
terms because random letters can make a puzzle ambiguous.

```
mvn test        # 80 tests across three drills, ~0.9 s
```

## See also

- [Max Subset Sum — why it is dynamic programming](../dynamic/max-subset-sum.md)
- [Abbreviation — the DP and where its time goes](../dynamic/abbreviation.md)

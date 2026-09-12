# Left Rotation — one index map, and why the fewest writes lose

Notes for [`src/java/array/LeftRotation.java`](../../src/java/array/LeftRotation.java),
tested by [`test/java/array/LeftRotationTest.java`](../../test/java/array/LeftRotationTest.java).

**Problem** Given `int a[n]` and `d`, perform `d` left rotations on `a`. Sample: `[1,2,3,4,5]`
with `d = 4` → `[5,1,2,3,4]`. Constraints `1 ≤ n ≤ 10⁵`, `1 ≤ d ≤ n`.

## The trap is in the wording

"Perform `d` left rotations" describes a loop, and written as one it costs O(n·d). At the problem's
own upper bound that is 10¹⁰ element moves. Measured, `n = d = 100 000`:

| Approach | Time |
|---|---:|
| `d` rotations of one position each | 683 ms |
| One index map (`rotLeft`) | 68 µs |

**~10 000×**, and the gap is quadratic — it widens with every extra element. But the composition of
`d` single rotations is not a loop at all. It is one relabelling:

```
rotated[i] = a[(i + d) mod n]
```

Each element has exactly one destination, known in closed form, so nothing needs to be touched
twice and no rotation can cost more than O(n). Everything below is that single formula, evaluated
in a different order.

## Normalising d

The formula only needs `d mod n`, which is why `Math.floorMod(d, n)` buys three cases the problem
never asks for, for free:

| `d` | `floorMod(d, n)` | meaning |
|---|---|---|
| `0` or `n` | `0` | identity |
| `> n` | wraps | `d = 17, n = 5` → shift 2 |
| **negative** | wraps the other way | **a right rotation** |

`Integer.MIN_VALUE` is included, and it is the value that catches hand-rolled normalisation:
`-d` overflows back to `Integer.MIN_VALUE`. The test's `Collections.rotate` oracle has to normalise
*before* negating for exactly that reason. Only `n = 0` is special-cased, because `floorMod(d, 0)`
throws: there is no modulus to reduce by.

## Three evaluation orders

| | Extra space | Element writes | Access pattern |
|---|---|---|---|
| `rotLeft` (copy) | O(n) | n | two sequential blocks |
| `rotateLeftInPlace` (3 reversals) | **O(1)** | ~2n | sequential |
| `rotateLeftInPlaceByCycles` (juggling) | **O(1)** | **n** | strided by `d` |

**Copy.** `rotated[i] = a[(i + d) mod n]` says the tail `a[d..n)` lands at the front and the head
`a[0..d)` follows it — two contiguous blocks, so two `System.arraycopy` calls, not n modulo
operations.

**Three reversals.** A left rotation emits `tail ++ head`. Reversing each part and then the whole
array performs that concatenation swap in place, because reversing a reversed block restores it:

```
[1 2 3 4 5]  d = 4
 reverse(0,4)   [4 3 2 1 | 5]
 reverse(4,5)   [4 3 2 1 | 5]
 reverse(0,5)   [5 | 1 2 3 4]
```

**Cycles (juggling).** `i → (i + d) mod n` is a permutation, so carry each element straight to its
destination: hold one, pull its replacement from `d` slots along, repeat until the walk closes,
drop the held value in the hole. There are exactly **`gcd(n, d)` cycles** of length `n / gcd(n, d)`
— the step `+d` first returns to its start after `lcm(n, d)` steps — which is why one walk is not
always enough. With `n` and `d` coprime a single cycle covers everything, and **that is the case the
HackerRank sample happens to be** (`n = 5, d = 4`), so a version that never restarts the walk still
passes it. `n = 4, d = 2` is the smallest case that catches it: a walk that never restarts leaves
`[1,2,3,4]` as `[3,2,1,4]` instead of `[3,4,1,2]`.

## The fewest writes is not the fastest

The cycle walk writes every element exactly once — the provable minimum, half what the reversals
do. It is also the one to avoid. Measured ad hoc, `n = 50 000 000` ints (200 MB), best of 3,
JDK 25.0.1 (Temurin), Intel i7-14650HX (L2 24 MB, L3 30 MB):

| `d` | power of 2 | 3 reversals | cycle walk | ratio |
|---:|:---:|---:|---:|---:|
| 1 | ✓ | 0.50 ns/el | 0.44 ns/el | 0.9× |
| 3 | | 0.52 | 0.78 | 1.5× |
| 7 | | 0.51 | 1.63 | 3.2× |
| 15 | | 0.50 | 3.22 | 6.4× |
| 100 | | 0.50 | 4.45 | 8.9× |
| 1 000 | | 0.50 | 1.60 | 3.2× |
| **1 024** | **✓** | 0.51 | **11.33** | **22×** |
| 12 345 | | 0.51 | 6.82 | 13.4× |
| **65 536** | **✓** | 0.50 | **23.52** | **47×** |
| 70 000 | | 0.51 | 1.08 | 2.1× |
| **1 048 576** | **✓** | 0.51 | **28.88** | **56×** |
| 3 333 333 | | 0.51 | 0.63 | 1.2× |
| 25 000 000 | | 0.51 | 0.52 | 1.0× |

Two columns, one array, the same instruction count. **The reversals are flat — 0.50–0.52 ns/element
across seven orders of magnitude of `d`. The cycle walk spans 0.44 to 28.88, a 65× spread driven by
nothing but the value of `d`.**

Where the spread comes from, in three regimes:

- **`d` below a cache line (16 ints).** The walk wraps the address range `d` times, and with a
  stride under 64 bytes each wrap touches every cache line — so it reloads the whole array `d`
  times. Cost tracks `d/2`: `d = 7` → 3.2×, `d = 15` → 6.4×.
- **Powers of two.** `1 024`, `65 536` and `1 048 576` cost 22×, 47× and 56×, while their immediate
  neighbours `1 000` and `70 000` cost 3.2× and 2.1×. A power-of-two stride maps every access onto
  the same cache sets; the neighbours do not. This is the sharpest effect in the table and it is
  invisible in the write count.
- **`d` large enough that `n/d` is small.** The walk becomes a handful of sequential streams the
  prefetcher follows, and it returns to parity: `d = 25 000 000` is 1.0×.

Three buckets is less of a model than it looks: the other mid-range values land anywhere between
2.1× (`70 000`) and 13.4× (`12 345`), and `d = 100` costs 8.9× while `d = 1 000` costs 3.2×. The
cache geometry is messier than any rule of thumb worth writing down. What survives is the shape of
the table, not a formula: the reversals are flat and the walk is not.

This is *not* a `gcd` effect — `d = 25 000 000` runs 25 million cycles of length 2 and is the
fastest row in the table. It is a cache effect, and the spikiness is what says so: a TLB cost would
rise smoothly with stride, not jump 7× between `1 000` and `1 024` and back down at `1 050`. (The
TLB was not isolated outright — this machine has THP set to `[always]`, so
`-XX:+UseTransparentHugePages` is a no-op here and turning THP off needs root.) Shrinking the array
does shrink the penalty, which is the more direct evidence — the same
`d = 65 536` costs 46× at 200 MB, 24× at 32 MB, 15× at 8 MB and 1.0× at 1 MB, where the array fits
in L2 and the strides stop mattering.

**So: ship the reversals.** They cost 2n writes instead of n and win by up to 56× because of *where*
they write. The cycle walk is worth knowing as the proof that n writes suffice, and worth reaching
for only when `d` is known and small.

## What the JDK does, and why it is not a counter-example

`Collections.rotate` picks between exactly these last two:

```java
if (list instanceof RandomAccess || list.size() < ROTATE_THRESHOLD)   // 100
    rotate1(list, distance);   // the cycle walk
else
    rotate2(list, distance);   // the three reversals
```

It gives the cycle walk to array-backed lists — seemingly the opposite of the conclusion above. It
is not, because it is answering a different question: `rotate2` reverses via `subList` and
`ListIterator`, which a `LinkedList` walks cheaply but an indexed `get`/`set` walk would make
O(n²). The choice is about **access cost**, not cache. For a primitive `int[]`, where indexing is
free and the array is bigger than L3, the trade-off inverts.

Also note the **sign**: `Collections.rotate(list, d)` rotates *right*. A left rotation by `d` is
`rotate(list, -d)`, which the test uses as one of its oracles.

## Rotation as a non-operation

The formula `rotated[i] = a[(i + d) mod n]` never actually required moving anything. If the caller
only reads, a rotation is a change of address arithmetic and costs O(1) — which is precisely what a
ring buffer is, and why `ArrayDeque` rotates by moving a head index. Moving elements is the price
of keeping the result a plain `int[]` whose index 0 is where the language says it is.

## Test oracles

Nothing rests on the implementation being its own witness:

| Oracle | Scale | Catches |
|---|---|---|
| Hand-written expected arrays | 20 cases incl. `gcd > 1`, negative `d`, `MIN_VALUE` | a wrong index map |
| Repeated single rotations | **every** `(n, d)` for `n ≤ 40`, `abs(d) ≤ 2n+3` | a wrong cycle count |
| `Collections.rotate` | 500 random arrays | a sign or off-by-one error |
| `rotated[i] == (i + d) mod n` | 10⁷ elements | overflow, and scale |

The exhaustive pass is the load-bearing one: `gcd(n, d) > 1` is a minority of pairs, and a cycle
walk that never restarts passes every sample that misses them.

Mutation-checked — eleven deliberate breaks, nine caught: `cycles = 1` → 8 failures, dropping the
wrap-around subtraction → 20 errors, reversing the whole array first → 15, raw `d % n` instead of
`floorMod` → 5 errors, returning the input when `shift == 0` → 1, rotating right → 16, breaking
Euclid's swap → 19, either reversal range off by one → 16 and 19. The two survivors are equivalent
mutants, not gaps: deleting `if (shift == 0) return` still leaves `reverse(0,0)` plus two full
reversals, which is the identity, and `hole == start` only ever holds on a walk's first step.

```
mvn test -Dtest=LeftRotationTest        # 28 tests, ~0.2 s
```

Measurements above were taken ad hoc with a throwaway harness, not by the build.

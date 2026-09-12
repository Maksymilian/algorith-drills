# `Gatherers.mapConcurrent`, and a semaphore that shares equally

Notes for [`src/java/jdk/EqualShareSemaphore.java`](../../src/java/jdk/EqualShareSemaphore.java) and
the three suites beside it: [`MapConcurrentTest`](../../test/java/jdk/MapConcurrentTest.java),
[`SemaphoreTest`](../../test/java/jdk/SemaphoreTest.java) and
[`EqualShareSemaphoreTest`](../../test/java/jdk/EqualShareSemaphoreTest.java).

This package keeps implementation in `src` and everything else in unit tests — no runnable example
classes. Every number here was measured on JDK 25.0.1 (Temurin), 24 CPUs, Intel i7-14650HX, either
by those tests or by a deliberately broken copy of the code (see
[Mutation results](#mutation-results)); the few figures from throughput benchmarks are marked where
they appear, since a benchmark is not a unit test and does not live in the tree.

**The one question** Running a stream's elements concurrently is easy. The question that decides
whether it works in production is narrower: *how many elements are in flight at the same time, and
who chose that number?* `Gatherers.mapConcurrent` answers it with a number you pass. The second
half of this note is about the case where the answer has to be **per caller**, which is the one
thing that number cannot express.

## `mapConcurrent` in one paragraph

`Gatherers.mapConcurrent(maxConcurrency, mapper)` is a **sequential** gatherer — final since JDK 24
(JEP 485), so no preview flag on the JDK 25 this project targets. It runs each element's mapper on
its own **virtual thread**, behind a permit window of `maxConcurrency`, and emits results in
encounter order. That combination is what makes it right for blocking work — I/O, queries, remote
calls — where the useful width has nothing to do with the number of cores.

```java
List<Response> out = urls.stream()
        .gather(Gatherers.mapConcurrent(500, this::httpGet))   // 500 in flight, exactly
        .toList();                                             // in the order of urls
```

| Property | Measured |
|---|---|
| Peak concurrency | **exactly** `maxConcurrency` — 1, 8 and 500 all hit their number |
| Thread per element | a virtual thread, every time — the calling thread is left alone, and is the one back-pressure blocks |
| Encounter order | preserved, even when the work finishes backwards |
| Read-ahead | exactly `maxConcurrency` elements — so an infinite source is safe |
| Failure | the mapper's own exception, unwrapped, delivered **in encounter order** |
| Short-circuit | returns without draining the window |
| `maxConcurrency <= 0` | `IllegalArgumentException: 'maxConcurrency' must be greater than 0` |
| `null` results | allowed — they pass through to the downstream |

## The width is a number, not a property of the machine

Three windows over blocking work, on a 24-core box:

| Window | Elements × work | Peak in flight | Wall clock |
|---:|---|---:|---:|
| 1 | 32 × 5 ms | 1 | — |
| 8 | 64 × 20 ms | 8 | 164–170 ms |
| 500 | 1 000 × 20 ms | **500** | 45–57 ms |

500 concurrent blocking calls on 24 cores is not a trick — a virtual thread parked in `sleep` or a
socket read costs a heap object, not a core. The wall clock follows the width directly: 1 000
elements × 20 ms of blocking is 20 seconds of work and finishes in ~50 ms.

The bound is a permit window, so the peak equals the window exactly, every run. It is not a thread
pool that might be busy with something else, and it is not shared with any other pipeline.

## Order is preserved; latency is not

The mapper for element 0 can be the slowest of the batch and the output still starts with element 0.
The example reverses the delays deliberately — element *i* sleeps `5 × (n - i)` ms, so completion
order is exactly backwards — and gets `[0, 1, 2, … 15]` back.

This is the property that pays for the failure behaviour below. You cannot have both "results in
encounter order" and "the first failure stops everything immediately".

## Laziness, and what back-pressure actually means here

An infinite source, a window of 4, a `limit(10)` downstream:

```java
Stream.iterate(0, i -> i + 1)
      .gather(Gatherers.mapConcurrent(4, this::work))
      .limit(10)
      .toList();
```

Measured: 10 taken, **11 generated, 12 mappers run**. Nothing runs away — the window *is* the
read-ahead. And with the consumer deliberately stalled after a single element (an `Iterator`, then
a 200 ms pause), exactly **5 mappers** had run behind a window of 5. The producer moves at the
consumer's pace, plus one window.

That is real back-pressure, and it is why this shape survives a source you cannot afford to
materialise: a cursor, a file, a socket.

## Failure surfaces in encounter order

This is the least obvious thing about `mapConcurrent`, and the most important to know before
putting it on a critical path. The mapper's exception is **not** reported when it is thrown. It is
reported when that element's *turn* comes — after every earlier element has been emitted.

Window of 8, each element blocking 100 ms:

| Mapper throws at | Exception surfaces after | Mappers that ran |
|---:|---:|---:|
| element 0 | 2–5 ms | 8 |
| element 20 | 301 ms | 24–25 |

301 ms is three windows of 100 ms: elements 0–19 have to be emitted first. So **the latency of a
failure is the latency of its position**, not the moment it happened. Three consequences:

1. A failure deep in a long stream is a slow failure. If fail-fast matters more than order, check
   the failure condition *before* the expensive call, or carry failures as values
   (`Either`/`Result`) and let them flow in order like everything else.
2. Work already in flight keeps running — the window is not cancelled, it drains.
3. The exception arrives **unwrapped** — the example gets its own `IllegalStateException` with its
   own message, not an `ExecutionException` or `CompletionException` around it.

And the mirror image: a failure the consumer never reaches never happens. With `limit(3)` in front
of an element that throws at index 30, the result is `[0, 1, 2]` and only 4–8 mappers ever ran.

## Short-circuiting does not drain the window

`findFirst()` over a window of 16 where element 0 takes 10 ms and the other 15 sleep 1 500 ms
returns element 0 after **12–15 ms**. The stragglers are abandoned, not awaited. Note the asymmetry
with the failure path above: a short-circuit is free, a failure costs you everything ahead of it in
encounter order.

The other half of that asymmetry is a hazard. A *non*-short-circuiting terminal operation —
`toList`, `forEach`, `reduce` — has to consume every element, so a mapper that never returns parks
the pipeline permanently, and interrupting the thread that called the terminal operation does not
free it. Discovered the hard way: it wedged a test run for 900 s (see
[Mutation results](#mutation-results)). Anything that can block indefinitely belongs behind a
timeout *inside* the mapper, not outside the stream.

## What the window cannot say

`maxConcurrency` is **one number for the whole pipeline**. It cannot express:

- *"tenant A may have three of these, tenant B three, and nobody may be starved"* — there is one
  window and it is first come, first served;
- *"this caller is over its budget"* — the window has no notion of a caller at all.

A single greedy source of elements fills the whole window, and everything else queues behind it.
That is the gap the second half of this note fills — not as a replacement for `mapConcurrent`, but
as the thing you put *inside* the mapper.

## The plain `Semaphore`'s sharp edges

Before building anything on top of it, the parts of `java.util.concurrent.Semaphore` that do not
behave like a lock. All asserted in `SemaphoreTest`:

| Behaviour | Measured |
|---|---|
| `release()` without a matching `acquire()` | **creates** a permit — `Semaphore(2)` plus 3 bare releases holds **5** |
| Who may release | anybody: the releasing thread need not be the acquiring one |
| 2 callers × `acquire(2)` twice, 4 permits | **deadlock** — 0 free, both parked, no thread left that could release |
| the same with `tryAcquire(2, timeout)` | **at most one** gets through (0 or 1, a race), never both |
| `acquire(4)` in one call | no hold-and-wait; both callers complete and the pool is restored |
| `tryAcquire()` on a **fair** semaphore | **barges** past a parked `acquire(5)` — fairness does not apply to it |
| `tryAcquire(1, 50 ms)` on a fair semaphore | queues behind it (`false`), where the unfair one hands it over (`true`) |
| `drainPermits()` with 2 of 5 held | takes the 3 free ones and shuts the gate; the 2 held are untouched |
| reopening after a drain | `release(taken)` — you restore what you took, not the original count |
| `new Semaphore(0)` + `acquire(n)` | a "wait for n completions" latch, and unlike `CountDownLatch` it is reusable |
| `new Semaphore(-2)` | starts in debt: three releases before one acquire can succeed |

Three of those rows are the reason the next section exists:

- **Permits have no owner.** A double release is not an error, it is a silent resize of the pool.
  `EqualShareSemaphore` hands out an `AutoCloseable` `Ticket` whose `close()` is idempotent, so the
  same permit cannot be returned twice — which is a property of the wrapper, not of semaphores.
- **Acquiring twice deadlocks.** `acquire(n)` is all-or-nothing, so ask for everything in one call.
  The deterministic demonstration forces the interleaving with a latch; without that the two callers
  usually miss each other and the bug hides, which is exactly how it reaches production.
- **`tryAcquire()` ignores fairness.** The fairness flag orders `acquire` and
  `tryAcquire(timeout)`, and the no-argument `tryAcquire()` is documented to barge regardless. It is
  the sharpest edge in the class, and it reinforces the next section's point: fairness is about
  *order*, never about *how much* one caller may hold.

## `EqualShareSemaphore`: a guarantee, not an ordering

The problem in its smallest form: `Semaphore(10)` shared by three tenants, tenant 0 takes all ten
and holds them. Tenants 1 and 2 get nothing for as long as tenant 0 wants. Measured in the example:
*"a second party gets 0 of 10 permits"*.

With `total` permits and `parties` parties:

```
share   = total / parties           // integer division — the guaranteed reservation
surplus = total - parties * share   // the remainder — first come, first served
```

A request from party *p* is admitted when **either**

1. `held[p] < share` — it is spending its own reservation, **or**
2. `surplusHeld < surplus` — it is above its share, and spare capacity exists.

That is the whole algorithm; in the source it is two lines. `held[p]` is the party's current
holdings, `surplusHeld` the sum of `max(0, held[q] - share)` over all parties.

### Why rule 1 does not check the total

Rule 1 admits without looking at `totalHeld`, which looks unsafe and is not. Every party above its
share draws from the surplus pool, so the *other* parties together can hold at most
`(parties-1)*share + surplus`. Therefore whenever `held[p] < share`:

```
totalHeld = others + held[p] <= (parties-1)*share + surplus + held[p] < parties*share + surplus = total
```

A free permit provably exists. This is the guarantee, and it is a strong one:

> **A party below its share never blocks** — regardless of what every other party is doing.

The proof is checked at runtime rather than trusted: `requireCapacity` throws if a party is ever
admitted below its share while all permits are out, and `tryAcquire` counts every refusal handed to
a below-share caller. Under 32 threads hammering 12 permits across 4 parties for 300 ms: 116 000–255 000
acquisitions depending on machine load, peak 12/12 in flight, 0 over-cap, **0 below-share
denials**.

### What it costs: not work-conserving

The reservation is real capacity, held aside. A party can never exceed `share + surplus` (4 of 10
in the example) even when every other party is idle. Handing an idle party's reservation to someone
else and taking it back on demand needs **preemption**, and a permit that has been given out cannot
be revoked — the holder is inside a database call. The alternatives are all worse in a specific way:

| Design | Guarantee | Work-conserving | Cost |
|---|---|---|---|
| `mapConcurrent(n)` alone | none — one greedy source fills the window | yes | nothing |
| `Semaphore(total)` | none — starvation | yes | nothing |
| `Semaphore(share)` per party, no sharing | yes | no | the remainder is unusable |
| **This gate** | yes | no | one lock, `signalAll` per release |
| Lease with revocation / weighted fair queueing | yes | yes | callers must be interruptible mid-work |

### Could you just compose two `Semaphore`s?

Almost. `Semaphore own = new Semaphore(share)` per party plus a shared `Semaphore(surplus)` gives
the same admission rules: try your own, fall back to the surplus. It breaks on the *waiting*: a
party above its share must wait for **either** its own pool or the surplus to free up, and there is
no way to wait on two semaphores at once. You would have to poll one of them with a timeout. One
lock with one condition — what the class does — is the version without a spin.

The same reasoning explains `signalAll()` in `close()`. `signal()` is tempting because all waiters
are above-share threads waiting on the same predicate, but a timed `tryAcquire` that is signalled
one nanosecond after it has given up will swallow that signal, and another waiter sleeps through an
available permit. Removing the signal entirely makes the example hang; downgrading it to `signal()`
is **not** caught by any check here, which is exactly why it is written the safe way.

The lock is a `ReentrantLock`, not `synchronized`, which matters here: the mapper inside
`mapConcurrent` runs on a virtual thread, and `ReentrantLock`/`Condition` park it without holding a
carrier.

## Fairness is ordering; a share is a quantity

`new Semaphore(1)` is barging: a thread that releases a permit can re-acquire it before a queued
waiter is even scheduled. `new Semaphore(1, true)` hands it to the longest waiter instead. Eight
threads in a tight acquire/release loop for 300 ms, started from a latch so the window is identical
for all of them:

| | Acquisitions | Self-succession |
|---|---:|---:|
| `Semaphore(1)` (barging) | 0.87–3.6 M | **97.6–98.1 %** |
| `Semaphore(1, true)` (fair) | 28–66 K | **0.00 %** |

Self-succession is the share of acquisitions where the permit went straight back to the thread that
just released it. At 98 %, the unfair semaphore is barely a shared resource at all — one thread runs
a private critical section while seven wait. Ordering removes that completely and costs 18–62×,
because every handoff becomes a park/unpark instead of staying in the releasing thread's cache.

**How not to measure it.** Two obvious metrics measure nothing here, and the first draft of this
example used both:

- *A fixed number of acquisitions per thread.* The counts come out equal by construction, whatever
  the semaphore does.
- *Per-thread counts over a fixed window.* This looks sound and is dominated by startup skew: the
  fair run gets tens of thousands of turns to the unfair run's millions, so a thread starting late
  shows as a large imbalance. Measured across 4, 8, 24 and 48 threads, the **fair** run's spread was
  frequently the worse of the two — 46 % against the unfair run's 14 % at 8 threads, 88 % against
  54 % at 24 — while ranging from 0 % to 89 % run to run. A metric that ranks fair as less fair, and
  disagrees with itself, is noise.

Barging is a question about the handoff, so the handoff is what has to be counted. And note what
ordering buys: even turns among threads **already waiting**. It says nothing about how many permits
a caller may hold, so:

> A fair semaphore does not stop one caller from taking every permit and keeping them.

### The `fairQueueing` knob, and its price

The constructor's flag only orders the contest for the *surplus* — the guarantee does not depend on
it, since a below-share party is never in a queue to begin with. Its price, 32 threads on 12
permits, five runs:

| Gate | Throughput (ad-hoc benchmark, not in the tree) | vs plain |
|---|---:|---:|
| `Semaphore(12)` | 8.5–10.5 M ops/s | 1× |
| `EqualShareSemaphore(12, 4)` | 5.0–10.8 M ops/s | 0.9–2.1× |
| `EqualShareSemaphore(12, 4, true)` | 31–59 K ops/s | 148–286× slower |

The middle row straddles 1×: run to run the gate is sometimes slower and occasionally faster than a
plain `Semaphore`, which is the sensible way to read a sub-2× difference in a microbenchmark like
this — the bookkeeping for the guarantee is one lock, two comparisons and a `signalAll`, and it does
not show. The `fairQueueing` flag is the entire cost, two orders of magnitude, which is why unfair
is the default.

## Both halves together

Four tenants, four independent `mapConcurrent` pipelines, each with a window of **40** — wider than
anything the tenants could use — and one `EqualShareSemaphore(12, 4)` taken inside the mapper:

```java
IntStream.range(0, itemsEach).boxed()
        .gather(Gatherers.mapConcurrent(40, i -> {          // no useful bound here
            EqualShareSemaphore.Ticket permit = acquire(gate, tenant);
            try (permit) {                                  // the bound is here
                return work(i);
            }
        }))
        .forEach(…);
```

Peak concurrency per tenant: **`[3, 3, 3, 3]`** — the window let 40 through, the gate let 3.

The control matters as much as the result. Re-run with a gate too wide to bind
(`EqualShareSemaphore(400, 4)`, share 100) and the same pipeline gives **`[40, 40, 40, 40]`**: the
window takes over. Without that second measurement, `[3, 3, 3, 3]` would be consistent with the
window doing the work, and the check would pass for the wrong reason.

`Ticket` is `AutoCloseable` and its `close()` is idempotent, so a retry, a stray copy or a `finally`
that runs twice cannot inflate the permit count — deleting that guard is caught.

## What this is not

- **Not a rate limiter.** Permits bound *concurrency* (things in flight), not *rate* (things per
  second). 12 permits × 10 ms of work is 1 200 ops/s; the same 12 permits × 1 s of work is 12 ops/s.
  For a rate, use tokens refilled on a clock.
- **Not weighted.** Every party gets `total / parties`. Weights mean replacing the scalar `share`
  with a per-party array — the admission rules do not otherwise change.
- **Not a scheduler.** The gate decides *whether* a party may hold another permit, never *which* of
  that party's threads goes first; that is what `fairQueueing` and the `ReentrantLock` are for.
- **Not reentrant.** A thread that acquires twice holds two permits, and two of them nested inside
  one party with `share = 1` is a self-deadlock. Permits are not locks.
- **Not a replacement for `mapConcurrent`'s window.** The two bound different things: the window
  caps the pipeline, the gate caps a tenant. Keep both.

## Test oracles

`test/java/jdk/` — 70 tests across three suites, four independent oracles, so nothing rests on the
implementation being its own witness:

| Oracle | Scale | Catches |
|---|---|---|
| Exhaustive state walk | every legal holding vector of 7 configurations, built in both orders — 3 458 states | an admission rule that is wrong *anywhere* in the state space |
| Deterministic scenarios | greedy vs starved parties, waking, interrupting, timing out, double close | the documented behaviours, including the ones that are trade-offs |
| Randomised stress | 24 threads against 12 permits, both fairness settings | races, leaks, lost wakeups |
| JDK semantics | every `mapConcurrent` and plain-`Semaphore` claim above | a future JDK changing them, or a wrong claim in these notes |

"Legal" in the first row is defined from the *specification* — no party over `share + surplus`, no
more than `surplus` permits borrowed in total — not from the admission rules, so the walk is a check
against the spec rather than a restatement of the code. At each state it asserts the guarantee
directly: every party below its share is admitted immediately, every party at its cap is refused,
and the permits all come back.

Where a claim allows it, the test is a latch rather than a stopwatch. "The failure has not surfaced
yet" is asserted by a pipeline thread still being blocked on a latch this test controls, not by a
duration that a loaded CI box could blow through.

```
mvn test                                              # 150 tests, ~6 s
```

## Mutation results

Each break is applied to a *copy* of the repository, and the suite is run against it.

| Mutation | Caught by |
|---|---|
| `EqualShareSemaphore`: rule 2 dropped (surplus unguarded) | **12 tests** |
| rule 1 off by one (`held <= share`) | **11 tests** |
| `take` forgets `surplusHeld++` | **5 tests** |
| `close` forgets `surplusHeld--` | **3 tests** |
| `close` not idempotent | **1 test** |
| `tryAcquire` ignores its timeout | **2 tests** |
| no `signal` on release | **5 tests**, after 214 s of timeouts |
| `signalAll` → `signal` | **nothing** |
| `fairQueueing` flag ignored | **nothing** |
| `requireCapacity` never throws | **nothing** (by design — see below) |
| per-tenant window 40 → 3 | **1 test** (the control) |
| permit released before the work, not after | **1 test** |
| reopening after a drain releases the original count | **1 test** |
| `acquire(4)` split into two `acquire(2)` calls | **nothing reliably** — see below |

Eleven of the fourteen are caught. The interesting rows are the other three and the slow one.

These numbers were taken while the package still had runnable example classes, which have since
been folded into the suites above; the mutations and the tests that catch them are unchanged.

**`signalAll` → `signal` is caught by nothing**, in either column. All waiters are above-share
threads waiting on the same predicate, so `signal()` looks sufficient and passes every test here;
it is wrong only in the narrow case where a timed `tryAcquire` is signalled just as it gives up and
swallows the wakeup. Correctness of that line rests on the argument, not on evidence — which is
worth stating plainly rather than leaving a reader to assume the tests cover it.

**The `fairQueueing` flag is unverified.** No test asserts ordering, so replacing the flag with a
hardcoded `false` changes nothing observable. That follows from the design — the guarantee does not
depend on queue order — but it does mean the flag is documentation, not a tested feature.

**`requireCapacity` is a tripwire, not behaviour.** While the algorithm is right it never fires, so
removing it is invisible; that is what a runtime assertion *should* look like. Its value shows in
combination: with rule 2 dropped *and* the tripwire removed, 11 tests still fail. It makes other
bugs louder, and the suite does not depend on it.

**A lost wakeup is caught, but slowly — and it took a fix to catch it at all.** Deleting the release
signal makes the examples hang outright. The JUnit suite fails it in 214 s, five tests reporting,
each having waited out its own timeout: "a permit that never arrives" cannot be detected faster than
you are willing to wait for it, and generous timeouts are the right trade against flakiness.

Getting there needed one change. In the first version of these tests the run never finished at all —
still going when it was killed at 900 s — because `theGateHoldsUpUnderManyVirtualThreads` wedged.
The reason is worth knowing independently of this repository: **a mapper that never returns cannot
be abandoned by a non-short-circuiting terminal operation.** `forEach` and `toList` must consume
every element, so they wait for the parked mapper forever, and JUnit's default `@Timeout` cannot
rescue the test — it interrupts the *test* thread, which is itself blocked inside the terminal
operation, not the virtual threads parked in the gate. Marking those tests
`@Timeout(threadMode = SEPARATE_THREAD)` lets the timeout report without waiting for the pipeline,
which turns a wedged fork into a 62 s failure. Test threads are daemons for the same reason: a
parked worker must not outlive the build.

**Splitting `acquire(4)` into two `acquire(2)` calls is not reliably caught**, and that is the
lesson rather than a gap. The deadlock only happens if the two callers interleave; left to chance
they usually do not, the test passes, and the bug waits for production. The deterministic case
forces the interleaving with a latch — which is the only way to test a race on purpose.

One earlier version of these tests turned that mutation into an *out-of-memory crash* instead of a
failure: the helper that drains a party's permits looped until it was refused, which never happens
if the gate admits everything. It now asserts the cap on every iteration. A test helper that cannot
terminate against a broken implementation is worse than no helper — it converts a clear failure into
a dead fork.

## Running it

```
mvn -q compile     # needs JDK 25 (pom targets release 25)
mvn test           # 150 tests, ~6 s — 70 of them in test/java/jdk
```

There is nothing else to run: this package keeps its implementation in `src/java/jdk` and
everything demonstrable in `test/java/jdk`, so the suite is both the documentation's evidence and
its regression net. Peaks, counts, orders and invariants are asserted; the wall-clock and
throughput figures quoted above are indicative, and they moved noticeably between JDK 17 and 25
while every invariant stayed put.

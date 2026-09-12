package jdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDK's {@link Semaphore} contract, and the five behaviours that surprise people who reach for
 * it expecting a lock. Like {@link MapConcurrentTest} these tests pin down the platform rather than
 * this repository's code — and three of them are the reason {@link EqualShareSemaphore} exists.
 *
 * <ol>
 *   <li>A permit has no owner: anyone can release one, including someone who never acquired it.</li>
 *   <li>{@code acquire(n)} is all-or-nothing, which is exactly why acquiring twice can deadlock.</li>
 *   <li>{@code tryAcquire()} ignores fairness; {@code tryAcquire(n, timeout, unit)} honours it.</li>
 *   <li>{@code drainPermits()} closes the gate in one move.</li>
 *   <li>Permits count in both directions: a semaphore can start negative, and {@code acquire(n)}
 *       is a "wait for n things to finish" latch.</li>
 * </ol>
 */
class SemaphoreTest {

    @ParameterizedTest(name = "{0} bare releases")
    @ValueSource(ints = {1, 3, 10})
    void releaseCreatesPermitsItWasNeverGiven(int bareReleases) {
        // not an error, not a no-op: the "bound" simply moves. Any code path that can release
        // twice has silently resized the pool, which is why a ticket that closes once is worth
        // having (see EqualShareSemaphore.Ticket)
        assertEquals(2 + bareReleases, permitsAfterBareReleases(bareReleases));
    }

    @Test
    @Timeout(30)
    void aPermitMayBeReleasedByAnotherThread() throws InterruptedException {
        assertTrue(releasedByAnotherThread());

        // the same property directly: this is what makes producer-acquires/worker-releases legal
        Semaphore mutex = new Semaphore(1);
        mutex.acquire();
        Thread other = new Thread(mutex::release);
        other.setDaemon(true);
        other.start();
        other.join(TimeUnit.SECONDS.toMillis(10));
        assertEquals(1, mutex.availablePermits());
        assertFalse(mutex.isFair());
    }

    // ---------- hold and wait ----------

    @Test
    @Timeout(60)
    void twoCallersHoldingHalfEachDeadlock() throws InterruptedException {
        // deterministic, not a race: zero permits are free and both callers are parked, so no
        // thread remains that could release one
        assertTrue(bothCallersStuckHoldingHalf(300),
                "the standoff resolved itself, which should be impossible");
    }

    @ParameterizedTest(name = "timeout {0} ms")
    @ValueSource(longs = {50, 300})
    @Timeout(60)
    void aTimeoutCannotLetBothCallersThrough(long timeoutMillis) throws InterruptedException {
        // 0 or 1, depending on whether one caller's timeout fires far enough ahead of the other's
        // to release its pair in time. Never 2: there are 4 permits and each caller needs 4.
        int through = callersThatGotTheirSecondPair(timeoutMillis);
        assertTrue(through <= 1, "both callers got their second pair, which the arithmetic forbids");
    }

    @Test
    @Timeout(60)
    void takingEverythingInOneCallCannotDeadlock() throws InterruptedException {
        assertTrue(takenInOneCall());
    }

    @Test
    @Timeout(30)
    void acquireIsAllOrNothing() throws InterruptedException {
        // a request for more than exists takes nothing at all — no partial hold to leak
        Semaphore pool = new Semaphore(3);
        assertFalse(pool.tryAcquire(5));
        assertEquals(3, pool.availablePermits());
        assertFalse(pool.tryAcquire(5, 50, TimeUnit.MILLISECONDS));
        assertEquals(3, pool.availablePermits());
    }

    @Test
    @Timeout(30)
    void anInterruptedAcquireTakesNothing() throws InterruptedException {
        Semaphore pool = new Semaphore(3);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread greedy = new Thread(() -> {
            started.countDown();
            try {
                pool.acquire(5);                      // more than exists: parks
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        greedy.setDaemon(true);
        greedy.start();
        started.await();
        while (!pool.hasQueuedThreads()) Thread.onSpinWait();

        greedy.interrupt();
        greedy.join(TimeUnit.SECONDS.toMillis(10));

        assertInstanceOf(InterruptedException.class, thrown.get());
        assertEquals(3, pool.availablePermits(), "an interrupted acquire must not hold anything");
    }

    // ---------- fairness applies to some methods and not others ----------

    @ParameterizedTest(name = "fair={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void tryAcquireWithoutATimeoutIgnoresFairness(boolean fair) throws InterruptedException {
        Barge barge = smallRequestMeetsAParkedBigOne(fair);
        assertTrue(barge.withoutTimeout(),
                "tryAcquire() is specified to barge whatever the fairness setting says");
        assertEquals(3, barge.available(), "the probe must leave the semaphore as it found it");
    }

    @ParameterizedTest(name = "fair={0}")
    @ValueSource(booleans = {false, true})
    @Timeout(30)
    void tryAcquireWithATimeoutHonoursFairness(boolean fair) throws InterruptedException {
        Barge barge = smallRequestMeetsAParkedBigOne(fair);
        assertEquals(!fair, barge.withTimeout(),
                fair ? "a fair semaphore must queue this behind the parked request"
                     : "an unfair semaphore should hand it over immediately");
    }

    // ---------- drainPermits ----------

    @Test
    @Timeout(30)
    void drainPermitsTakesOnlyWhatIsFree() throws InterruptedException {
        Drain drain = closeAndReopen(5, 2);
        assertEquals(3, drain.taken(), "2 of the 5 were held, so only 3 were there to drain");
        assertEquals(0, drain.leftAvailable());
        assertFalse(drain.stillOpen(), "a drained semaphore admits nobody");
        assertEquals(3, drain.afterReopen(), "releasing what was drained restores exactly that");
    }

    @Test
    @Timeout(30)
    void drainingAnEmptySemaphoreTakesNothing() {
        Semaphore gate = new Semaphore(0);
        assertEquals(0, gate.drainPermits());
        assertEquals(0, gate.availablePermits());
    }

    // ---------- counting in both directions ----------

    @ParameterizedTest(name = "{0} workers")
    @ValueSource(ints = {1, 4, 32})
    @Timeout(60)
    void acquireOfNWaitsForNReleases(int workers) throws InterruptedException {
        long millis = millisToAwait(workers);
        assertTrue(millis < TimeUnit.SECONDS.toMillis(30), "waited " + millis + " ms for " + workers);
    }

    @Test
    @Timeout(30)
    void aSemaphoreCanStartInDebt() throws InterruptedException {
        assertEquals(-2, permitsOfADebtOf(2));
        Semaphore owing = new Semaphore(-1);
        assertFalse(owing.tryAcquire());
        owing.release();
        assertFalse(owing.tryAcquire(), "one release only brought it back to zero");
        owing.release();
        owing.release();
        assertTrue(owing.tryAcquire(), "the second release is the first real permit");
        assertTrue(owing.tryAcquire(), "the second release is the first real permit");
        assertFalse(owing.tryAcquire(), "the second release is the first real permit");
    }

    @Test
    @Timeout(30)
    void permitsSurviveBeingCountedBackUp() throws InterruptedException {
        // unlike a CountDownLatch, the count is reusable: the permits are still there afterwards
        Semaphore finished = new Semaphore(0);
        finished.release(3);
        finished.acquire(3);
        assertEquals(0, finished.availablePermits());
        finished.release(3);
        assertTrue(finished.tryAcquire(3, 1, TimeUnit.SECONDS),
                "the same semaphore should be usable for a second round");
    }

    // ---------- fairness is about order, and it costs ----------

    /** What one barging measurement produced. */
    private record Barging(long acquisitions, long selfSuccessions) {
        /** Share of acquisitions where the permit went straight back to its last holder. */
        double selfRate() { return acquisitions == 0 ? 0 : (double) selfSuccessions / acquisitions; }
    }

    /**
     * Counts the handoff, not the winners. Two obvious metrics measure nothing here: a fixed
     * number of acquisitions per thread makes the counts equal by construction, and per-thread
     * counts over a fixed window are dominated by startup skew — measured across 4 to 48 threads,
     * the fair run's spread was frequently the worse of the two. Barging is a question about who
     * gets the permit next, so that is what this counts.
     */
    private static Barging barging(int threads, int millis, boolean fair) throws InterruptedException {
        Semaphore gate = new Semaphore(1, fair);
        AtomicInteger lastHolder = new AtomicInteger(-1);
        AtomicInteger acquisitions = new AtomicInteger();
        AtomicInteger selfSuccessions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        long[] deadline = new long[1];

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            workers[t] = new Thread(() -> {
                int mine = 0, selfs = 0;
                ready.countDown();
                try {
                    go.await();
                    while (System.nanoTime() < deadline[0]) {
                        for (int i = 0; i < 64; i++) {           // amortise the clock read
                            gate.acquire();
                            if (lastHolder.getAndSet(id) == id) selfs++;
                            mine++;
                            Thread.onSpinWait();                 // a tiny critical section
                            gate.release();                      // ...then grab it straight back
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                acquisitions.addAndGet(mine);
                selfSuccessions.addAndGet(selfs);
            });
            workers[t].setDaemon(true);
            workers[t].start();
        }
        ready.await();                                           // no startup skew in the window
        deadline[0] = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        go.countDown();
        for (Thread w : workers) w.join(TimeUnit.SECONDS.toMillis(30));
        return new Barging(acquisitions.get(), selfSuccessions.get());
    }

    @Test
    @Timeout(60)
    void anUnfairSemaphoreHandsThePermitBackToItsLastHolder() throws InterruptedException {
        Barging unfair = barging(8, 300, false);
        assertTrue(unfair.acquisitions() > 1_000, "too few acquisitions to mean anything");
        assertTrue(unfair.selfRate() > 0.5,
                "only " + Math.round(100 * unfair.selfRate()) + "% self-succession; barging should dominate");
    }

    @Test
    @Timeout(60)
    void aFairSemaphoreHandsItOnInstead() throws InterruptedException {
        Barging fair = barging(8, 300, true);
        assertTrue(fair.acquisitions() > 100, "too few acquisitions to mean anything");
        assertTrue(fair.selfRate() < 0.1,
                Math.round(100 * fair.selfRate()) + "% self-succession on a fair semaphore");
    }

    // ---------- 1. a permit has no owner ----------

    /**
     * {@code release()} does not check that you hold anything — it <em>creates</em> a permit. A
     * double release is therefore not an error but a silent capacity increase, and the pool you
     * thought was bounded at 2 is now bounded at 5. This is the bug {@code EqualShareSemaphore}'s
     * idempotent {@code Ticket} exists to make impossible.
     *
     * @return permits available after {@code bareReleases} releases on a fresh {@code Semaphore(2)}
     */
    private static int permitsAfterBareReleases(int bareReleases) {
        Semaphore pool = new Semaphore(2);
        for (int i = 0; i < bareReleases; i++) pool.release();
        return pool.availablePermits();
    }

    /**
     * The same property read the other way, and the reason a semaphore is not a mutex: the thread
     * that releases need not be the thread that acquired. Useful — it is how a permit taken by a
     * producer is returned by the worker that finishes the job — and dangerous for the same reason.
     */
    private static boolean releasedByAnotherThread() throws InterruptedException {
        Semaphore mutex = new Semaphore(1);
        mutex.acquire();
        Thread other = new Thread(mutex::release);
        other.start();
        other.join();
        return mutex.availablePermits() == 1;
    }

    // ---------- 2. acquire(n) is atomic, which is what makes acquiring twice dangerous ----------

    /**
     * Two callers, four permits, and each wants two now and two later. Both get their first pair,
     * so nothing is left for either second pair, and nothing will ever be released — the classic
     * hold-and-wait deadlock, reached without a single lock.
     *
     * <p>This is deterministic rather than a race: with zero permits available and both callers
     * parked in {@code acquire(2)}, there is no thread left that could release one. The method
     * builds that deadlock, observes it, and then interrupts its way out.
     *
     * @return true if, after {@code observeMillis}, neither caller has moved
     */
    private static boolean bothCallersStuckHoldingHalf(long observeMillis) throws InterruptedException {
        Semaphore pool = new Semaphore(4);
        CountDownLatch bothHoldTwo = new CountDownLatch(2);
        AtomicInteger gotSecondPair = new AtomicInteger();

        Runnable caller = () -> {
            try {
                pool.acquire(2);                       // first pair: always fine
                bothHoldTwo.countDown();
                bothHoldTwo.await();                   // ...now nothing is left
                pool.acquire(2);                       // no timeout: this is the deadlock
                gotSecondPair.incrementAndGet();
                pool.release(4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();     // the way out, since nothing else frees it
            }
        };
        Thread a = new Thread(caller);
        Thread b = new Thread(caller);
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();

        bothHoldTwo.await();
        Thread.sleep(observeMillis);
        boolean stuck = gotSecondPair.get() == 0
                && pool.availablePermits() == 0
                && a.isAlive() && b.isAlive();

        a.interrupt();
        b.interrupt();
        a.join(TimeUnit.SECONDS.toMillis(10));
        b.join(TimeUnit.SECONDS.toMillis(10));
        return stuck;
    }

    /**
     * The same standoff with {@code tryAcquire(2, timeout)} instead. A timeout cannot make both
     * callers succeed — there are only four permits and each needs four — but it does turn a
     * permanent deadlock into a refusal. Which caller gets through, if any, is a race: whoever
     * gives up first releases its pair, and the other may still be inside its own timeout window
     * and take it. So the invariant is "at most one", never "exactly none".
     *
     * @return how many of the two callers got their second pair: 0 or 1, never 2
     */
    private static int callersThatGotTheirSecondPair(long timeoutMillis) throws InterruptedException {
        Semaphore pool = new Semaphore(4);
        AtomicInteger succeeded = new AtomicInteger();
        CountDownLatch bothHoldTwo = new CountDownLatch(2);

        Runnable caller = () -> {
            try {
                pool.acquire(2);
                bothHoldTwo.countDown();
                bothHoldTwo.await();
                if (pool.tryAcquire(2, timeoutMillis, TimeUnit.MILLISECONDS)) {
                    succeeded.incrementAndGet();
                    pool.release(2);
                }
                pool.release(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(caller);
        Thread b = new Thread(caller);
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        a.join();
        b.join();
        return succeeded.get();
    }

    /** The same four permits, taken in one call instead of two: no hold-and-wait, no deadlock. */
    private static boolean takenInOneCall() throws InterruptedException {
        Semaphore pool = new Semaphore(4);
        AtomicInteger completed = new AtomicInteger();
        Runnable caller = () -> {
            try {
                pool.acquire(4);                       // all of it, or nothing
                try {
                    completed.incrementAndGet();
                } finally {
                    pool.release(4);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(caller);
        Thread b = new Thread(caller);
        a.start();
        b.start();
        a.join(TimeUnit.SECONDS.toMillis(10));
        b.join(TimeUnit.SECONDS.toMillis(10));
        return completed.get() == 2 && pool.availablePermits() == 4;
    }

    // ---------- 3. tryAcquire() is not subject to fairness ----------

    private record Barge(boolean withoutTimeout, boolean withTimeout, int available) {}

    /**
     * A big request is parked — {@code acquire(5)} against three permits — and a small one arrives.
     * On a <b>fair</b> semaphore the small request is supposed to queue behind it, and with a
     * timeout it does. The no-argument {@code tryAcquire()} barges anyway: it is documented to
     * ignore the fairness setting, and it is the single most surprising line in the class.
     */
    private static Barge smallRequestMeetsAParkedBigOne(boolean fair) throws InterruptedException {
        Semaphore pool = new Semaphore(3, fair);
        Thread big = new Thread(() -> {
            try {
                pool.acquire(5);                       // never satisfiable here; it parks
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        big.setDaemon(true);                           // it is never woken; it must not outlive us
        big.start();
        while (!pool.hasQueuedThreads()) Thread.onSpinWait();   // wait until it is really queued

        boolean barged = pool.tryAcquire();            // ignores fairness, always
        if (barged) pool.release();
        boolean queued = pool.tryAcquire(1, 50, TimeUnit.MILLISECONDS);   // honours fairness
        if (queued) pool.release();

        return new Barge(barged, queued, pool.availablePermits());
    }

    // ---------- 4. drainPermits(): closing the gate in one move ----------

    private record Drain(int taken, int leftAvailable, boolean stillOpen, int afterReopen) {}

    /**
     * {@code drainPermits()} takes everything that is free, in one atomic step, and returns how
     * much that was. It is how you stop new entrants without disturbing the ones already inside:
     * permits already held are not affected and come back later — which is why reopening means
     * releasing what you drained, not resetting a count.
     */
    private static Drain closeAndReopen(int permits, int alreadyHeld) throws InterruptedException {
        Semaphore gate = new Semaphore(permits);
        gate.acquire(alreadyHeld);                     // somebody is already inside

        int taken = gate.drainPermits();               // close
        boolean stillOpen = gate.tryAcquire();
        gate.release(taken);                           // reopen with exactly what we took
        return new Drain(taken, permits - alreadyHeld - taken, stillOpen, gate.availablePermits());
    }

    // ---------- 5. permits count in both directions ----------

    /**
     * {@code acquire(n)} against a semaphore that starts at zero is a "wait for n completions"
     * latch: each worker releases one permit as it finishes, and the coordinator asks for all of
     * them at once. Unlike a {@code CountDownLatch} the count can be reused — the permits are
     * still there to be taken again.
     */
    private static long millisToAwait(int workers) throws InterruptedException {
        Semaphore finished = new Semaphore(0);
        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(finished::release);
            worker.setDaemon(true);
            worker.start();
        }
        long start = System.nanoTime();
        finished.acquire(workers);                     // returns only when all of them are done
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /**
     * A semaphore may start below zero, which is the same idea stated as a debt: three releases
     * are needed before a single acquire can succeed.
     */
    private static int permitsOfADebtOf(int debt) {
        return new Semaphore(-debt).availablePermits();
    }

    // ---------- permits have no owner ----------

}

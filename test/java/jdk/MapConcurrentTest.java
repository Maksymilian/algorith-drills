package jdk;

import jdk.EqualShareSemaphore.Ticket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Gatherers;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * These tests pin down the {@code Gatherers.mapConcurrent} behaviour the notes rely on — the exact
 * bound, encounter order, bounded read-ahead, encounter-ordered failure, and short-circuiting that
 * does not drain the window. None of that is this repository's code, so a failure here means the
 * JDK changed (or the notes were wrong), which is precisely why the checks are worth keeping.
 *
 * <p>Where the claim allows it the test is a latch, not a stopwatch: "the failure has not surfaced
 * yet" is asserted by a thread still being blocked, not by a duration.
 */
class MapConcurrentTest {

    @ParameterizedTest(name = "window of {0}")
    @ValueSource(ints = {1, 2, 8, 64})
    @Timeout(60)
    void theWindowIsTheExactBound(int window) {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<Integer> out = IntStream.range(0, 3 * window).boxed()
                .gather(Gatherers.mapConcurrent(window, i -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    sleep(20);
                    inFlight.decrementAndGet();
                    return i * i;
                }))
                .toList();

        assertEquals(window, peak.get(), "the window is a ceiling AND a floor when work is queued");
        assertEquals(3 * window, out.size());
        assertEquals(0, inFlight.get());
    }

    @Test
    @Timeout(60)
    void theBoundIsNotTheCoreCount() {
        // 500 blocking calls in flight on a machine with far fewer cores: the whole reason
        // mapConcurrent uses virtual threads rather than a pool sized to the CPU
        int window = 500;
        // on a machine with 125+ cores the comparison stops being interesting; the bound itself
        // still holds, but this test has nothing left to say, so it steps aside rather than fails
        assumeTrue(window > 4 * Runtime.getRuntime().availableProcessors(),
                "this box has too many cores for the test to mean anything");

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        IntStream.range(0, 1_000).boxed()
                .gather(Gatherers.mapConcurrent(window, i -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    sleep(20);
                    inFlight.decrementAndGet();
                    return i;
                }))
                .forEach(ignored -> { });

        assertEquals(window, peak.get());
    }

    @Test
    @Timeout(60)
    void everyMapperRunsOnAVirtualThread() {
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        AtomicInteger counted = new AtomicInteger();
        IntStream.range(0, 64).boxed()
                .gather(Gatherers.mapConcurrent(8, i -> {
                    if (!Thread.currentThread().isVirtual()) allVirtual.set(false);
                    counted.incrementAndGet();
                    return i;
                }))
                .forEach(ignored -> { });

        assertEquals(64, counted.get());
        assertTrue(allVirtual.get(), "mapConcurrent is specified to use virtual threads");
    }

    @ParameterizedTest(name = "maxConcurrency={0}")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsANonPositiveWindow(int window) {
        assertThrows(IllegalArgumentException.class,
                () -> Stream.of(1).gather(Gatherers.mapConcurrent(window, i -> i)).toList());
    }

    // ---------- order ----------

    @Test
    @Timeout(60)
    void resultsKeepEncounterOrderWhenWorkFinishesBackwards() {
        int n = 16;
        // element 0 is the slowest, so completion order is exactly the reverse of encounter order
        List<Integer> out = IntStream.range(0, n).boxed()
                .gather(Gatherers.mapConcurrent(n, i -> {
                    sleep(5 * (n - i));
                    return i;
                }))
                .toList();

        assertEquals(IntStream.range(0, n).boxed().toList(), out);
    }

    @Test
    @Timeout(60)
    void nullResultsPassThrough() {
        // worth pinning: a null from the mapper is not an error, it is a value
        List<Integer> out = Stream.of(1, 2, 3)
                .gather(Gatherers.mapConcurrent(2, i -> i == 2 ? null : i))
                .toList();
        assertEquals(Arrays.asList(1, null, 3), out);
    }

    // ---------- laziness and back-pressure ----------

    @Test
    @Timeout(60)
    void readAheadIsBoundedByTheWindow() {
        int window = 5;
        AtomicInteger mapped = new AtomicInteger();
        Iterator<Integer> it = IntStream.range(0, 10_000).boxed()
                .gather(Gatherers.mapConcurrent(window, i -> {
                    mapped.incrementAndGet();
                    IO.println(i + " is i ");
                    return i;
                }))
                .iterator();

        it.next();                                           // consume exactly one element
        sleep(500);                                          // ...and then stall

        assertEquals(window, mapped.get(),
                "a stalled consumer must stall the producer, one window ahead");
    }

    @Test
    @Timeout(60)
    void readAheadStaysWithinTheWindowForTheWholeStream() {
        // the test above watches only the first window. This one walks every element and checks the
        // bound at each step. Note what the bound is NOT: refilling happens in lumps, so mapped
        // sits at 5, 5, 5, 5, 5, 10, 11, 11... rather than tracking consumed + window one for one.
        // Only the inequality holds everywhere.
        int window = 5;
        int elements = 2_000;
        AtomicInteger mapped = new AtomicInteger();
        Iterator<Integer> it = IntStream.range(0, elements).boxed()
                .gather(Gatherers.mapConcurrent(window, i -> { mapped.incrementAndGet(); return i; }))
                .iterator();

        int consumed = 0;
        int worstReadAhead = 0;
        while (it.hasNext()) {
            assertEquals(consumed, it.next(), "elements arrived out of order");
            consumed++;
            int readAhead = mapped.get() - consumed;
            worstReadAhead = Math.max(worstReadAhead, readAhead);
            assertTrue(readAhead <= window,
                    "read " + readAhead + " ahead of the consumer at element " + consumed);
        }

        assertEquals(elements, consumed, "the walk did not reach the end");
        assertEquals(elements, mapped.get(), "every element is mapped exactly once");
        assertTrue(worstReadAhead > 0, "nothing was prefetched at all, so the bound proves nothing");
    }

    @Test
    @Timeout(60)
    void anInfiniteSourceIsSafeBehindALimit() {
        int window = 4;
        int take = 10;
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger mapped = new AtomicInteger();

        List<Integer> out = Stream.iterate(0, i -> { generated.incrementAndGet(); return i + 1; })
                .gather(Gatherers.mapConcurrent(window, i -> { mapped.incrementAndGet(); return i; }))
                .limit(take)
                .toList();

        assertEquals(IntStream.range(0, take).boxed().toList(), out);
        assertTrue(mapped.get() <= take + window,
                "mapped " + mapped.get() + ", which is more than take + window");
        assertTrue(generated.get() <= take + window,
                "generated " + generated.get() + ", which is more than take + window");
    }

    // ---------- failure ----------

    @Test
    @Timeout(60)
    void theMappersExceptionArrivesUnwrapped() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> IntStream.range(0, 10).boxed()
                        .gather(Gatherers.mapConcurrent(4, i -> {
                            if (i == 0) throw new IllegalStateException("boom at " + i);
                            return i;
                        }))
                        .toList());
        assertEquals("boom at 0", thrown.getMessage(),
                "no ExecutionException/CompletionException wrapper is expected");
    }

    @Test
    @Timeout(60)
    void aFailureIsWithheldUntilItsTurnInEncounterOrder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread pipeline = new Thread(() -> {
            try {
                IntStream.range(0, 8).boxed()
                        .gather(Gatherers.mapConcurrent(4, i -> {
                            if (i == 0) await(release);
                            if (i == 2) throw new IllegalStateException("boom at " + i);
                            return i;
                        }))
                        .toList();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        pipeline.setDaemon(true);                        // see EqualShareSemaphoreTest: never hang CI
        pipeline.start();

        Thread.sleep(200);
        assertTrue(pipeline.isAlive(), "the failure surfaced before its turn in encounter order");
        assertNull(thrown.get());

        release.countDown();
        pipeline.join(TimeUnit.SECONDS.toMillis(10));
        assertFalse(pipeline.isAlive(), "the pipeline never finished");
        assertNotNull(thrown.get(), "the failure was lost");
        assertEquals("boom at 2", thrown.get().getMessage());
    }

    @Test
    @Timeout(60)
    void aFailureTheConsumerNeverReachesNeverHappens() {
        AtomicInteger mapped = new AtomicInteger();
        List<Integer> out = IntStream.range(0, 100).boxed()
                .gather(Gatherers.mapConcurrent(4, i -> {
                    mapped.incrementAndGet();
                    if (i == 30) throw new IllegalStateException("boom at " + i);
                    return i;
                }))
                .limit(3)
                .toList();

        assertEquals(List.of(0, 1, 2), out);
        assertTrue(mapped.get() < 30, "element 30 should never have been reached, ran " + mapped.get());
    }

    // ---------- short-circuit ----------

    @Test
    @Timeout(60)
    void shortCircuitingDoesNotWaitForTheWindowToDrain() throws Exception {
        // every element but the first parks forever; findFirst must still return. If it drained
        // the window instead, this test would hang and the @Timeout would fail it.
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        try {
            int first = IntStream.range(0, 1_000).boxed()
                    .gather(Gatherers.mapConcurrent(16, i -> {
                        started.incrementAndGet();
                        if (i > 0) await(release);
                        return i;
                    }))
                    .findFirst()
                    .orElse(-1);

            assertEquals(0, first);
            assertTrue(started.get() > 1, "the window should have been filled ahead of the consumer");
            assertEquals(1, release.getCount(), "the stragglers were still parked, not awaited");
        } finally {
            release.countDown();                             // let the abandoned mappers exit
        }
    }

    // ---------- the window bounds the pipeline; the gate bounds a tenant ----------

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theGateBoundsEachTenantAndTheWindowDoesNot() {
        int[] gated = perTenantPeaks(new EqualShareSemaphore(12, 4), 4, 40);
        assertEquals(4, gated.length);
        for (int peak : gated) {
            assertEquals(3, peak, "each tenant is held to its share of 12/4, inside a window of 40");
        }

        // the control: with a gate too wide to bind, the same pipeline runs to the window —
        // without this, [3, 3, 3, 3] would also be consistent with the window doing the work
        int[] ungated = perTenantPeaks(new EqualShareSemaphore(400, 4), 4, 40);
        for (int peak : ungated) {
            assertTrue(peak > 3, "the window should bound this, not the gate: " + Arrays.toString(ungated));
            assertTrue(peak <= 40, "nothing may exceed the window: " + Arrays.toString(ungated));
        }
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)   // see above
    void theGateHoldsUpUnderManyVirtualThreads() {
        // the gate parks virtual threads on a ReentrantLock/Condition, which is what makes it
        // usable inside a mapper at all; this drives far more of them than there are carriers
        EqualShareSemaphore gate = new EqualShareSemaphore(8, 2);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        IntStream.range(0, 2_000).boxed()
                .gather(Gatherers.mapConcurrent(200, i -> {
                    Ticket permit = acquire(gate, i % 2);
                    try (permit) {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        inFlight.decrementAndGet();
                        return i;
                    }
                }))
                .forEach(ignored -> { });

        assertTrue(peak.get() <= gate.totalPermits(), "peak " + peak.get() + " exceeded the gate");
        assertEquals(0, gate.totalHeld(), "permits leaked");
        assertEquals(0, gate.belowShareDenials(), "a party below its share was made to wait");
    }

    private static Ticket acquire(EqualShareSemaphore gate, int party) {
        try {
            return gate.acquire(party);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private int[] perTenantPeaks(EqualShareSemaphore gate, int tenants, int itemsEach) {
        AtomicInteger[] inFlight = new AtomicInteger[tenants];
        AtomicInteger[] peak = new AtomicInteger[tenants];
        for (int t = 0; t < tenants; t++) {
            inFlight[t] = new AtomicInteger();
            peak[t] = new AtomicInteger();
        }
        List<Thread> drivers = new ArrayList<>();
        for (int t = 0; t < tenants; t++) {
            final int tenant = t;
            Thread driver = Thread.ofVirtual().start(() ->
                    IntStream.range(0, itemsEach).boxed()
                            .gather(Gatherers.mapConcurrent(itemsEach, i -> {   // no bound here
                                EqualShareSemaphore.Ticket permit = acquire(gate, tenant);
                                try (permit) {                                  // the bound is here
                                    peak[tenant].accumulateAndGet(
                                            inFlight[tenant].incrementAndGet(), Math::max);
                                    sleep(2);
                                    inFlight[tenant].decrementAndGet();
                                    return i * i;
                                }
                            }))
                            .forEach(ignored -> { }));
            drivers.add(driver);
        }
        drivers.forEach(MapConcurrentTest::join);
        return Arrays.stream(peak).mapToInt(AtomicInteger::get).toArray();
    }

    private static void join(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

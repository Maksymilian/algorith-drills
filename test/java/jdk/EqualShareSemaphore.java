package jdk;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A counting semaphore that splits its permits <em>equally</em> between a fixed set of
 * parties, so that no party can be starved by another's appetite.
 *
 * <p>A plain {@link Semaphore} answers only "how many permits are left". One greedy
 * caller can hold all of them, and a well-behaved caller then waits behind it for as
 * long as the greedy one cares to run. That is fine when the callers are cooperating
 * threads of one job; it is a denial of service when they are tenants, priorities, or
 * customers sharing one connection pool.
 *
 * <h2>The algorithm</h2>
 * With {@code total} permits and {@code parties} parties, let
 * <pre>
 *     share   = total / parties        (integer division — the guarantee)
 *     surplus = total - parties*share  (the remainder — first come, first served)
 * </pre>
 * A request from party <i>p</i> is admitted when either
 * <ol>
 *   <li><b>{@code held[p] < share}</b> — it is spending its own reservation, or</li>
 *   <li><b>{@code surplusHeld < surplus}</b> — it is above its share and spare capacity exists.</li>
 * </ol>
 *
 * <h2>Why rule 1 never has to check the total</h2>
 * Because every party above its share draws from the surplus pool, the others can hold
 * at most {@code (parties-1)*share + surplus} permits. So when {@code held[p] < share},
 * <pre>
 *     totalHeld = others + held[p] &lt;= (parties-1)*share + surplus + held[p] &lt; total
 * </pre>
 * — there is provably a free permit. <b>A party below its share never blocks</b>: that
 * is the guarantee, and it holds no matter what the other parties do. {@code requireCapacity}
 * re-checks that proof on every admission, {@code tryAcquire} counts every refusal handed to a
 * below-share caller, and {@code main} drives both under load.
 *
 * <h2>What it costs</h2>
 * The reservation is not work-conserving: a party can never exceed
 * {@code share + surplus}, even while every other party is idle. Handing an idle
 * party's reservation out and taking it back later would need preemption, which a
 * permit-based gate cannot do — see docs/jdk/map-concurrent-and-equal-share-semaphore.md
 * for that trade-off and the measured cost of the {@code fairQueueing} flag, and
 * {@code test/java/jdk/EqualShareSemaphoreTest.java} for the guarantee checked exhaustively.
 */
public final class EqualShareSemaphore {

    private final int total;
    private final int parties;
    private final int share;
    private final int surplus;

    private final ReentrantLock lock;
    private final Condition released;

    private final int[] held;        // permits currently held, per party
    private int totalHeld;
    private int surplusHeld;         // sum over parties of max(0, held[p] - share)
    private long belowShareDenials;  // must stay 0: that is the guarantee, counted

    /** Equal shares, with barging allowed among surplus waiters — the fast default. */
    public EqualShareSemaphore(int totalPermits, int parties) {
        this(totalPermits, parties, false);
    }

    /**
     * @param fairQueueing serve waiters FIFO. This does <em>not</em> affect the equal-share
     *     guarantee — a party below its share never waits, so it is never in a queue to be
     *     barged out of. It only orders the contest for the <em>surplus</em>, and in the
     *     benchmark in {@code main} it costs two orders of magnitude of throughput.
     */
    public EqualShareSemaphore(int totalPermits, int parties, boolean fairQueueing) {
        if (parties <= 0) throw new IllegalArgumentException("parties must be > 0");
        if (totalPermits < parties) {
            throw new IllegalArgumentException(
                    "need at least one permit per party: " + totalPermits + " < " + parties);
        }

        this.total = totalPermits;
        this.parties = parties;
        this.share = totalPermits / parties;
        this.surplus = totalPermits - parties * share;
        this.held = new int[parties];
        this.lock = new ReentrantLock(fairQueueing);
        this.released = lock.newCondition();
    }

    public int totalPermits()   { return total; }
    public int parties()        { return parties; }
    /** Permits party <i>p</i> can always get without waiting. */
    public int guaranteedShare(){ return share; }
    /** Permits nobody is guaranteed — handed out first come, first served. */
    public int surplus()        { return surplus; }
    /** The most one party can ever hold at once. */
    public int maxPerParty()    { return share + surplus; }

    /** Blocks until party {@code p} may hold one more permit. */
    public Ticket acquire(int party) throws InterruptedException {
        checkParty(party);
        lock.lockInterruptibly();
        try {
            while (!admissible(party)) {
                released.await();
            }
            requireCapacity(party);
            take(party);
            return new Ticket(party);
        } finally {
            lock.unlock();
        }
    }

    /** @return a ticket, or {@code null} if none became available in time. */
    public Ticket tryAcquire(int party, long timeout, TimeUnit unit) throws InterruptedException {
        checkParty(party);
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (!admissible(party)) {
                if (nanos <= 0) {
                    if (held[party] < share) belowShareDenials++;   // can never happen
                    return null;
                }
                nanos = released.awaitNanos(nanos);
            }
            requireCapacity(party);
            take(party);
            return new Ticket(party);
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot, for tests and logging. */
    public int held(int party) {
        checkParty(party);
        lock.lock();
        try {
            return held[party];
        } finally {
            lock.unlock();
        }
    }

    /** Times a party below its share was refused a permit. The guarantee says: never. */
    public long belowShareDenials() {
        lock.lock();
        try {
            return belowShareDenials;
        } finally {
            lock.unlock();
        }
    }

    public int totalHeld() {
        lock.lock();
        try {
            return totalHeld;
        } finally {
            lock.unlock();
        }
    }

    /** One held permit. Closing it is the release; use it in try-with-resources. */
    public final class Ticket implements AutoCloseable {
        private final int party;
        private boolean open = true;

        private Ticket(int party) { this.party = party; }

        @Override public void close() {
            lock.lock();
            try {
                if (!open) return;                       // idempotent: no permit inflation
                open = false;
                if (held[party] > share) surplusHeld--;
                held[party]--;
                totalHeld--;
                released.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    // ---------- the two lines that are the algorithm ----------

    private boolean admissible(int party) {
        return held[party] < share       // rule 1: own reservation, provably free
                || surplusHeld < surplus; // rule 2: above share, only from the spare pool
    }

    /** Rule 1's proof, checked at runtime rather than trusted. */
    private void requireCapacity(int party) {
        if (held[party] < share && totalHeld >= total) {
            throw new IllegalStateException("invariant broken: party " + party + " holds "
                    + held[party] + " < share " + share + " but all " + total + " permits are out");
        }
    }

    private void take(int party) {
        if (held[party] >= share) surplusHeld++;
        held[party]++;
        totalHeld++;
    }

    private void checkParty(int party) {
        if (party < 0 || party >= parties) {
            throw new IndexOutOfBoundsException("party " + party + " of " + parties);
        }
    }
}

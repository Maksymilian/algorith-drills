package dynamic;

import java.util.List;
import java.util.stream.IntStream;

/**
 * HackerRank "Candies": hand out candy to children standing in a line, each child getting at least
 * one, and any child rated higher than an immediate neighbour getting strictly more than that
 * neighbour. Minimise the total handed out.
 * <p>
 * Only the <i>higher</i> child is constrained, which is what makes the problem interesting: a rating
 * that dips or repeats imposes nothing, so the constraints form chains that run in both directions
 * and the greedy left-to-right pass fails on {@code [3, 2, 1]} - it hands out {@code [1, 1, 1]},
 * while the answer is {@code [3, 2, 1]}.
 * <p>
 * Every constraint is a lower bound, so the cheapest legal handout is the smallest assignment
 * satisfying all of them at once, and it splits cleanly by direction:
 * <pre>
 *     L[i] = ratings[i] &gt; ratings[i-1] ? L[i-1] + 1 : 1        // left-neighbour chains
 *     R[i] = ratings[i] &gt; ratings[i+1] ? R[i+1] + 1 : 1        // right-neighbour chains
 *     candy[i] = max(L[i], R[i])                               answer = sum of candy
 * </pre>
 * <b>State</b> {@code L[i]} is the answer for the prefix {@code ratings[0..i]} ignoring the right
 * neighbour, {@code R[i]} the mirror for the suffix. <b>Transition</b>: a child rated above its
 * predecessor must outbid it, which is the predecessor's own answer plus one; otherwise the chain
 * breaks and the child is free to take the minimum. <b>Base case</b> 1, the floor every child is
 * owed. Both directions are <i>needed</i>: {@code L} alone underfeeds every descent, {@code R} alone
 * every ascent, and taking the larger of the two satisfies both chains at once - see
 * {@code docs/dynamic/candies.md} for why that maximum is not merely valid but minimal.
 * <p>
 * Read as a table the recurrence wants O(n) memory, since {@code R} is filled right to left and so
 * cannot be folded in with {@code L} on a single forward pass. Two forms of it are implemented here:
 * <ul>
 *   <li>{@link #candiesWithTable(int[])} keeps both memo tables and sums them - the textbook two
 *       passes, O(n) time and O(n) memory, kept for comparison and as the readable statement of the
 *       recurrence;</li>
 *   <li>{@link #candies(int[])} and its overloads do the same work in one pass and O(1) memory, by
 *       never materialising {@code R}: {@code R[i]} exceeds {@code L[i]} only inside a descending
 *       run, and the extra candy a descent owes can be added as the run is walked rather than after
 *       it is known - one for every child already in the descent, plus one for the peak once the
 *       descent outgrows the ascent that led into it. <b>This is the one to use.</b></li>
 * </ul>
 * They agree on every input. The tables are what make either of them linear - derived from scratch,
 * each {@code L[i]} and {@code R[i]} would be rescanned along its run, which is O(n<sup>2</sup>)
 * on a monotone line - but every entry has exactly one dependent, so the chains can be walked
 * incrementally and nothing is lost by rolling the tables up into counters. What they cost is the
 * whole line in memory at once, which is why only the fold has
 * {@link java.util.stream.IntStream} and {@link List} overloads.
 * <p>
 * Time O(n) either way. The total needs a {@code long}: at the problem's own bound of
 * 10<sup>5</sup> children a strictly increasing line costs 5 000 050 000 candies, comfortably past
 * {@link Integer#MAX_VALUE}.
 */
public class Candies {

    /**
     * The HackerRank signature: the minimum number of candies for children rated {@code arr}, in
     * line order.
     *
     * @param n   the number of children, which the platform passes alongside the list; the list's
     *            own size is what is used
     * @param arr the ratings in line order, may be empty
     * @return the minimum total number of candies
     * @throws IllegalArgumentException if {@code arr} is null
     * @throws NullPointerException     if any rating is null
     */
    public static long candies(int n, List<Integer> arr) {
        if (arr == null) throw new IllegalArgumentException("arr must not be null");

        Run run = new Run();
        for (Integer rating : arr) {
            run.advance(rating);
        }
        return run.total;
    }

    /**
     * Same handout as {@link #candies(int, List)}, over an array. Time O(n), space O(1); the input
     * is not modified.
     *
     * @param ratings the ratings in line order, may be empty
     * @return the minimum total number of candies
     * @throws IllegalArgumentException if {@code ratings} is null
     */
    public static long candies(int[] ratings) {
        if (ratings == null) throw new IllegalArgumentException("ratings must not be null");

        Run run = new Run();
        for (int rating : ratings) {
            run.advance(rating);
        }
        return run.total;
    }

    /**
     * Same handout as {@link #candies(int, List)}, reading the line from a stream so that no array
     * has to be held in memory. The fold keeps five numbers whatever the length, so a line of any
     * size can be priced in constant space - straight off a file, a database cursor or a generator.
     * <p>
     * The recurrence is order-dependent, so the stream is consumed sequentially and in encounter
     * order even when a parallel one is handed in.
     *
     * @param ratings the ratings in line order, may be empty
     * @return the minimum total number of candies
     * @throws IllegalArgumentException if {@code ratings} is null
     */
    public static long candies(IntStream ratings) {
        if (ratings == null) throw new IllegalArgumentException("ratings must not be null");

        Run run = new Run();
        ratings.sequential().forEachOrdered(run::advance);
        return run.total;
    }

    /**
     * Same handout as {@link #candies(int[])}, with both memo tables kept: {@code L} filled left to
     * right, {@code R} right to left, and the answer summed from the larger of the two at each
     * index. O(n) time, O(n) memory - two {@code int[]} of the line's length.
     * <p>
     * This is the textbook form, and the one to read the recurrence off; {@link #candies(int[])} is
     * the one to run. The tables are what make it linear rather than quadratic, but each entry has a
     * single dependent - {@code left[i]} feeds only {@code left[i + 1]} - so none is ever looked up
     * twice, and rolling them up into the fold's counters loses nothing. What they cost is the whole
     * line in memory, so there is no stream overload to go with this one. Their one advantage is
     * that the individual shares survive the call, which the fold discards as it goes.
     *
     * @param ratings the ratings in line order, may be empty
     * @return the minimum total number of candies
     * @throws IllegalArgumentException if {@code ratings} is null
     */
    public static long candiesWithTable(int[] ratings) {
        if (ratings == null) throw new IllegalArgumentException("ratings must not be null");

        int n = ratings.length;
        int[] left = new int[n];    // L[i]: the answer for ratings[0..i], right neighbour ignored
        int[] right = new int[n];   // R[i]: the mirror, for ratings[i..n-1]

        for (int i = 0; i < n; i++) {
            left[i] = i > 0 && ratings[i] > ratings[i - 1] ? left[i - 1] + 1 : 1;
        }
        for (int i = n - 1; i >= 0; i--) {
            right[i] = i < n - 1 && ratings[i] > ratings[i + 1] ? right[i + 1] + 1 : 1;
        }

        long total = 0;
        for (int i = 0; i < n; i++) {
            total += Math.max(left[i], right[i]);   // both chains at once, and nothing to spare
        }
        return total;
    }

    /**
     * The running shape of the line: how far the current run has climbed or fallen, and what the
     * handout costs so far. This is the {@code L}/{@code R} table rolled up - the only parts of it
     * the transition can still reach.
     */
    private static final class Run {

        private boolean started;
        private int previousRating;
        private long ascent;       // consecutive rises ending at the previous child, so L = ascent + 1
        private long descent;      // consecutive falls since the peak the run came off
        private long peakAscent;   // the ascent that peak was reached by, so its L = peakAscent + 1
        private long total;

        /** Folds one child in, settling the candy owed to it and any the run behind it now owes. */
        void advance(int rating) {
            if (!started) {
                started = true;
                previousRating = rating;
                total = 1;             // the first child is owed the floor and nothing more
                return;
            }

            if (rating > previousRating) {
                descent = 0;
                peakAscent = ++ascent;
                total += 1 + ascent;   // outbid the predecessor: L = ascent + 1, and R is 1 here
            } else if (rating == previousRating) {
                ascent = descent = peakAscent = 0;   // equal ratings constrain neither child
                total += 1;
            } else {
                ascent = 0;
                descent++;
                // One more candy for each child already in the descent, and the new child's own -
                // that is `descent` in total, the difference between two triangular numbers. The
                // peak needs one more only once the descent has outgrown the ascent it came off.
                total += descent + (peakAscent < descent ? 1 : 0);
            }

            previousRating = rating;
        }
    }

    private Candies() {
    }
}

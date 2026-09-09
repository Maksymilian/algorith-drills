package dynamic;

import java.util.stream.IntStream;

/**
 * HackerRank "Max Array Sum": pick a subset of non-adjacent elements with the largest sum.
 * <p>
 * The dynamic program behind every method here is one line:
 * <pre>
 *     best[i] = max(best[i - 1], best[i - 2] + arr[i])        best[-1] = best[-2] = 0
 * </pre>
 * <b>State</b> {@code best[i]} is the answer for the prefix {@code arr[0..i]}. <b>Transition</b>:
 * element {@code i} is either skipped, so the prefix answer without it carries over, or taken,
 * which rules out element {@code i - 1} and leaves the prefix ending at {@code i - 2}.
 * <b>Base case</b> 0 for both empty prefixes, which is what makes the empty subset a candidate -
 * so the answer is never negative and an all-negative input returns 0, with no special case.
 * <p>
 * Two properties make this scale to sets far larger than the problem's stated 10<sup>5</sup>:
 * <ul>
 *   <li><b>O(1) memory.</b> The transition reaches back only two states, so the {@code best} table
 *       collapses to two variables. No table is allocated, whatever the input size, and
 *       {@link #maxSubsetSumAsLong(IntStream)} can therefore fold a set that never fits in memory.</li>
 *   <li><b>No recursion.</b> The bottom-up loop has no stack depth. The equivalent top-down
 *       memoized recursion blows the JVM stack somewhere around n = 10<sup>4</sup>..10<sup>5</sup>.</li>
 * </ul>
 * The running sums are accumulated in a {@code long}, so the only real limit is the accumulator:
 * with {@code int} inputs it takes on the order of 10<sup>15</sup> elements to overflow.
 */
public class MaxSubsetSum {

    /**
     * Returns the maximum sum of a subset of {@code arr} that contains no two adjacent elements.
     * <p>
     * This is the HackerRank signature. Time O(n), space O(1).
     *
     * @param arr the input array, may be empty
     * @return the maximum non-adjacent subset sum, at least 0
     * @throws IllegalArgumentException if {@code arr} is null
     * @throws ArithmeticException      if the answer does not fit in an {@code int}, which needs a
     *                                  set larger than the problem allows - use
     *                                  {@link #maxSubsetSumAsLong(int[])} for those
     */
    public static int maxSubsetSum(int[] arr) {
        return Math.toIntExact(maxSubsetSumAsLong(arr));
    }

    /**
     * Same dynamic program as {@link #maxSubsetSum(int[])}, widened for sets big enough that the
     * answer passes {@link Integer#MAX_VALUE} - which starts around 2*10<sup>5</sup> elements at
     * the maximum allowed value.
     *
     * @param arr the input array, may be empty
     * @return the maximum non-adjacent subset sum, at least 0
     * @throws IllegalArgumentException if {@code arr} is null
     */
    public static long maxSubsetSumAsLong(int[] arr) {
        if (arr == null) throw new IllegalArgumentException("arr must not be null");

        Best best = new Best();
        for (int value : arr) {
            best.advance(value);
        }
        return best.forPrefix;
    }

    /**
     * Same dynamic program as {@link #maxSubsetSum(int[])}, reading the elements from a stream so
     * that no array has to be held in memory. Since the two-variable state is all the recurrence
     * needs, a set of any length can be folded in constant space - straight off a file, a database
     * cursor or a generator.
     * <p>
     * The recurrence is order-dependent, so the stream is consumed sequentially and in encounter
     * order even when a parallel one is handed in.
     *
     * @param values the elements in order, may be empty
     * @return the maximum non-adjacent subset sum, at least 0
     * @throws IllegalArgumentException if {@code values} is null
     */
    public static long maxSubsetSumAsLong(IntStream values) {
        if (values == null) throw new IllegalArgumentException("values must not be null");

        Best best = new Best();
        values.sequential().forEachOrdered(best::advance);
        return best.forPrefix;
    }

    /** The two rows of the {@code best} table that the transition can still reach. */
    private static final class Best {

        private long forPrefixBeforeLast;   // best[i - 2]
        private long forPrefix;             // best[i - 1], and best[i] once advanced

        /** Folds one element in: {@code best[i] = max(best[i - 1], best[i - 2] + value)}. */
        void advance(int value) {
            long withValue = forPrefixBeforeLast + value;
            forPrefixBeforeLast = forPrefix;
            forPrefix = Math.max(forPrefix, withValue);
        }
    }

    private MaxSubsetSum() {
    }
}

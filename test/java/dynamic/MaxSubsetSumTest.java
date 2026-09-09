package dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dynamic.MaxSubsetSum.maxSubsetSum;
import static dynamic.MaxSubsetSum.maxSubsetSumAsLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MaxSubsetSumTest {

    static Stream<Arguments> knownCases() {
        return Stream.of(
                Arguments.of("sample 0", new int[]{3, 7, 4, 6, 5}, 13),          // 7 + 6
                Arguments.of("sample 1", new int[]{2, 1, 5, 8, 4}, 11),          // 2 + 5 + 4
                Arguments.of("sample 2", new int[]{3, 5, -7, 8, 10}, 15),        // 5 + 10
                Arguments.of("problem example", new int[]{-2, 1, 3, -4, 5}, 8),  // 3 + 5
                Arguments.of("empty array", new int[]{}, 0),
                Arguments.of("single positive", new int[]{7}, 7),
                Arguments.of("single negative", new int[]{-7}, 0),
                Arguments.of("all negative", new int[]{-2, -4, -6, -1}, 0),
                Arguments.of("all zeroes", new int[]{0, 0, 0}, 0),
                Arguments.of("two elements, pick larger", new int[]{4, 9}, 9),
                Arguments.of("adjacent peaks", new int[]{5, 100, 5}, 100),
                Arguments.of("alternating best", new int[]{6, 1, 6, 1, 6}, 18),
                Arguments.of("negative gap worth crossing", new int[]{10, -1, -1, 10}, 20),
                Arguments.of("extreme values", new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE}, 0)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("knownCases")
    void returnsMaxNonAdjacentSum(String name, int[] arr, int expected) {
        assertEquals(expected, maxSubsetSum(arr));
        assertEquals(expected, maxSubsetSumAsLong(arr));
        assertEquals(expected, maxSubsetSumAsLong(Arrays.stream(arr)));
    }

    @Test
    void doesNotModifyInput() {
        int[] arr = {3, 5, -7, 8, 10};
        maxSubsetSum(arr);
        assertEquals("[3, 5, -7, 8, 10]", Arrays.toString(arr));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> maxSubsetSum((int[]) null));
        assertThrows(IllegalArgumentException.class, () -> maxSubsetSumAsLong((int[]) null));
        assertThrows(IllegalArgumentException.class, () -> maxSubsetSumAsLong((IntStream) null));
    }

    @Test
    void matchesBruteForceOnRandomArrays() {
        Random random = new Random(20260908L);

        for (int trial = 0; trial < 2_000; trial++) {
            int[] arr = new int[random.nextInt(13)];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = random.nextInt(41) - 20;   // -20..20, so negatives are common
            }
            assertEquals(bruteForce(arr), maxSubsetSumAsLong(arr), Arrays.toString(arr));
        }
    }

    // --- very large sets -------------------------------------------------------------------------

    /**
     * 10 million random elements, cross-checked against the O(n)-memory form of the same
     * recurrence. Collapsing the table to two variables is the one step that could plausibly break
     * at scale, so it is worth checking against a table that is actually kept.
     */
    @Test
    void matchesTableDpOnTenMillionRandomElements() {
        int[] arr = new Random(20260908L).ints(10_000_000, -10_000, 10_001).toArray();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertEquals(tableDp(arr), maxSubsetSumAsLong(arr)));
    }

    /** 10 million alternating peaks and troughs: every peak is pickable, every trough is not. */
    @Test
    void solvesTenMillionAlternatingElements() {
        int n = 10_000_000;
        int[] arr = IntStream.range(0, n).map(i -> i % 2 == 0 ? 10_000 : -10_000).toArray();

        assertEquals((n / 2) * 10_000L, maxSubsetSumAsLong(arr));
    }

    /**
     * 200 million elements folded straight off a stream. The set is roughly 800 MB of ints, far
     * more than this JVM's heap allows (see the surefire argLine in pom.xml), so it can only pass
     * if the dynamic program really does keep O(1) state and never materializes the input.
     */
    @Test
    void foldsTwoHundredMillionElementsFromAStream() {
        int n = 200_000_000;

        long sum = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                maxSubsetSumAsLong(IntStream.range(0, n).map(i -> 10_000)));

        assertEquals((n / 2) * 10_000L, sum);   // every other element, so 10^12
    }

    @Test
    void parallelStreamIsFoldedInEncounterOrder() {
        int[] arr = new Random(11L).ints(1_000_000, -10_000, 10_001).toArray();

        assertEquals(maxSubsetSumAsLong(arr), maxSubsetSumAsLong(Arrays.stream(arr).parallel()));
    }

    // --- the int contract at its limits ----------------------------------------------------------

    @Test
    void intOverloadAcceptsAnAnswerOfExactlyIntegerMaxValue() {
        assertEquals(Integer.MAX_VALUE, maxSubsetSum(new int[]{Integer.MAX_VALUE}));
        assertEquals(Integer.MAX_VALUE, maxSubsetSum(new int[]{Integer.MAX_VALUE - 1, 7, 1}));
    }

    @Test
    void intOverloadRefusesToWrapWhenTheAnswerOutgrowsIt() {
        int[] arr = {Integer.MAX_VALUE, 5, Integer.MAX_VALUE};

        assertThrows(ArithmeticException.class, () -> maxSubsetSum(arr));
        assertEquals(2L * Integer.MAX_VALUE, maxSubsetSumAsLong(arr));
    }

    @Test
    void longOverloadCarriesAnAnswerWellPastIntegerRange() {
        int[] arr = new int[1_000_000];
        Arrays.fill(arr, 10_000);

        assertEquals(500_000L * 10_000, maxSubsetSumAsLong(arr));   // 5 * 10^9
        assertThrows(ArithmeticException.class, () -> maxSubsetSum(arr));
    }

    // --- reference implementations ---------------------------------------------------------------

    /** The same recurrence with the whole {@code best} table kept: O(n) time, O(n) memory. */
    private static long tableDp(int[] arr) {
        long[] best = new long[arr.length + 2];   // best[i + 2] is the answer for arr[0..i]

        for (int i = 0; i < arr.length; i++) {
            best[i + 2] = Math.max(best[i + 1], best[i] + arr[i]);
        }

        return best[arr.length + 1];
    }

    /** Checks every subset via a bitmask, rejecting the ones with adjacent elements. */
    private static long bruteForce(int[] arr) {
        long best = 0;   // the empty subset

        for (int mask = 1; mask < (1 << arr.length); mask++) {
            if ((mask & (mask << 1)) != 0) continue;

            long sum = 0;
            for (int i = 0; i < arr.length; i++) {
                if ((mask & (1 << i)) != 0) sum += arr[i];
            }
            best = Math.max(best, sum);
        }

        return best;
    }
}

package dynamic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static dynamic.Candies.candies;
import static dynamic.Candies.candiesWithTable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandiesTest {

    static Stream<Arguments> knownCases() {
        return Stream.of(
                Arguments.of("sample 0", new int[]{1, 2, 2}, 4L),
                Arguments.of("sample 1", new int[]{2, 4, 2, 6, 1, 7, 8, 9, 2, 1}, 19L),
                Arguments.of("sample 2", new int[]{2, 4, 3, 5, 2, 6, 4, 5}, 12L),
                Arguments.of("greedy trap: a descent the forward pass underfeeds", new int[]{3, 2, 1}, 6L),
                Arguments.of("empty line", new int[]{}, 0L),
                Arguments.of("single child", new int[]{5}, 1L),
                Arguments.of("two equal", new int[]{1, 1}, 2L),
                Arguments.of("two ascending", new int[]{1, 2}, 3L),
                Arguments.of("two descending", new int[]{2, 1}, 3L),
                Arguments.of("all equal", new int[]{4, 4, 4, 4}, 4L),
                Arguments.of("strictly increasing", new int[]{1, 2, 3, 4, 5}, 15L),
                Arguments.of("strictly decreasing", new int[]{5, 4, 3, 2, 1}, 15L),
                Arguments.of("peak", new int[]{1, 2, 3, 2, 1}, 9L),
                Arguments.of("valley", new int[]{3, 2, 1, 2, 3}, 11L),
                Arguments.of("descent outgrows the ascent", new int[]{1, 2, 3, 2, 1, 0}, 13L),
                Arguments.of("long descent off a short ascent", new int[]{1, 5, 4, 3, 2, 1}, 16L),
                Arguments.of("plateau breaks the chain", new int[]{1, 2, 2, 1}, 6L),
                Arguments.of("plateau at the peak", new int[]{1, 2, 3, 3, 2, 1}, 12L),
                Arguments.of("plateau between descents", new int[]{3, 2, 2, 1}, 6L),
                Arguments.of("valley then a second peak", new int[]{3, 2, 1, 2, 1}, 9L),
                Arguments.of("sawtooth", new int[]{1, 2, 1, 2, 1}, 7L),
                Arguments.of("ratings may repeat far apart", new int[]{1, 3, 1, 3, 1}, 7L),
                Arguments.of("negative ratings are only compared", new int[]{-3, -2, -1, -2}, 7L)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("knownCases")
    void handsOutTheMinimumForKnownLines(String name, int[] ratings, long expected) {
        assertEquals(expected, candies(ratings));
        assertEquals(expected, candies(ratings.length, boxed(ratings)));
        assertEquals(expected, candies(Arrays.stream(ratings)));
        assertEquals(expected, candiesWithTable(ratings), "the shipped two-pass table disagrees");
        assertEquals(expected, twoPassDp(ratings), "the O(n)-memory table disagrees");
    }

    @Test
    void doesNotModifyInput() {
        int[] ratings = {2, 4, 3, 5, 2, 6, 4, 5};
        candies(ratings);
        candiesWithTable(ratings);
        assertEquals("[2, 4, 3, 5, 2, 6, 4, 5]", Arrays.toString(ratings));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> candies((int[]) null));
        assertThrows(IllegalArgumentException.class, () -> candiesWithTable(null));
        assertThrows(IllegalArgumentException.class, () -> candies((IntStream) null));
        assertThrows(IllegalArgumentException.class, () -> candies(0, null));
        assertThrows(NullPointerException.class, () -> candies(2, Arrays.asList(1, null)));
    }

    /** The platform passes the count alongside the list; the list is the line. */
    @Test
    void readsTheWholeListWhateverTheCountSays() {
        List<Integer> line = List.of(1, 2, 2);

        assertEquals(4L, candies(3, line));
        assertEquals(4L, candies(0, line));
        assertEquals(4L, candies(99, line));
    }

    // --- cross-checks ----------------------------------------------------------------------------

    @Test
    void matchesBruteForceOnEveryShortLine() {
        for (int n = 0; n <= 6; n++) {
            for (int[] ratings : allLines(n, 3)) {   // every line over a 3-rating alphabet
                long expected = bruteForce(ratings);
                assertEquals(expected, candies(ratings), Arrays.toString(ratings));
                assertEquals(expected, candiesWithTable(ratings), Arrays.toString(ratings));
            }
        }
    }

    /**
     * Every rise/level/fall shape a line of up to six children can take - 3^(n-1) of them - brute
     * forced. Three rating values cannot build a run longer than three, and long runs are exactly
     * what the descent bookkeeping is for.
     */
    @Test
    void matchesBruteForceOnEveryShortShape() {
        for (int n = 1; n <= 6; n++) {
            for (int[] ratings : allShapes(n)) {
                long expected = bruteForce(ratings);
                assertEquals(expected, candies(ratings), Arrays.toString(ratings));
                assertEquals(expected, candiesWithTable(ratings), Arrays.toString(ratings));
            }
        }
    }

    @Test
    void matchesTwoPassDpOnRandomLines() {
        Random random = new Random(20260912L);

        for (int trial = 0; trial < 20_000; trial++) {
            int[] ratings = new int[random.nextInt(40)];
            int distinct = 2 + random.nextInt(8);   // few values -> plateaus and short runs are common
            for (int i = 0; i < ratings.length; i++) {
                ratings[i] = random.nextInt(distinct);
            }
            long expected = twoPassDp(ratings);
            assertEquals(expected, candies(ratings), Arrays.toString(ratings));
            assertEquals(expected, candiesWithTable(ratings), Arrays.toString(ratings));
        }
    }

    @Test
    void matchesTwoPassDpOnRandomWalks() {
        Random random = new Random(20260912L);

        for (int trial = 0; trial < 20_000; trial++) {
            int[] ratings = randomWalk(random, random.nextInt(60));   // long runs, peaks and valleys
            long expected = twoPassDp(ratings);
            assertEquals(expected, candies(ratings), Arrays.toString(ratings));
            assertEquals(expected, candiesWithTable(ratings), Arrays.toString(ratings));
        }
    }

    // --- very large lines ------------------------------------------------------------------------

    /**
     * 10 million children, cross-checked against the two-pass form of the same recurrence. Never
     * materialising the right-to-left pass is the one step that could plausibly break at scale, so
     * it is worth checking against a table that is actually kept.
     */
    @Test
    void matchesTwoPassDpOnTenMillionRandomRatings() {
        int[] ratings = new Random(20260912L).ints(10_000_000, 0, 10).toArray();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            long expected = twoPassDp(ratings);
            assertEquals(expected, candies(ratings));
            assertEquals(expected, candiesWithTable(ratings));   // 80 MB of tables, under the argLine
        });
    }

    /** The same at 10 million, on a walk - so the runs are long and the peak bookkeeping matters. */
    @Test
    void matchesTwoPassDpOnATenMillionStepWalk() {
        int[] ratings = randomWalk(new Random(11L), 10_000_000);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertEquals(twoPassDp(ratings), candies(ratings)));
    }

    /** At the problem's own bound a strictly increasing line already outgrows an {@code int}. */
    @Test
    void totalOutgrowsIntegerRangeWithinTheProblemsConstraints() {
        int n = 100_000;
        int[] ratings = IntStream.rangeClosed(1, n).toArray();

        long total = candies(ratings);

        assertEquals(n * (n + 1L) / 2, total);
        assertEquals(5_000_050_000L, total);
        assertTrue(total > Integer.MAX_VALUE, "the answer must not be returned as an int");
    }

    /**
     * 200 million children folded straight off a stream. The line is roughly 800 MB of ints, far
     * more than this JVM's heap allows (see the surefire argLine in pom.xml), so it can only pass if
     * the fold really does keep O(1) state and never materializes the input.
     */
    @Test
    void foldsTwoHundredMillionChildrenFromAStream() {
        int n = 200_000_000;

        long total = assertTimeoutPreemptively(Duration.ofSeconds(60), () ->
                candies(IntStream.range(0, n)));

        assertEquals(n * (n + 1L) / 2, total);   // strictly increasing, so 2 * 10^16
    }

    @Test
    void parallelStreamIsFoldedInEncounterOrder() {
        int[] ratings = randomWalk(new Random(11L), 1_000_000);

        assertEquals(candies(ratings), candies(Arrays.stream(ratings).parallel()));
    }

    // --- reference implementations ---------------------------------------------------------------

    /** The textbook two passes, with both {@code L} and {@code R} kept: O(n) time, O(n) memory. */
    private static long twoPassDp(int[] ratings) {
        int n = ratings.length;
        int[] left = new int[n];
        int[] right = new int[n];

        for (int i = 0; i < n; i++) {
            left[i] = i > 0 && ratings[i] > ratings[i - 1] ? left[i - 1] + 1 : 1;
        }
        for (int i = n - 1; i >= 0; i--) {
            right[i] = i < n - 1 && ratings[i] > ratings[i + 1] ? right[i + 1] + 1 : 1;
        }

        long total = 0;
        for (int i = 0; i < n; i++) {
            total += Math.max(left[i], right[i]);
        }
        return total;
    }

    /**
     * Tries every handout in {@code {1..n}^n} and keeps the cheapest legal one. No minimal solution
     * gives any child more than {@code n} candies, so the search is complete.
     */
    private static long bruteForce(int[] ratings) {
        int n = ratings.length;
        if (n == 0) return 0;

        int[] handout = new int[n];
        Arrays.fill(handout, 1);
        long best = Long.MAX_VALUE;

        while (true) {
            if (isLegal(ratings, handout)) {
                long total = 0;
                for (int candy : handout) total += candy;
                best = Math.min(best, total);
            }

            int i = 0;                       // odometer over {1..n}^n
            while (i < n && handout[i] == n) handout[i++] = 1;
            if (i == n) return best;
            handout[i]++;
        }
    }

    /** Every child gets at least one, and outscores any immediate neighbour it outranks. */
    private static boolean isLegal(int[] ratings, int[] handout) {
        for (int i = 0; i < ratings.length; i++) {
            if (handout[i] < 1) return false;
            if (i > 0 && ratings[i] > ratings[i - 1] && handout[i] <= handout[i - 1]) return false;
            if (i < ratings.length - 1 && ratings[i] > ratings[i + 1] && handout[i] <= handout[i + 1]) return false;
        }
        return true;
    }

    /** Every line of {@code length} children drawn from {@code distinct} ratings. */
    private static List<int[]> allLines(int length, int distinct) {
        List<int[]> lines = new ArrayList<>();
        int[] line = new int[length];

        while (true) {
            lines.add(line.clone());

            int i = 0;
            while (i < length && line[i] == distinct - 1) line[i++] = 0;
            if (i == length) return lines;
            line[i]++;
        }
    }

    /** One line per rise/level/fall pattern: every step moves the rating by +1, 0 or -1. */
    private static List<int[]> allShapes(int length) {
        List<int[]> shapes = new ArrayList<>();
        int steps = length - 1;
        int[] step = new int[steps];

        while (true) {
            int[] ratings = new int[length];
            for (int i = 1; i < length; i++) ratings[i] = ratings[i - 1] + step[i - 1] - 1;
            shapes.add(ratings);

            int i = 0;
            while (i < steps && step[i] == 2) step[i++] = 0;
            if (i == steps) return shapes;
            step[i]++;
        }
    }

    /** A line that drifts by at most one per step, so monotone runs are long. */
    private static int[] randomWalk(Random random, int length) {
        int[] ratings = new int[length];
        int rating = 0;

        for (int i = 0; i < length; i++) {
            ratings[i] = rating;
            rating += random.nextInt(3) - 1;   // -1, 0 or +1
        }
        return ratings;
    }

    private static List<Integer> boxed(int[] ratings) {
        return Arrays.stream(ratings).boxed().toList();
    }
}

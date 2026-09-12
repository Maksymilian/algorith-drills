package array;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.ObjIntConsumer;
import java.util.stream.Stream;

import static array.LeftRotation.rotLeft;
import static array.LeftRotation.rotateLeftInPlace;
import static array.LeftRotation.rotateLeftInPlaceByCycles;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class LeftRotationTest {

    static Stream<Arguments> knownCases() {
        return Stream.of(
                Arguments.of("sample case", new int[]{1, 2, 3, 4, 5}, 4, new int[]{5, 1, 2, 3, 4}),
                Arguments.of("problem example", new int[]{1, 2, 3, 4, 5}, 2, new int[]{3, 4, 5, 1, 2}),
                Arguments.of("empty array", new int[]{}, 3, new int[]{}),
                Arguments.of("single element", new int[]{7}, 1, new int[]{7}),
                Arguments.of("d = 0 is the identity", new int[]{1, 2, 3}, 0, new int[]{1, 2, 3}),
                Arguments.of("d = n is the identity", new int[]{1, 2, 3}, 3, new int[]{1, 2, 3}),
                Arguments.of("d = n - 1 moves one back", new int[]{1, 2, 3}, 2, new int[]{3, 1, 2}),
                Arguments.of("repeated values", new int[]{4, 4, 9, 4}, 2, new int[]{9, 4, 4, 4}),

                // gcd(n, d) > 1: the cycle walk needs more than one pass here, and only here.
                Arguments.of("n=6 d=4, gcd 2", new int[]{1, 2, 3, 4, 5, 6}, 4, new int[]{5, 6, 1, 2, 3, 4}),
                Arguments.of("n=6 d=3, gcd 3", new int[]{1, 2, 3, 4, 5, 6}, 3, new int[]{4, 5, 6, 1, 2, 3}),
                Arguments.of("n=6 d=2, gcd 2", new int[]{1, 2, 3, 4, 5, 6}, 2, new int[]{3, 4, 5, 6, 1, 2}),
                Arguments.of("n=8 d=4, gcd 4", new int[]{1, 2, 3, 4, 5, 6, 7, 8}, 4, new int[]{5, 6, 7, 8, 1, 2, 3, 4}),
                Arguments.of("n=8 d=6, gcd 2", new int[]{1, 2, 3, 4, 5, 6, 7, 8}, 6, new int[]{7, 8, 1, 2, 3, 4, 5, 6}),
                Arguments.of("n=12 d=8, gcd 4", new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, 8,
                        new int[]{9, 10, 11, 12, 1, 2, 3, 4, 5, 6, 7, 8}),

                // Outside the problem's 1 <= d <= n, where floorMod does the work.
                Arguments.of("d > n wraps", new int[]{1, 2, 3, 4, 5}, 17, new int[]{3, 4, 5, 1, 2}),
                Arguments.of("negative d rotates right", new int[]{1, 2, 3, 4, 5}, -1, new int[]{5, 1, 2, 3, 4}),
                Arguments.of("negative d beyond n", new int[]{1, 2, 3, 4, 5}, -13, new int[]{3, 4, 5, 1, 2}),
                Arguments.of("Integer.MIN_VALUE", new int[]{1, 2, 3, 4, 5}, Integer.MIN_VALUE, new int[]{3, 4, 5, 1, 2}),
                Arguments.of("Integer.MAX_VALUE", new int[]{1, 2, 3, 4, 5}, Integer.MAX_VALUE, new int[]{3, 4, 5, 1, 2}),
                Arguments.of("extreme values rotate too", new int[]{Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, 1,
                        new int[]{0, Integer.MAX_VALUE, Integer.MIN_VALUE})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("knownCases")
    void rotatesLeft(String name, int[] a, int d, int[] expected) {
        assertArrayEquals(expected, rotLeft(a, d));
        assertArrayEquals(expected, inPlace(LeftRotation::rotateLeftInPlace, a, d));
        assertArrayEquals(expected, inPlace(LeftRotation::rotateLeftInPlaceByCycles, a, d));
    }

    @Test
    void doesNotModifyInput() {
        int[] a = {1, 2, 3, 4, 5};
        rotLeft(a, 4);
        assertEquals("[1, 2, 3, 4, 5]", Arrays.toString(a));
    }

    @Test
    void returnsAFreshArrayEvenWhenNothingMoves() {
        int[] a = {1, 2, 3};
        int[] rotated = rotLeft(a, 0);

        rotated[0] = 99;
        assertEquals(1, a[0]);   // the "identity" rotation must not alias the input
    }

    @Test
    void rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> rotLeft(null, 1));
        assertThrows(IllegalArgumentException.class, () -> rotateLeftInPlace(null, 1));
        assertThrows(IllegalArgumentException.class, () -> rotateLeftInPlaceByCycles(null, 1));
    }

    // --- cross-checks against independent oracles -----------------------------------------------

    /**
     * Every {@code (n, d)} pair a small array can have, against the naive repeated-rotation
     * definition. This is the test that pins the cycle walk: it fails for any {@code n} and
     * {@code d} sharing a factor if the walk is restarted the wrong number of times, and those
     * pairs are a minority that a handful of samples can easily miss.
     */
    @Test
    void matchesRepeatedSingleRotationsForEveryPairUpToFortyElements() {
        for (int n = 0; n <= 40; n++) {
            int[] a = new Random(20260912L + n).ints(n, -1000, 1001).toArray();

            for (int d = -2 * n - 3; d <= 2 * n + 3; d++) {
                int[] expected = rotateOneStepAtATime(a, d);
                String where = "n=" + n + " d=" + d;

                assertArrayEquals(expected, rotLeft(a, d), where);
                assertArrayEquals(expected, inPlace(LeftRotation::rotateLeftInPlace, a, d), where);
                assertArrayEquals(expected, inPlace(LeftRotation::rotateLeftInPlaceByCycles, a, d), where);
            }
        }
    }

    /** The JDK's own rotation, which is the same permutation with the opposite sign convention. */
    @Test
    void matchesCollectionsRotateOnRandomArrays() {
        Random random = new Random(20260912L);

        for (int trial = 0; trial < 500; trial++) {
            int[] a = random.ints(random.nextInt(60), -1000, 1001).toArray();
            int d = random.nextInt(400) - 200;

            assertArrayEquals(rotateViaCollections(a, d), rotLeft(a, d), "d=" + d + " " + Arrays.toString(a));
        }
    }

    @Test
    void rotatingBackByTheComplementRestoresTheInput() {
        Random random = new Random(7L);

        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + random.nextInt(60);
            int[] a = random.ints(n, -1000, 1001).toArray();
            int d = random.nextInt(n);

            int[] thereAndBack = a.clone();
            rotateLeftInPlace(thereAndBack, d);
            rotateLeftInPlace(thereAndBack, n - d);
            assertArrayEquals(a, thereAndBack);

            rotateLeftInPlaceByCycles(thereAndBack, d);
            rotateLeftInPlaceByCycles(thereAndBack, -d);
            assertArrayEquals(a, thereAndBack);
        }
    }

    // --- large arrays ---------------------------------------------------------------------------

    /**
     * 10 million elements. The array is {@code a[i] = i}, so the whole rotation can be checked
     * against the closed form {@code rotated[i] == (i + d) mod n} without building a second array -
     * which also makes the check independent of every strategy under test.
     * <p>
     * {@code d} shares a large factor with {@code n} on purpose, so the cycle walk has to run
     * 2 500 000 separate cycles of 4 elements rather than one long one - the structural case that
     * a coprime sample would never reach.
     */
    @Test
    void rotatesTenMillionElements() {
        int n = 10_000_000;
        int d = 7_500_000;                   // gcd(n, d) = 2 500 000
        int[] a = new int[n];
        Arrays.setAll(a, i -> i);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            assertIsRotationOfIdentity(rotLeft(a, d), d);
            assertIsRotationOfIdentity(inPlace(LeftRotation::rotateLeftInPlace, a, d), d);
            assertIsRotationOfIdentity(inPlace(LeftRotation::rotateLeftInPlaceByCycles, a, d), d);
        });
    }

    /** The same size with {@code n} and {@code d} coprime, so the cycle walk is one long cycle. */
    @Test
    void rotatesTenMillionElementsInASingleCycle() {
        int n = 10_000_001;                  // odd, and coprime with d below
        int d = 5_000_000;
        int[] a = new int[n];
        Arrays.setAll(a, i -> i);

        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertIsRotationOfIdentity(inPlace(LeftRotation::rotateLeftInPlaceByCycles, a, d), d));
    }

    private static void assertIsRotationOfIdentity(int[] rotated, int d) {
        int n = rotated.length;
        for (int i = 0; i < n; i++) {
            if (rotated[i] != (i + d) % n) {
                assertEquals((i + d) % n, rotated[i], "at index " + i);
            }
        }
    }

    // --- reference implementations ---------------------------------------------------------------

    /** Applies a void in-place rotation to a copy of {@code a} and returns the copy. */
    private static int[] inPlace(ObjIntConsumer<int[]> rotation, int[] a, int d) {
        int[] copy = a.clone();
        rotation.accept(copy, d);
        return copy;
    }

    /** The problem read literally: {@code d} rotations of one position each, O(n*d). */
    private static int[] rotateOneStepAtATime(int[] a, int d) {
        int[] rotated = a.clone();
        int n = rotated.length;
        if (n == 0) return rotated;

        for (int step = 0, steps = normalize(d, n); step < steps; step++) {
            int first = rotated[0];
            System.arraycopy(rotated, 1, rotated, 0, n - 1);
            rotated[n - 1] = first;
        }
        return rotated;
    }

    /** {@link Collections#rotate} - note the sign: a positive distance there rotates <i>right</i>. */
    private static int[] rotateViaCollections(int[] a, int d) {
        List<Integer> list = new ArrayList<>(a.length);
        for (int value : a) {
            list.add(value);
        }

        if (!list.isEmpty()) {
            Collections.rotate(list, -normalize(d, a.length));   // normalized first: -MIN_VALUE overflows
        }

        int[] rotated = new int[list.size()];
        for (int i = 0; i < rotated.length; i++) {
            rotated[i] = list.get(i);
        }
        return rotated;
    }

    /** {@code floorMod} written out, so the oracles do not share the implementation's normalization. */
    private static int normalize(int d, int n) {
        return ((d % n) + n) % n;
    }
}

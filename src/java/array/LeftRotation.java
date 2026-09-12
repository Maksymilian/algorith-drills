package array;

/**
 * HackerRank "Arrays: Left Rotation": shift every element of {@code a} left by {@code d}
 * positions, wrapping the first {@code d} elements around to the back.
 * <p>
 * The problem is stated as a repeated operation - "perform {@code d} left rotations" - and taking
 * that literally is the trap: rotating one step at a time costs O(n*d), which at the problem's
 * bounds ({@code n = d = 10}<sup>5</sup>) is 10<sup>10</sup> element moves. But the composition of
 * {@code d} single rotations is not a loop, it is one relabelling of the indices:
 * <pre>
 *     rotated[i] = a[(i + d) mod n]
 * </pre>
 * Every method here is that identity, evaluated in a different order; none of them rotates twice.
 * The formula is also the reason a rotation can never be more than O(n): each element has exactly
 * one destination, known in closed form, so no element needs to be touched twice.
 * <p>
 * <b>Three evaluation orders</b>, all O(n) time:
 * <table border="1">
 *   <caption>What each method costs</caption>
 *   <tr><th>Method</th><th>Extra space</th><th>Element writes</th><th>Access pattern</th></tr>
 *   <tr><td>{@link #rotLeft(int[], int)}</td><td>O(n)</td><td>n</td><td>two sequential blocks</td></tr>
 *   <tr><td>{@link #rotateLeftInPlace(int[], int)}</td><td>O(1)</td><td>~2n</td><td>sequential</td></tr>
 *   <tr><td>{@link #rotateLeftInPlaceByCycles(int[], int)}</td><td>O(1)</td><td>n</td><td>strided by d</td></tr>
 * </table>
 * The last two trade the same currency in opposite directions: the cycle walk writes every element
 * exactly once but jumps {@code d} slots at a time, while the three reversals write everything
 * twice and never jump. Which one wins is a memory-system question, not an arithmetic one - see
 * {@code docs/array/left-rotation.md}.
 * <p>
 * <b>Beyond the stated bounds.</b> The problem guarantees {@code 1 <= d <= n}. Every method here
 * normalizes with {@link Math#floorMod(int, int)} instead, so {@code d = 0} and {@code d = n} are
 * the identity, {@code d > n} wraps, and a <b>negative {@code d} rotates right</b> - a right
 * rotation by {@code k} being a left rotation by {@code n - k}. Only an empty array is special,
 * because there is no {@code n} to reduce modulo.
 */
public class LeftRotation {

    /**
     * The HackerRank signature: returns {@code a} rotated left by {@code d}, leaving {@code a}
     * untouched.
     * <p>
     * Evaluates {@code rotated[i] = a[(i + d) mod n]} as the two contiguous blocks that index map
     * actually describes - {@code a[d..n)} lands at the front, {@code a[0..d)} follows it - so the
     * work is two {@link System#arraycopy} calls rather than n modulo operations. Time O(n), extra
     * space O(n) for the result.
     *
     * @param a the array to rotate, may be empty
     * @param d how far to rotate left; any value, see the class note on normalization
     * @return a new array holding the rotation
     * @throws IllegalArgumentException if {@code a} is null
     */
    public static int[] rotLeft(int[] a, int d) {
        if (a == null) throw new IllegalArgumentException("a must not be null");

        int n = a.length;
        if (n == 0) return new int[0];
        int shift = Math.floorMod(d, n);

        int[] rotated = new int[n];
        System.arraycopy(a, shift, rotated, 0, n - shift);   // the tail moves to the front
        System.arraycopy(a, 0, rotated, n - shift, shift);   // the head wraps around behind it
        return rotated;
    }

    /**
     * Rotates {@code a} left by {@code d} in place, by reversing three ranges.
     * <p>
     * A left rotation splits the array into a head {@code a[0..d)} and a tail {@code a[d..n)} and
     * emits them as {@code tail ++ head}. Reversal turns that concatenation swap into three linear
     * passes, because reversing a reversed block restores its order:
     * <pre>
     *     reverse(head) reverse(tail)  ->  head' ++ tail'
     *     reverse(whole)               ->  tail  ++ head
     * </pre>
     * Time O(n) in ~n swaps, so about 2n element writes, but every pass walks memory sequentially.
     * Space O(1).
     *
     * @param a the array to rotate in place, may be empty
     * @param d how far to rotate left; any value, see the class note on normalization
     * @throws IllegalArgumentException if {@code a} is null
     */
    public static void rotateLeftInPlace(int[] a, int d) {
        if (a == null) throw new IllegalArgumentException("a must not be null");

        int n = a.length;
        if (n == 0) return;
        int shift = Math.floorMod(d, n);
        if (shift == 0) return;

        reverse(a, 0, shift);
        reverse(a, shift, n);
        reverse(a, 0, n);
    }

    /**
     * Rotates {@code a} left by {@code d} in place, by following the permutation's cycles.
     * <p>
     * The index map {@code i -> (i + d) mod n} is a permutation, so the array decomposes into
     * disjoint cycles and each element can be carried straight to its destination: hold one
     * element, pull its replacement in from {@code d} slots along, repeat until the walk returns to
     * where it started, then drop the held value into the hole it left.
     * <p>
     * There are exactly {@code gcd(n, d)} such cycles, each of length {@code n / gcd(n, d)} - the
     * step {@code +d} first returns to its start after {@code lcm(n, d)} steps - which is why one
     * walk is not always enough and the loop restarts at {@code 0, 1, ... gcd(n, d) - 1}. With
     * {@code n} and {@code d} coprime a single cycle covers the whole array; that is the case the
     * HackerRank sample happens to be, and the case a broken implementation still passes.
     * <p>
     * Time O(n) in exactly n writes - the theoretical minimum, since every element moves - and
     * space O(1). That minimum is not what it costs: the walk strides by {@code d}, so on an array
     * larger than cache it is anywhere from as fast as
     * {@link #rotateLeftInPlace(int[], int)} to <b>56x slower</b> on the very same array, depending
     * on nothing but the value of {@code d}. Powers of two are the worst of it. Measurements are in
     * {@code docs/array/left-rotation.md}; prefer the reversals unless {@code d} is known.
     *
     * @param a the array to rotate in place, may be empty
     * @param d how far to rotate left; any value, see the class note on normalization
     * @throws IllegalArgumentException if {@code a} is null
     */
    public static void rotateLeftInPlaceByCycles(int[] a, int d) {
        if (a == null) throw new IllegalArgumentException("a must not be null");

        int n = a.length;
        if (n == 0) return;
        int shift = Math.floorMod(d, n);
        if (shift == 0) return;

        for (int start = 0, cycles = gcd(n, shift); start < cycles; start++) {
            int held = a[start];
            int hole = start;

            while (true) {
                int source = hole + shift;
                if (source >= n) source -= n;          // one subtraction is enough: shift < n
                if (source == start) break;            // the cycle has closed
                a[hole] = a[source];
                hole = source;
            }
            a[hole] = held;
        }
    }

    /** Reverses {@code a[from..toExclusive)} in place. */
    private static void reverse(int[] a, int from, int toExclusive) {
        for (int lo = from, hi = toExclusive - 1; lo < hi; lo++, hi--) {
            int swap = a[lo];
            a[lo] = a[hi];
            a[hi] = swap;
        }
    }

    /** Euclid, on values already known to be positive. */
    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private LeftRotation() {
    }
}

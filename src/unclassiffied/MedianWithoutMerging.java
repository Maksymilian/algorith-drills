import java.util.Arrays;

public class MedianWithoutMerging {

    public static double findMedian(int[] a, int[] b) {
        // always binary-search the smaller array
        if (a.length > b.length) return findMedian(b, a);

        int m = a.length, n = b.length;
        if (m + n == 0) throw new IllegalArgumentException("both sets are empty");

        int half = (m + n + 1) / 2;
        int lo = 0, hi = m;

        while (lo <= hi) {
            int i = (lo + hi) / 2;   // elements taken from a
            int j = half - i;        // elements taken from b

            int aLeft  = (i > 0) ? a[i - 1] : Integer.MIN_VALUE;
            int aRight = (i < m) ? a[i]     : Integer.MAX_VALUE;
            int bLeft  = (j > 0) ? b[j - 1] : Integer.MIN_VALUE;
            int bRight = (j < n) ? b[j]     : Integer.MAX_VALUE;

            if (aLeft <= bRight && bLeft <= aRight) {
                int maxLeft = Math.max(aLeft, bLeft);
                if (((m + n) % 2) == 1) return maxLeft;
                int minRight = Math.min(aRight, bRight);
                return (maxLeft + (double) minRight) / 2.0;
            } else if (aLeft > bRight) {
                hi = i - 1;          // took too many from a
            } else {
                lo = i + 1;          // took too few from a
            }
        }
        throw new IllegalArgumentException("input arrays are not sorted");
    }

    // ---------- tests ----------

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, double expected, int[] a, int[] b) {
        try {
            double actual = findMedian(a, b);
            if (Math.abs(actual - expected) < 1e-9) {
                passed++;
                System.out.printf("PASS  %-28s -> %s%n", name, actual);
            } else {
                failed++;
                System.out.printf("FAIL  %-28s -> %s (expected %s)%n", name, actual, expected);
            }
        } catch (RuntimeException e) {
            failed++;
            System.out.printf("FAIL  %-28s -> threw %s: %s%n",
                    name, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private static void checkThrows(String name, int[] a, int[] b) {
        try {
            double actual = findMedian(a, b);
            failed++;
            System.out.printf("FAIL  %-28s -> %s (expected an exception)%n", name, actual);
        } catch (IllegalArgumentException e) {
            passed++;
            System.out.printf("PASS  %-28s -> threw: %s%n", name, e.getMessage());
        }
    }

    static void main(String[] args) {
        // odd / even totals
        check("odd total",            2.0,  new int[]{1, 3},          new int[]{2});
        check("even total",           2.5,  new int[]{1, 2},          new int[]{3, 4});

        // one side empty
        check("empty + single",       1.0,  new int[]{},              new int[]{1});
        check("empty + even",         1.5,  new int[]{},              new int[]{1, 2});
        check("empty + odd",          2.0,  new int[]{},              new int[]{1, 2, 3});

        // cut lands at an array boundary (the sentinel cases)
        check("disjoint, a below b",  3.0,  new int[]{1, 2},          new int[]{3, 4, 5});
        check("disjoint, a above b", 10.0,  new int[]{10, 20, 30},    new int[]{1, 2});

        // very lopsided sizes
        check("lopsided sizes",       5.5,  new int[]{1,2,3,4,5,6,7,8,9}, new int[]{10});
        check("single vs single",     1.5,  new int[]{1},             new int[]{2});

        // duplicates
        check("all identical",        1.0,  new int[]{1, 1, 1},       new int[]{1, 1, 1});
        check("duplicates spanning",  2.0,  new int[]{1, 2, 2, 3},    new int[]{2, 2});

        // negatives and extremes
        check("negatives",           -2.0,  new int[]{-5, -3, -1},    new int[]{-2, 0});
        check("mixed signs",          0.0,  new int[]{-2, -1},        new int[]{1, 2});
        check("int extremes",  Integer.MAX_VALUE,
                new int[]{Integer.MAX_VALUE}, new int[]{Integer.MAX_VALUE});
        check("overflow-prone even",
                ((double) Integer.MIN_VALUE + Integer.MAX_VALUE) / 2.0,
                new int[]{Integer.MIN_VALUE}, new int[]{Integer.MAX_VALUE});

        // argument order must not matter
        check("swapped args",         3.0,  new int[]{3, 4, 5},       new int[]{1, 2});

        // invalid input
        checkThrows("both empty",            new int[]{},             new int[]{});

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
    }

}

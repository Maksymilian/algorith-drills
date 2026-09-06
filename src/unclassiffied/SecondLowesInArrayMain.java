import java.util.Arrays;

public class SecondLowesInArrayMain {

    /**
     * Returns the second lowest *distinct* value in the array.
     * Single pass, O(n) time, O(1) space.
     *
     * @throws IllegalArgumentException if the array is null, holds fewer than
     *                                  two elements, or has no distinct runner-up.
     */
    public static int secondLowest(int[] nums) {
        if (nums == null || nums.length < 2) {
            throw new IllegalArgumentException("Need at least two elements");
        }

        long lowest = Long.MAX_VALUE;
        long second = Long.MAX_VALUE;

        for (int n : nums) {
            if (n < lowest) {
                second = lowest;
                lowest = n;
            } else if (n > lowest && n < second) {
                second = n;
            }
        }

        if (second == Long.MAX_VALUE) {
            throw new IllegalArgumentException("All elements are equal");
        }
        return (int) second;
    }

    /**
     * Returns the second lowest *distinct* value in the array.
     * Single pass, O(n) time, O(1) space.
     *
     * @throws IllegalArgumentException if the array is null, holds fewer than
     *                                  two elements, or has no distinct runner-up.
     */
    public static int mySecondLowest(int[] nums) {
        if (nums == null || nums.length < 2) {
            throw new IllegalArgumentException("Need at least two elements");
        }
        int lowestIndex = 0;
        int secondLowestIndex = 0;

        for (int i = 1; i < nums.length; i++) {
            if (nums[i] < nums[lowestIndex]) {
                secondLowestIndex = lowestIndex;
                lowestIndex = i;
            } else if(nums[i] > nums[lowestIndex] && nums[i] < nums[secondLowestIndex]) {
                secondLowestIndex = i;
            }
        }
        return nums[secondLowestIndex];
    }

    /**
     * Variant that treats duplicates as separate elements, i.e. the second
     * element of the sorted array. For [1, 1, 2] this returns 1, whereas
     * {@link #secondLowest(int[])} returns 2.
     */
    public static int secondLowestWithDuplicates(int[] nums) {
        if (nums == null || nums.length < 2) {
            throw new IllegalArgumentException("Need at least two elements");
        }

        long lowest = Long.MAX_VALUE;
        long second = Long.MAX_VALUE;

        for (int n : nums) {
            if (n < lowest) {
                second = lowest;
                lowest = n;
            } else if (n < second) {
                second = n;
            }
        }
        return (int) second;
    }

    static void main() {
        int[][] samples = {
                {5, 3, 9, 1, 7},
                {4, 4, 2, 2, 8},
                {-3, -1, -7, -7},
                {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE},
                {2, 1},
        };

        for (int[] sample : samples) {
            System.out.printf("%-42s -> distinct: %-12d with duplicates: %d%n",
                    Arrays.toString(sample),
                    secondLowest(sample),
                    secondLowestWithDuplicates(sample));
        }
    }
}

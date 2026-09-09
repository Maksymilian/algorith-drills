package dynamic;

/**
 * HackerRank "Abbreviation": can {@code a} be turned into {@code b} by capitalizing some of its
 * lowercase letters and deleting every lowercase letter left over?
 * <p>
 * The asymmetry between the two kinds of letter is the whole problem. A lowercase letter has a
 * choice - capitalize it and spend it on {@code b}, or drop it - while an <b>uppercase letter has
 * none</b>: there is no operation that removes one, so every uppercase letter of {@code a} must be
 * matched, in order, or the answer is no.
 * <p>
 * That is also why greedy fails. Matching {@code a = "aA"} against {@code b = "A"} by grabbing the
 * first letter that fits capitalizes the {@code 'a'} and then strands the {@code 'A'}; the answer is
 * yes, by dropping the {@code 'a'} and matching the {@code 'A'}. Whether spending a lowercase letter
 * pays off depends on the entire rest of the string, so both choices have to stay alive - which is
 * what the table does.
 * <p>
 * <b>State</b> {@code reachable[i][j]} - the first {@code i} letters of {@code a} can produce the
 * first {@code j} letters of {@code b}. <b>Transition</b> on {@code c = a[i-1]}, {@code d = b[j-1]}:
 * <pre>
 *     c lowercase:  reachable[i][j] = reachable[i-1][j]                      // drop c
 *                                   | reachable[i-1][j-1] &amp;&amp; upper(c) == d   // capitalize c
 *     c uppercase:  reachable[i][j] = reachable[i-1][j-1] &amp;&amp; c == d          // c must be spent
 * </pre>
 * <b>Base case</b> {@code reachable[i][0]} is true only while {@code a[0..i-1]} is all lowercase
 * (nothing to match means everything must be droppable), and {@code reachable[0][j>0]} is false.
 * <b>Answer</b> {@code reachable[n][m]}.
 * <p>
 * Time O(n*m), space O(m) - the transition only ever reads row {@code i - 1}, so one row is enough.
 * <p>
 * {@code b} is expected to be uppercase, per the problem's constraints. A lowercase letter in
 * {@code b} is not rejected, it simply never matches: capitalizing or keeping a letter of {@code a}
 * always yields uppercase, so such a query correctly answers no.
 */
public class Abbreviation {

    /**
     * The HackerRank signature: {@code "YES"} if {@code a} can be made equal to {@code b},
     * {@code "NO"} otherwise.
     *
     * @throws IllegalArgumentException if either string is null
     */
    public static String abbreviation(String a, String b) {
        return canAbbreviate(a, b) ? "YES" : "NO";
    }

    /**
     * Whether {@code a} can be made equal to {@code b} by capitalizing some of its lowercase letters
     * and deleting the rest. Time O(n*m), space O(m).
     *
     * @param a the string to modify, may be empty
     * @param b the string to match, may be empty
     * @return true if the conversion is possible
     * @throws IllegalArgumentException if either string is null
     */
    public static boolean canAbbreviate(String a, String b) {
        if (a == null || b == null) throw new IllegalArgumentException("a and b must not be null");

        int n = a.length();
        int m = b.length();
        if (n < m) return false;   // every letter of b has to be spent from its own letter of a

        // One row of the table: reachable[j] == "the prefix of a seen so far can produce b[0..j-1]".
        boolean[] reachable = new boolean[m + 1];
        reachable[0] = true;       // the empty prefix produces the empty prefix

        for (int i = 1; i <= n; i++) {
            char c = a.charAt(i - 1);
            boolean droppable = Character.isLowerCase(c);
            char spent = Character.toUpperCase(c);

            // Descending, so reachable[j] and reachable[j - 1] are still row i - 1 when read.
            for (int j = m; j >= 1; j--) {
                boolean spendOnMatch = spent == b.charAt(j - 1) && reachable[j - 1];
                reachable[j] = spendOnMatch || (droppable && reachable[j]);
            }
            reachable[0] &= droppable;   // an uppercase letter cannot be left unspent
        }

        return reachable[m];
    }

    private Abbreviation() {
    }
}

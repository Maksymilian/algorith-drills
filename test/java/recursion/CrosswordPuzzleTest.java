package recursion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static recursion.CrosswordPuzzle.crosswordPuzzle;

class CrosswordPuzzleTest {

    private static final String[] STATEMENT_GRID = {
            "++++++++++", "+------+++", "+++-++++++", "+++-++++++", "+++-----++",
            "+++-++-+++", "++++++-+++", "++++++-+++", "++++++-+++", "++++++++++"};
    private static final String[] STATEMENT_SOLVED = {
            "++++++++++", "+POLAND+++", "+++H++++++", "+++A++++++", "+++SPAIN++",
            "+++A++N+++", "++++++D+++", "++++++I+++", "++++++A+++", "++++++++++"};

    private static final String[] SAMPLE_0_GRID = {
            "+-++++++++", "+-++++++++", "+-++++++++", "+-----++++", "+-+++-++++",
            "+-+++-++++", "+++++-++++", "++------++", "+++++-++++", "+++++-++++"};
    private static final String[] SAMPLE_0_SOLVED = {
            "+L++++++++", "+O++++++++", "+N++++++++", "+DELHI++++", "+O+++C++++",
            "+N+++E++++", "+++++L++++", "++ANKARA++", "+++++N++++", "+++++D++++"};

    private static final String[] SAMPLE_1_GRID = {
            "+-++++++++", "+-++++++++", "+-------++", "+-++++++++", "+-++++++++",
            "+------+++", "+-+++-++++", "+++++-++++", "+++++-++++", "++++++++++"};
    private static final String[] SAMPLE_1_SOLVED = {
            "+E++++++++", "+N++++++++", "+GWALIOR++", "+L++++++++", "+A++++++++",
            "+NORWAY+++", "+D+++G++++", "+++++R++++", "+++++A++++", "++++++++++"};

    private static final String[] SAMPLE_2_GRID = {
            "++++++-+++", "++------++", "++++++-+++", "++++++-+++", "+++------+",
            "++++++-+-+", "++++++-+-+", "++++++++-+", "++++++++-+", "++++++++-+"};
    private static final String[] SAMPLE_2_SOLVED = {
            "++++++I+++", "++MEXICO++", "++++++E+++", "++++++L+++", "+++PANAMA+",
            "++++++N+L+", "++++++D+M+", "++++++++A+", "++++++++T+", "++++++++Y+"};

    private static final String[] WORD_SQUARE_GRID = {"---", "---", "---"};

    /** Two slots crossing at one cell: the wrong first choice fits, then strands the other word. */
    private static final String[] BACKTRACK_GRID = {"---", "++-", "++-"};
    private static final String[] BACKTRACK_SOLVED = {"CAT", "++O", "++P"};

    static Stream<Arguments> puzzles() {
        return Stream.of(
                Arguments.of("statement example", STATEMENT_GRID, "POLAND;LHASA;SPAIN;INDIA", STATEMENT_SOLVED),
                Arguments.of("sample 0", SAMPLE_0_GRID, "LONDON;DELHI;ICELAND;ANKARA", SAMPLE_0_SOLVED),
                Arguments.of("sample 1", SAMPLE_1_GRID, "AGRA;NORWAY;ENGLAND;GWALIOR", SAMPLE_1_SOLVED),
                Arguments.of("sample 2", SAMPLE_2_GRID, "ICELAND;MEXICO;PANAMA;ALMATY", SAMPLE_2_SOLVED),
                Arguments.of("forces a backtrack", BACKTRACK_GRID, "TOP;CAT", BACKTRACK_SOLVED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("puzzles")
    void solvesPublishedPuzzles(String name, String[] grid, String words, String[] expected) {
        assertArrayEquals(expected, crosswordPuzzle(grid, words));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("puzzles")
    void hasExactlyOneSolution(String name, String[] grid, String words, String[] expected) {
        assertEquals(1, countSolutions(grid, words.split(";")), "brute force found a different number");
    }

    /** The answer cannot depend on the order the words arrive in - only backtracking makes that true. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("puzzles")
    void solvesRegardlessOfWordOrder(String name, String[] grid, String words, String[] expected) {
        for (String[] order : permutations(words.split(";"))) {
            assertArrayEquals(expected, crosswordPuzzle(grid, String.join(";", order)), Arrays.toString(order));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("puzzles")
    void leavesTheCallersGridUntouched(String name, String[] grid, String words, String[] expected) {
        String[] copy = grid.clone();
        crosswordPuzzle(grid, words);
        assertArrayEquals(copy, grid);
    }

    /**
     * A 3x3 word square: six slots, and every cell is shared by two of them. Restoring a slot by
     * blanking it, instead of putting back what it displaced, erases a crossing word's letters -
     * and only a grid this dense notices, since the published puzzles are solved correctly either
     * way. Every ordering is tried, because whether the bug shows depends on which word is offered
     * to the retried slot first.
     */
    @Test
    void retryingASlotKeepsTheLettersThatCrossingWordsWrote() {
        String[] words = {"ABA", "CCA", "ACA", "ACC", "CAC", "BAC"};

        for (String[] order : permutations(words)) {
            String list = String.join(";", order);
            assertSolved(WORD_SQUARE_GRID, order, crosswordPuzzle(WORD_SQUARE_GRID, list));
        }
    }

    @Test
    void acceptsTheListFlavouredStub() {
        assertEquals(List.of(SAMPLE_0_SOLVED), crosswordPuzzle(List.of(SAMPLE_0_GRID), "LONDON;DELHI;ICELAND;ANKARA"));
    }

    @Test
    void fillsABlankThatNoRunOfTwoReaches() {
        assertArrayEquals(new String[]{"X++", "+++", "CAT"},
                crosswordPuzzle(new String[]{"-++", "+++", "---"}, "X;CAT"));
    }

    @Test
    void solvesAGridWithNothingToFill() {
        assertArrayEquals(new String[]{"++", "++"}, crosswordPuzzle(new String[]{"++", "++"}, ""));
    }

    // --- refusals --------------------------------------------------------------------------------

    @Test
    void rejectsAWordCountThatDoesNotMatchTheSlotCount() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> crosswordPuzzle(new String[]{"---"}, "ABC;DEF"));
        assertTrue(thrown.getMessage().contains("1 slots but 2 words"), thrown.getMessage());
    }

    @Test
    void rejectsAPuzzleThatCannotBeSolved() {
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[]{"---"}, "ABCD"));
        // right lengths, but no crossing letter agrees: CAT/DOG cannot meet at the shared cell
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(BACKTRACK_GRID, "CAT;DOG"));
    }

    @Test
    void rejectsAMalformedGrid() {
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle((String[]) null, "A"));
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[0], "A"));
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[]{"---", "--"}, "ABC"));
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[]{"-x-"}, "ABC"));
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[]{null}, "ABC"));
    }

    @Test
    void rejectsAMalformedWordList() {
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[]{"---"}, null));
        assertThrows(IllegalArgumentException.class, () -> crosswordPuzzle(new String[]{"---", "+-+"}, "ABC;;X"));
    }

    // --- property-based --------------------------------------------------------------------------

    /**
     * Fills each published grid with random letters, reads the words back out of it - so a solution
     * exists by construction - then shuffles the list and checks the solver produces a grid that is
     * valid on its own terms. Random letters can make a puzzle ambiguous, so this checks the
     * properties of the answer rather than one expected grid.
     */
    @Test
    void solvesRandomlyLetteredPuzzles() {
        Random random = new Random(20260908L);
        String[][] grids = {STATEMENT_GRID, SAMPLE_0_GRID, SAMPLE_1_GRID, SAMPLE_2_GRID};

        for (String[] grid : grids) {
            List<int[]> slots = slotsOf(grid);

            for (int trial = 0; trial < 50; trial++) {
                String[] lettered = randomlyLettered(random, grid);
                String[] words = slots.stream().map(slot -> wordAt(lettered, slot)).toArray(String[]::new);
                shuffle(random, words);

                String[] solved = crosswordPuzzle(grid, String.join(";", words));

                assertSolved(grid, words, solved);
            }
        }
    }

    // --- oracles and helpers ---------------------------------------------------------------------

    private static final Pattern RUN_OF_BLANKS = Pattern.compile("-{2,}");

    /** Slots found independently of the solver: runs of two or more dashes, in rows and in columns. */
    private static List<int[]> slotsOf(String[] grid) {
        List<int[]> slots = new ArrayList<>();   // {row, col, length, across}

        for (int row = 0; row < grid.length; row++) {
            Matcher across = RUN_OF_BLANKS.matcher(grid[row]);
            while (across.find()) slots.add(new int[]{row, across.start(), across.end() - across.start(), 1});
        }

        String[] columns = transpose(grid);
        for (int col = 0; col < columns.length; col++) {
            Matcher down = RUN_OF_BLANKS.matcher(columns[col]);
            while (down.find()) slots.add(new int[]{down.start(), col, down.end() - down.start(), 0});
        }

        return slots;
    }

    /** Counts consistent arrangements by trying every permutation of the word list, with no pruning. */
    private static int countSolutions(String[] grid, String[] words) {
        List<int[]> slots = slotsOf(grid);
        if (slots.size() != words.length) return 0;

        int solutions = 0;
        for (String[] arrangement : permutations(words)) {
            if (isConsistent(grid, slots, arrangement)) solutions++;
        }

        return solutions;
    }

    private static boolean isConsistent(String[] grid, List<int[]> slots, String[] arrangement) {
        char[][] filled = Arrays.stream(grid).map(String::toCharArray).toArray(char[][]::new);

        for (int s = 0; s < slots.size(); s++) {
            int[] slot = slots.get(s);
            String word = arrangement[s];
            if (word.length() != slot[2]) return false;

            for (int k = 0; k < word.length(); k++) {
                int row = slot[3] == 1 ? slot[0] : slot[0] + k;
                int col = slot[3] == 1 ? slot[1] + k : slot[1];
                if (filled[row][col] != '-' && filled[row][col] != word.charAt(k)) return false;
                filled[row][col] = word.charAt(k);
            }
        }

        return true;
    }

    private static void assertSolved(String[] empty, String[] words, String[] solved) {
        assertEquals(empty.length, solved.length);

        for (int row = 0; row < empty.length; row++) {
            assertEquals(empty[row].length(), solved[row].length(), "row " + row + " changed width");
            for (int col = 0; col < empty[row].length(); col++) {
                char before = empty[row].charAt(col);
                char after = solved[row].charAt(col);
                if (before == '+') {
                    assertEquals('+', after, "a wall was overwritten at " + row + "," + col);
                } else {
                    assertTrue(Character.isLetter(after), "a blank was left unfilled at " + row + "," + col);
                }
            }
        }

        String[] placed = slotsOf(empty).stream().map(slot -> wordAt(solved, slot)).sorted().toArray(String[]::new);
        assertArrayEquals(Arrays.stream(words).sorted().toArray(String[]::new), placed,
                "the grid does not hold exactly the given words");
    }

    private static String wordAt(String[] grid, int[] slot) {
        StringBuilder word = new StringBuilder(slot[2]);

        for (int k = 0; k < slot[2]; k++) {
            int row = slot[3] == 1 ? slot[0] : slot[0] + k;
            int col = slot[3] == 1 ? slot[1] + k : slot[1];
            word.append(grid[row].charAt(col));
        }

        return word.toString();
    }

    private static String[] randomlyLettered(Random random, String[] grid) {
        return Arrays.stream(grid)
                .map(row -> row.replaceAll("-", "?"))
                .map(row -> {
                    StringBuilder lettered = new StringBuilder(row.length());
                    for (int col = 0; col < row.length(); col++) {
                        lettered.append(row.charAt(col) == '?' ? (char) ('A' + random.nextInt(26)) : row.charAt(col));
                    }
                    return lettered.toString();
                })
                .toArray(String[]::new);
    }

    private static String[] transpose(String[] grid) {
        String[] columns = new String[grid[0].length()];

        for (int col = 0; col < columns.length; col++) {
            StringBuilder column = new StringBuilder(grid.length);
            for (String row : grid) column.append(row.charAt(col));
            columns[col] = column.toString();
        }

        return columns;
    }

    private static List<String[]> permutations(String[] words) {
        List<String[]> permutations = new ArrayList<>();
        permute(words.clone(), 0, permutations);
        return permutations;
    }

    private static void permute(String[] words, int from, List<String[]> into) {
        if (from == words.length) {
            into.add(words.clone());
            return;
        }

        for (int i = from; i < words.length; i++) {
            swap(words, from, i);
            permute(words, from + 1, into);
            swap(words, from, i);
        }
    }

    private static void shuffle(Random random, String[] words) {
        for (int i = words.length - 1; i > 0; i--) swap(words, i, random.nextInt(i + 1));
    }

    private static void swap(String[] words, int i, int j) {
        String held = words[i];
        words[i] = words[j];
        words[j] = held;
    }
}

package recursion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * HackerRank "Crossword Puzzle": fill a grid of {@code '+'} walls and {@code '-'} blanks with a
 * semicolon-separated word list, one word per slot.
 * <p>
 * The grid decides nothing on its own - placing a word rewrites the letters every crossing slot has
 * to agree with, so no slot can be filled by looking at it alone. That is what makes this a search
 * rather than a scan, and the search is the three-step backtracking pattern:
 * <ol>
 *   <li><b>choose</b> - take a word that fits the slot's length and agrees with the letters already
 *       crossing it;</li>
 *   <li><b>explore</b> - recurse on the next slot, with that word marked as spent;</li>
 *   <li><b>un-choose</b> - on failure put back exactly the characters the word overwrote, which are
 *       not all {@code '-'}: a crossing word may already have written letters into this slot.</li>
 * </ol>
 * Restoring the overwritten characters rather than blanking the slot is the one detail that is easy
 * to get wrong, and it is what keeps the grid a faithful record of the current partial assignment.
 * <p>
 * <b>Cost.</b> An assignment of {@code w} words to {@code w} slots is a permutation, so the search
 * tree holds at most {@code w!} leaves - but two cheap tests prune nearly all of it: a word must
 * match the slot's length, and it must agree with every letter already crossing that slot. At the
 * problem's bounds ({@code w <= 10}) the pruned search finishes in microseconds.
 * <p>
 * Unlike the dynamic-programming drills, memoizing would buy nothing here: two different partial
 * assignments are two different states, and they do not overlap. All the leverage is in pruning.
 * <p>
 * The first solution found is returned; HackerRank guarantees the puzzle has exactly one. The grid
 * need not be 10x10 - any rectangle works, and a blank that no run of two reaches is treated as a
 * one-letter slot.
 */
public class CrosswordPuzzle {

    private static final char BLANK = '-';
    private static final char WALL = '+';

    /**
     * Solves the puzzle and returns the filled grid, one string per row.
     *
     * @param crossword the empty grid, {@code '+'} for walls and {@code '-'} for blanks
     * @param words     the words to fit, separated by {@code ';'}
     * @return the filled grid
     * @throws IllegalArgumentException if the grid is malformed, if the number of words does not
     *                                  match the number of slots, or if no arrangement fits
     */
    public static String[] crosswordPuzzle(String[] crossword, String words) {
        char[][] grid = toGrid(crossword);
        String[] wordList = splitWords(words);
        List<Slot> slots = findSlots(grid);

        if (slots.size() != wordList.length) {
            throw new IllegalArgumentException(
                    "the grid has " + slots.size() + " slots but " + wordList.length + " words were given");
        }
        if (!fill(grid, slots, 0, wordList, new boolean[wordList.length])) {
            throw new IllegalArgumentException("no arrangement of the words fits the grid");
        }

        return Arrays.stream(grid).map(String::new).toArray(String[]::new);
    }

    /** {@link #crosswordPuzzle(String[], String)} for the list-flavoured HackerRank stub. */
    public static List<String> crosswordPuzzle(List<String> crossword, String words) {
        if (crossword == null) throw new IllegalArgumentException("crossword must not be null");
        return List.of(crosswordPuzzle(crossword.toArray(new String[0]), words));
    }

    /**
     * Fills {@code slots} from {@code index} on, and reports whether the whole tail could be filled.
     * Every slot filled means every word spent, since the counts match.
     */
    private static boolean fill(char[][] grid, List<Slot> slots, int index, String[] words, boolean[] used) {
        if (index == slots.size()) return true;

        Slot slot = slots.get(index);

        for (int w = 0; w < words.length; w++) {
            if (used[w] || !fits(grid, slot, words[w])) continue;

            char[] overwritten = write(grid, slot, words[w]);   // choose
            used[w] = true;

            if (fill(grid, slots, index + 1, words, used)) return true;   // explore

            used[w] = false;                                    // un-choose
            restore(grid, slot, overwritten);
        }

        return false;
    }

    /** Whether {@code word} has the slot's length and agrees with every letter already crossing it. */
    private static boolean fits(char[][] grid, Slot slot, String word) {
        if (word.length() != slot.length()) return false;

        for (int k = 0; k < slot.length(); k++) {
            char present = grid[slot.rowAt(k)][slot.colAt(k)];
            if (present != BLANK && present != word.charAt(k)) return false;
        }

        return true;
    }

    /** Writes {@code word} into the slot and returns the characters it displaced. */
    private static char[] write(char[][] grid, Slot slot, String word) {
        char[] displaced = new char[slot.length()];

        for (int k = 0; k < slot.length(); k++) {
            int row = slot.rowAt(k);
            int col = slot.colAt(k);
            displaced[k] = grid[row][col];
            grid[row][col] = word.charAt(k);
        }

        return displaced;
    }

    /** Puts back exactly what {@link #write} displaced - letters from crossing words included. */
    private static void restore(char[][] grid, Slot slot, char[] displaced) {
        for (int k = 0; k < slot.length(); k++) {
            grid[slot.rowAt(k)][slot.colAt(k)] = displaced[k];
        }
    }

    /** Every maximal run of two or more blanks, across and down, plus any blank neither reaches. */
    private static List<Slot> findSlots(char[][] grid) {
        int rows = grid.length;
        int cols = grid[0].length;
        List<Slot> slots = new ArrayList<>();
        boolean[][] covered = new boolean[rows][cols];

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (grid[row][col] != BLANK) continue;

                boolean startsAcross = col == 0 || grid[row][col - 1] != BLANK;
                if (startsAcross && col + 1 < cols && grid[row][col + 1] == BLANK) {
                    add(slots, covered, new Slot(row, col, runLength(grid, row, col, true), true));
                }

                boolean startsDown = row == 0 || grid[row - 1][col] != BLANK;
                if (startsDown && row + 1 < rows && grid[row + 1][col] == BLANK) {
                    add(slots, covered, new Slot(row, col, runLength(grid, row, col, false), false));
                }
            }
        }

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                if (grid[row][col] == BLANK && !covered[row][col]) {
                    slots.add(new Slot(row, col, 1, true));   // a blank on its own needs its own word
                }
            }
        }

        return slots;
    }

    private static void add(List<Slot> slots, boolean[][] covered, Slot slot) {
        slots.add(slot);
        for (int k = 0; k < slot.length(); k++) {
            covered[slot.rowAt(k)][slot.colAt(k)] = true;
        }
    }

    private static int runLength(char[][] grid, int row, int col, boolean across) {
        int rowStep = across ? 0 : 1;
        int colStep = across ? 1 : 0;
        int length = 0;

        while (row + rowStep * length < grid.length
                && col + colStep * length < grid[0].length
                && grid[row + rowStep * length][col + colStep * length] == BLANK) {
            length++;
        }

        return length;
    }

    private static char[][] toGrid(String[] crossword) {
        if (crossword == null || crossword.length == 0) {
            throw new IllegalArgumentException("crossword must not be null or empty");
        }

        int cols = crossword[0] == null ? -1 : crossword[0].length();
        char[][] grid = new char[crossword.length][];

        for (int row = 0; row < crossword.length; row++) {
            String line = crossword[row];
            if (line == null || line.length() != cols) {
                throw new IllegalArgumentException("row " + row + " is not " + cols + " characters wide");
            }
            for (int col = 0; col < cols; col++) {
                char cell = line.charAt(col);
                if (cell != WALL && cell != BLANK) {
                    throw new IllegalArgumentException(
                            "row " + row + " contains '" + cell + "'; only '" + WALL + "' and '" + BLANK + "' are allowed");
                }
            }
            grid[row] = line.toCharArray();
        }

        return grid;
    }

    private static String[] splitWords(String words) {
        if (words == null) throw new IllegalArgumentException("words must not be null");
        if (words.isEmpty()) return new String[0];

        String[] split = words.split(";");
        for (String word : split) {
            if (word.isEmpty()) throw new IllegalArgumentException("the word list has an empty word: \"" + words + "\"");
        }

        return split;
    }

    /** One run of blanks: where it starts, how long it is, and which way it goes. */
    private record Slot(int row, int col, int length, boolean across) {

        int rowAt(int k) {
            return across ? row : row + k;
        }

        int colAt(int k) {
            return across ? col + k : col;
        }
    }

    private CrosswordPuzzle() {
    }
}

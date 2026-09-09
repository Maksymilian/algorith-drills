package unclassified;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FindACycleInDirectionalGraph {

    private static final int WHITE = 0;   // not visited
    private static final int GRAY  = 1;   // visited, still on the DFS stack
    private static final int BLACK = 2;   // visited, fully explored

    /**
     * Finds one cycle in a directed graph given as an adjacency list.
     * Returns the cycle as node ids with the entry node repeated at the end
     * (e.g. [1, 2, 3, 1]), or an empty list if the graph is acyclic.
     * O(V + E) time, O(V) space, iterative so the recursion depth is bounded
     * by the heap rather than the JVM stack.
     */
    public static List<Integer> findCycle(List<List<Integer>> adj) {
        int n = adj.size();
        int[] color = new int[n];
        int[] parent = new int[n];
        int[] nextEdge = new int[n];   // how far into adj.get(u) each frame has walked
        Arrays.fill(parent, -1);

        Deque<Integer> stack = new ArrayDeque<>();

        // every node needs a start: the graph may be disconnected
        for (int s = 0; s < n; s++) {
            if (color[s] != WHITE) continue;

            color[s] = GRAY;
            stack.push(s);

            while (!stack.isEmpty()) {
                int u = stack.peek();                 // peek, not pop: the frame stays
                List<Integer> edges = adj.get(u);

                if (nextEdge[u] < edges.size()) {
                    int v = edges.get(nextEdge[u]++); // consume one edge, then re-loop
                    if (color[v] == GRAY) {           // back edge: v is still on the stack
                        return buildCycle(u, v, parent);
                    }
                    if (color[v] == WHITE) {
                        parent[v] = u;
                        color[v] = GRAY;
                        stack.push(v);                // "descend" into v
                    }
                    // color[v] == BLACK: already fully explored, not a cycle
                } else {
                    color[u] = BLACK;                 // post-order: all edges done
                    stack.pop();                      // "return" from u
                }
            }
        }
        return List.of();
    }

    /** Walks parent pointers from u back up to start, then closes the loop. */
    private static List<Integer> buildCycle(int u, int start, int[] parent) {
        List<Integer> path = new ArrayList<>();
        for (int x = u; x != start; x = parent[x]) path.add(x);
        path.add(start);
        Collections.reverse(path);   // start -> ... -> u
        path.add(start);             // close it
        return path;
    }

    /**
     * Detection only, via Kahn's algorithm: strip nodes with in-degree 0 until
     * none are left. Anything still standing is locked in a cycle.
     */
    public static boolean hasCycle(List<List<Integer>> adj) {
        int n = adj.size();
        int[] inDegree = new int[n];
        for (List<Integer> edges : adj)
            for (int v : edges) inDegree[v]++;

        Deque<Integer> queue = new ArrayDeque<>();
        for (int u = 0; u < n; u++)
            if (inDegree[u] == 0) queue.add(u);

        int removed = 0;
        while (!queue.isEmpty()) {
            int u = queue.poll();
            removed++;
            for (int v : adj.get(u))
                if (--inDegree[v] == 0) queue.add(v);
        }
        return removed < n;
    }

    // ---------- tests ----------

    private static int passed = 0;
    private static int failed = 0;

    private static List<List<Integer>> graph(int n, int[][] edges) {
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int[] e : edges) adj.get(e[0]).add(e[1]);
        return adj;
    }

    /** A path 0 -> 1 -> ... -> n-1, optionally closed back to 0. */
    private static List<List<Integer>> chain(int n, boolean closeLoop) {
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>(1));
        for (int i = 0; i + 1 < n; i++) adj.get(i).add(i + 1);
        if (closeLoop) adj.get(n - 1).add(0);
        return adj;
    }

    /**
     * The particular cycle returned depends on traversal order, so verify the
     * result is a genuine cycle of the graph rather than comparing to a fixed list.
     */
    private static boolean isRealCycle(List<Integer> cycle, List<List<Integer>> adj) {
        if (cycle.size() < 2) return false;
        if (!cycle.get(0).equals(cycle.get(cycle.size() - 1))) return false;
        for (int i = 0; i + 1 < cycle.size(); i++)
            if (!adj.get(cycle.get(i)).contains(cycle.get(i + 1))) return false;
        Set<Integer> interior = new HashSet<>(cycle.subList(0, cycle.size() - 1));
        return interior.size() == cycle.size() - 1;   // no node repeated mid-cycle
    }

    private static void check(String name, boolean cyclic, int n, int[][] edges) {
        check(name, cyclic, graph(n, edges));
    }

    private static void check(String name, boolean cyclic, List<List<Integer>> adj) {
        List<Integer> cycle = findCycle(adj);
        boolean kahn = hasCycle(adj);

        String problem = null;
        if (cyclic) {
            if (cycle.isEmpty()) problem = "found no cycle, expected one";
            else if (!isRealCycle(cycle, adj)) problem = "returned " + describe(cycle) + ", which is not a cycle";
        } else if (!cycle.isEmpty()) {
            problem = "reported cycle " + describe(cycle) + " in an acyclic graph";
        }
        if (problem == null && kahn != cyclic)
            problem = "hasCycle returned " + kahn + ", expected " + cyclic;

        if (problem == null) {
            passed++;
            System.out.printf("PASS  %-28s -> %s%n", name, cycle.isEmpty() ? "acyclic" : describe(cycle));
        } else {
            failed++;
            System.out.printf("FAIL  %-28s -> %s%n", name, problem);
        }
    }

    /** Keeps a 200k-node cycle from flooding the console. */
    private static String describe(List<Integer> cycle) {
        if (cycle.size() <= 12) return cycle.toString();
        return "cycle of length " + (cycle.size() - 1) + " starting " + cycle.subList(0, 4) + "...";
    }

    public static void main(String[] args) {
        // the basics
        check("triangle",         true,  3, new int[][]{{0,1},{1,2},{2,0}});
        check("two-node cycle",   true,  2, new int[][]{{0,1},{1,0}});
        check("self loop",        true,  1, new int[][]{{0,0}});
        check("simple chain",     false, 3, new int[][]{{0,1},{1,2}});

        // the classic false positive: C is reached twice, but there is no cycle
        check("cross edge",       false, 3, new int[][]{{0,1},{0,2},{1,2}});
        check("diamond DAG",      false, 4, new int[][]{{0,1},{0,2},{1,3},{2,3}});

        // cycle hidden behind a tail, so the entry node is not part of it
        check("tail into cycle",  true,  5, new int[][]{{0,1},{1,2},{2,3},{3,4},{4,1}});

        // disconnected components
        check("cycle in 2nd comp", true, 5, new int[][]{{0,1},{2,3},{3,4},{4,2}});
        check("both comps acyclic", false, 4, new int[][]{{0,1},{2,3}});

        // degenerate shapes
        check("empty graph",      false, 0, new int[][]{});
        check("isolated nodes",   false, 3, new int[][]{});

        // parallel edges and a node feeding a cycle it is not in
        check("parallel edges",   false, 2, new int[][]{{0,1},{0,1}});
        check("source into loop", true,  4, new int[][]{{0,1},{1,2},{2,3},{3,2}});

        // depth the recursive version could not survive
        check("deep chain 200k",  false, chain(200_000, false));
        check("deep cycle 200k",  true,  chain(200_000, true));

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
    }

}

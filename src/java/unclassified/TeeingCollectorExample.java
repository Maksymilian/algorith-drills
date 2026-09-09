package unclassified;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collectors.teeing (Java 12+) feeds every element to TWO downstream collectors
 * and then merges their results with a BiFunction. It is the answer to
 * "I need two different aggregates but I only want to traverse the stream once"
 * — which also makes it usable on a stream you cannot replay (a file, a socket).
 */
public class TeeingCollectorExample {

    record Order(String customer, String category, double amount) {}

    record Range(double min, double max) {
        double spread() { return max - min; }
    }

    record Summary(long count, double total, double average, Range range) {}

    // ---------- 1. two aggregates, one pass ----------

    /** min and max without traversing the list twice. */
    static Range minMax(List<Order> orders) {
        return orders.stream().collect(Collectors.teeing(
                Collectors.minBy((a, b) -> Double.compare(a.amount(), b.amount())),
                Collectors.maxBy((a, b) -> Double.compare(a.amount(), b.amount())),
                (min, max) -> new Range(
                        min.map(Order::amount).orElse(Double.NaN),
                        max.map(Order::amount).orElse(Double.NaN))));
    }

    /** The classic: average as sum / count, computed by two collectors at once. */
    static double average(List<Order> orders) {
        return orders.stream().collect(Collectors.teeing(
                Collectors.summingDouble(Order::amount),
                Collectors.counting(),
                (sum, count) -> count == 0 ? 0.0 : sum / count));
    }

    // ---------- 2. teeing nested in teeing, for more than two results ----------

    /**
     * teeing only takes two downstreams, so three or more aggregates means
     * nesting: (count, total) on one side, the min/max range on the other.
     */
    static Summary summarize(List<Order> orders) {
        return orders.stream().collect(Collectors.teeing(
                Collectors.teeing(
                        Collectors.counting(),
                        Collectors.summingDouble(Order::amount),
                        (count, total) -> new double[]{count, total}),
                Collectors.teeing(
                        Collectors.minBy((a, b) -> Double.compare(a.amount(), b.amount())),
                        Collectors.maxBy((a, b) -> Double.compare(a.amount(), b.amount())),
                        (min, max) -> new Range(
                                min.map(Order::amount).orElse(0.0),
                                max.map(Order::amount).orElse(0.0))),
                (countAndTotal, range) -> {
                    long count = (long) countAndTotal[0];
                    double total = countAndTotal[1];
                    return new Summary(count, total, count == 0 ? 0.0 : total / count, range);
                }));
    }

    // ---------- 3. two different *filters* over the same stream ----------

    /** Splits on a threshold: the big spenders' names and how much the rest paid. */
    static String splitOnThreshold(List<Order> orders, double threshold) {
        return orders.stream().collect(Collectors.teeing(
                Collectors.filtering(o -> o.amount() >= threshold,
                        Collectors.mapping(Order::customer, Collectors.toList())),
                Collectors.filtering(o -> o.amount() < threshold,
                        Collectors.summingDouble(Order::amount)),
                (big, restTotal) -> big + " >= " + threshold + ", rest paid " + restTotal));
    }

    // ---------- 4. it works on an infinite/lazy source too ----------

    /** First and last of a generated stream — no intermediate list involved. */
    static Range firstAndLastOfSquares(int n) {
        return Stream.iterate(1, i -> i + 1).limit(n)
                .map(i -> (double) i * i)
                .collect(Collectors.teeing(
                        Collectors.reducing((first, next) -> first),   // keep the first
                        Collectors.reducing((prev, next) -> next),     // keep the last
                        (first, last) -> new Range(first.orElse(0.0), last.orElse(0.0))));
    }

    // ---------- tests ----------

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, Object expected, Object actual) {
        if (expected.equals(actual)) {
            passed++;
            System.out.printf("PASS  %-28s -> %s%n", name, actual);
        } else {
            failed++;
            System.out.printf("FAIL  %-28s -> %s (expected %s)%n", name, actual, expected);
        }
    }

    private static final List<Order> ORDERS = List.of(
            new Order("ada",    "books",   30.0),
            new Order("linus",  "tools",   10.0),
            new Order("grace",  "books",   50.0),
            new Order("ada",    "tools",   10.0));

    public static void main(String[] args) {
        check("min/max",            new Range(10.0, 50.0), minMax(ORDERS));
        check("spread",             40.0,                  minMax(ORDERS).spread());
        check("average",            25.0,                  average(ORDERS));
        check("average of empty",   0.0,                   average(List.of()));

        check("summary",
                new Summary(4L, 100.0, 25.0, new Range(10.0, 50.0)),
                summarize(ORDERS));
        check("summary of empty",
                new Summary(0L, 0.0, 0.0, new Range(0.0, 0.0)),
                summarize(List.of()));

        check("split on threshold",
                "[ada, grace] >= 30.0, rest paid 20.0",
                splitOnThreshold(ORDERS, 30.0));

        check("first/last squares",  new Range(1.0, 25.0),  firstAndLastOfSquares(5));
        check("single element",      new Range(1.0, 1.0),   firstAndLastOfSquares(1));

        // min/max on an empty list: both Optionals are empty, the merger decides
        check("min/max of empty",
                new Range(Double.NaN, Double.NaN).toString(),
                minMax(List.of()).toString());

        // teeing composes with groupingBy like any other collector
        var perCategory = ORDERS.stream().collect(Collectors.groupingBy(
                Order::category,
                Collectors.teeing(
                        Collectors.counting(),
                        Collectors.summingDouble(Order::amount),
                        (count, total) -> count + " orders, " + total)));
        check("grouped teeing books", "2 orders, 80.0", perCategory.get("books"));
        check("grouped teeing tools", "2 orders, 20.0", perCategory.get("tools"));

        System.out.printf("%n%d passed, %d failed%n", passed, failed);
    }
}

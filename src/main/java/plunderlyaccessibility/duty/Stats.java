package plunderlyaccessibility.duty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Core calculations over duty score events.
 *
 * This class has no game or UI dependency. It provides totals, averages, best
 * values, per-bonus summaries, lair-score handling, and running-average deltas
 * for the dashboard and copy text.
 */
public final class Stats {

    /** The one score type whose quality is a signed lair score, not a raw sum. */
    public static final String LAIR_TYPE = "vampire_patches";

    public static boolean isLairType(String scoreType) {
        return LAIR_TYPE.equals(scoreType);
    }

    /** Sum of all bonus counts — the main score for most types. */
    public static int rawTotal(ScoreEvent event) {
        int sum = 0;
        for (int value : event.bonusCounts.values()) sum += value;
        return sum;
    }

    /** Signed vampire-patch lair score: vampire-proof patches count double. */
    public static int lairScore(ScoreEvent event) {
        return 2 * count(event, "vampire_proof")
                + count(event, "creaky_coffin")
                - count(event, "slipshod");
    }

    /** Main score for rankings/bests/averages: lair score for patches, else raw total. */
    public static int scoreValue(ScoreEvent event) {
        return isLairType(event.scoreType) ? lairScore(event) : rawTotal(event);
    }

    /** Max main score over a set (0 if empty). */
    public static int best(List<ScoreEvent> events) {
        boolean any = false;
        int best = 0;
        for (ScoreEvent event : events) {
            int value = scoreValue(event);
            if (!any || value > best) {
                best = value;
                any = true;
            }
        }
        return best;
    }

    public static int scoreValueTotal(List<ScoreEvent> events) {
        int sum = 0;
        for (ScoreEvent event : events) sum += scoreValue(event);
        return sum;
    }

    public static double scoreValueAverage(List<ScoreEvent> events) {
        return events.isEmpty() ? 0.0 : (double) scoreValueTotal(events) / events.size();
    }

    /**
     * Average rating on the shared <b>0–5</b> display scale (see
     * {@link Performance#displayRank(int)}), so classic and expedition ratings
     * are visually comparable instead of sitting in 0–6 vs 7–12 bands. Unread
     * ratings (-1) and the Learning tier (6, no rank) are ignored. Returns -1.0
     * when no event has a ranked rating.
     */
    public static double performanceAverage(List<ScoreEvent> events) {
        int sum = 0;
        int known = 0;
        for (ScoreEvent event : events) {
            int rank = Performance.displayRank(event.performance);
            if (rank >= 0) {
                sum += rank;
                known++;
            }
        }
        return known == 0 ? -1.0 : (double) sum / known;
    }

    /**
     * Aggregate a set of same-typed events: per-bonus totals & averages in bonus
     * order, aggregate total & average, best, and (for vampire patches) the
     * signed lair total & average.
     */
    public static CategorySummary summarize(String scoreType, List<ScoreEvent> events) {
        CategorySummary summary = new CategorySummary();
        summary.scoreType = scoreType;
        summary.count = events.size();
        summary.lair = isLairType(scoreType);

        for (String key : bonusKeys(scoreType, events)) {
            summary.bonusTotals.put(key, 0);
        }
        for (ScoreEvent event : events) {
            for (Map.Entry<String, Integer> bonus : event.bonusCounts.entrySet()) {
                if (summary.bonusTotals.containsKey(bonus.getKey())) {
                    summary.bonusTotals.merge(bonus.getKey(), bonus.getValue(), Integer::sum);
                }
            }
        }
        int aggregate = 0;
        for (Map.Entry<String, Integer> total : summary.bonusTotals.entrySet()) {
            aggregate += total.getValue();
            summary.bonusAverages.put(total.getKey(),
                    summary.count == 0 ? 0.0 : (double) total.getValue() / summary.count);
        }
        summary.aggregateTotal = aggregate;
        summary.aggregateAverage = summary.count == 0 ? 0.0 : (double) aggregate / summary.count;
        summary.best = best(events);
        if (summary.lair) {
            int lairSum = 0;
            for (ScoreEvent event : events) lairSum += lairScore(event);
            summary.lairTotal = lairSum;
            summary.lairAverage = summary.count == 0 ? 0.0 : (double) lairSum / summary.count;
        }
        return summary;
    }

    /**
     * Running-average delta of {@code current} against {@code previous} scans of
     * the same score type: (currentValue - previousAverage) / (previousCount + 1),
     * for the raw total, each bonus count, and (if a lair type) the lair score.
     */
    public static Delta runningDelta(List<ScoreEvent> previous, ScoreEvent current) {
        Delta delta = new Delta();
        delta.previousCount = previous.size();
        double denom = previous.size() + 1;

        delta.totalDelta = (rawTotal(current) - mean(previous, Stats::rawTotal)) / denom;
        for (Map.Entry<String, Integer> bonus : current.bonusCounts.entrySet()) {
            double prevAvg = meanBonus(previous, bonus.getKey());
            delta.bonusDeltas.put(bonus.getKey(), (bonus.getValue() - prevAvg) / denom);
        }
        if (isLairType(current.scoreType)) {
            delta.hasLair = true;
            delta.lairDelta = (lairScore(current) - mean(previous, Stats::lairScore)) / denom;
        }
        return delta;
    }

    // Formatting helpers.

    /** Dashboard average format: one decimal below 10, whole number at 10 or higher. */
    public static String formatAverage(double value) {
        if (value < 10.0) return String.format(Locale.ROOT, "%.1f", value);
        return String.valueOf(Math.round(value));
    }

    /** Copy-text average format: always one decimal. */
    public static String round1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Signed integer for lair scores: "+7", "0", "-3". */
    public static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    /** Signed one-decimal for average lair scores: "+3.2", "0.0", "-1.5". */
    public static String signedAvg(double value) {
        String magnitude = String.format(Locale.ROOT, "%.1f", Math.abs(value));
        if (value > 0) return "+" + magnitude;
        if (value < 0) return "-" + magnitude;
        return magnitude;
    }

    // Internal helpers.

    private static int count(ScoreEvent event, String key) {
        Integer value = event.bonusCounts.get(key);
        return value == null ? 0 : value;
    }

    private static List<String> bonusKeys(String scoreType, List<ScoreEvent> events) {
        ScoreTypes.ScoreType type = ScoreTypes.byKey(scoreType);
        if (type != null) return type.bonusOrder;
        // Unknown types keep whatever bonus keys were observed, in encounter order.
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (ScoreEvent event : events) {
            for (String key : event.bonusCounts.keySet()) seen.putIfAbsent(key, Boolean.TRUE);
        }
        return new ArrayList<>(seen.keySet());
    }

    private interface ToInt {
        int apply(ScoreEvent event);
    }

    private static double mean(List<ScoreEvent> events, ToInt fn) {
        if (events.isEmpty()) return 0.0;
        int sum = 0;
        for (ScoreEvent event : events) sum += fn.apply(event);
        return (double) sum / events.size();
    }

    private static double meanBonus(List<ScoreEvent> events, String key) {
        if (events.isEmpty()) return 0.0;
        int sum = 0;
        for (ScoreEvent event : events) sum += event.bonusCounts.getOrDefault(key, 0);
        return (double) sum / events.size();
    }

    public static final class CategorySummary {
        public String scoreType = "";
        public int count;
        public boolean lair;
        public final Map<String, Integer> bonusTotals = new LinkedHashMap<>();
        public final Map<String, Double> bonusAverages = new LinkedHashMap<>();
        public int aggregateTotal;
        public double aggregateAverage;
        public int best;
        public int lairTotal;     // signed; meaningful only when lair
        public double lairAverage;
    }

    public static final class Delta {
        public int previousCount;
        public double totalDelta;
        public final Map<String, Double> bonusDeltas = new LinkedHashMap<>();
        public boolean hasLair;
        public double lairDelta;
    }

    private Stats() {
    }
}

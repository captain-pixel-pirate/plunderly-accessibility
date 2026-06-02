package plunderlyaccessibility.duty;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure presentation logic for the duty dashboard.
 *
 */
public final class DashboardModel {

    public enum Mode { MY, CREW }

    public enum DateRange { ALL, WEEK, TODAY }

    /** Column headers, typed row values, and stable row keys for click actions. */
    public static final class TableData {
        public final List<String> columns = new ArrayList<>();
        public final List<List<Object>> rows = new ArrayList<>();
        public final List<String> rowKeys = new ArrayList<>();
        public boolean lairTotals; // total/average values should render as signed lair scores
    }

    // Filtering.

    public static List<ScoreEvent> filter(List<ScoreEvent> all, Mode mode, DateRange range, Instant now) {
        List<ScoreEvent> out = new ArrayList<>();
        for (ScoreEvent event : all) {
            if (mode == Mode.MY && !event.isUser) continue;
            if (!inRange(event, range, now)) continue;
            out.add(event);
        }
        return out;
    }

    static boolean inRange(ScoreEvent event, DateRange range, Instant now) {
        if (range == DateRange.ALL) return true;
        Instant when = parseInstant(event.occurredAt);
        if (when == null) return false;
        if (range == DateRange.TODAY) {
            ZoneId zone = ZoneId.systemDefault();
            return when.atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate());
        }
        return !when.isBefore(now.minus(Duration.ofDays(7)));
    }

    /** Score-type counts in display order for filter buttons. */
    public static LinkedHashMap<String, Integer> scoreTypeCounts(List<ScoreEvent> events) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        for (ScoreEvent event : events) raw.merge(event.scoreType, 1, Integer::sum);
        LinkedHashMap<String, Integer> ordered = new LinkedHashMap<>();
        for (String key : ScoreTypes.keys()) {
            if (raw.containsKey(key)) ordered.put(key, raw.remove(key));
        }
        raw.forEach(ordered::put); // unmapped or future types appear after known types
        return ordered;
    }

    // Table builders.

    public static TableData buildTable(List<ScoreEvent> events, Mode mode, String typeFilter,
                                       boolean byStation, boolean byRating) {
        boolean filtered = typeFilter != null && !typeFilter.isEmpty();
        // The maneuvers-by-station breakdown is available in both modes.
        if (byStation && "maneuvers".equals(typeFilter)) {
            return maneuverStationRows(events, mode);
        }
        // Per-rating breakdown for any filtered type.
        if (byRating && filtered) {
            return ratingBreakdown(events, typeFilter);
        }
        if (mode == Mode.MY) {
            return filtered ? myScanRows(events, typeFilter) : categoryOverview(events);
        }
        return filtered ? crewByType(events, typeFilter) : crewMixed(events);
    }

    /** My Scans, no filter: one row per score type. Click a row to filter. */
    static TableData categoryOverview(List<ScoreEvent> events) {
        TableData table = new TableData();
        table.columns.addAll(List.of("Score Type", "Scans", "Avg", "Avg Perf"));
        Map<String, List<ScoreEvent>> byType = groupByType(events);
        for (String type : orderTypes(byType.keySet())) {
            List<ScoreEvent> typed = byType.get(type);
            table.rows.add(row(label(type), typed.size(),
                    Stats.scoreValueAverage(typed),
                    Stats.performanceAverage(typed)));
            table.rowKeys.add(type);
        }
        return table;
    }

    /** My Scans, one type: per-scan rows, ranked by score. */
    static TableData myScanRows(List<ScoreEvent> events, String type) {
        TableData table = new TableData();
        table.lairTotals = Stats.isLairType(type);
        List<String> bonusKeys = bonusKeys(type, events);
        table.columns.add("#");
        table.columns.add("Rating");
        table.columns.add("Pirate");
        table.columns.add(table.lairTotals ? "Lair" : "Total");
        for (String key : bonusKeys) table.columns.add(prettyBonus(key));
        table.columns.add("Scanned");

        List<ScoreEvent> typed = sortedByScoreDesc(forType(events, type));
        int rank = 1;
        for (ScoreEvent event : typed) {
            List<Object> row = new ArrayList<>();
            row.add(rank++);
            row.add(event.rating);
            row.add(event.pirateName);
            row.add(Stats.scoreValue(event));
            for (String key : bonusKeys) row.add(event.bonusCounts.getOrDefault(key, 0));
            row.add(epochMillis(event.occurredAt));
            table.rows.add(row);
            table.rowKeys.add(event.id);
        }
        return table;
    }

    /** Crew Scans, no filter: one row per pirate, mixed types (no bonus breakdown). */
    static TableData crewMixed(List<ScoreEvent> events) {
        TableData table = new TableData();
        table.columns.addAll(List.of("#", "Pirate", "Types", "Scans", "Avg Perf", "Latest Scan"));
        List<Map.Entry<String, List<ScoreEvent>>> pirates = new ArrayList<>(groupByPirate(events).entrySet());
        // Latest activity is the primary crew overview signal; count and average
        // make the ordering stable when multiple pirates scanned at the same time.
        pirates.sort((a, b) -> {
            int byLatest = Long.compare(latest(b.getValue()), latest(a.getValue()));
            if (byLatest != 0) return byLatest;
            int byCount = Integer.compare(b.getValue().size(), a.getValue().size());
            if (byCount != 0) return byCount;
            return Double.compare(Stats.scoreValueAverage(b.getValue()), Stats.scoreValueAverage(a.getValue()));
        });
        int rank = 1;
        for (Map.Entry<String, List<ScoreEvent>> entry : pirates) {
            List<ScoreEvent> scans = entry.getValue();
            table.rows.add(row(rank++, entry.getKey(), typesLabel(scans), scans.size(),
                    Stats.performanceAverage(scans), latest(scans)));
            table.rowKeys.add(entry.getKey());
        }
        return table;
    }

    /**
     * Crew drill: one row per individual scan for a single pirate, newest first.
     * Mixed across score types (so no per-type bonus columns), which is why it
     * carries a Score Type column. When {@code typeFilter} is non-null the rows
     * are scoped to that type, matching the dashboard's active filter.
     */
    static TableData pirateScanRows(List<ScoreEvent> events, String pirate, String typeFilter) {
        TableData table = new TableData();
        table.columns.addAll(List.of("Scanned", "Score Type", "Station", "Rating", "Total"));
        List<ScoreEvent> theirs = forPirate(events, pirate);
        if (typeFilter != null && !typeFilter.isEmpty()) theirs = forType(theirs, typeFilter);
        theirs.sort((a, b) -> Long.compare(epochMillis(b.occurredAt), epochMillis(a.occurredAt)));
        for (ScoreEvent event : theirs) {
            List<Object> row = new ArrayList<>();
            row.add(epochMillis(event.occurredAt));
            row.add(label(event.scoreType));
            row.add(event.rawDuty == null ? "" : event.rawDuty);
            row.add(event.rating == null ? "" : event.rating);
            row.add(Stats.scoreValue(event));
            table.rows.add(row);
            table.rowKeys.add(event.id);
        }
        return table;
    }

    /** Crew Scans, one type: one row per pirate with per-bonus averages, ranked by best average. */
    static TableData crewByType(List<ScoreEvent> events, String type) {
        TableData table = new TableData();
        table.lairTotals = Stats.isLairType(type);
        List<String> bonusKeys = bonusKeys(type, events);
        table.columns.add("#");
        table.columns.add("Pirate");
        table.columns.add("Scans");
        table.columns.add(table.lairTotals ? "Avg Lair" : "Avg");
        table.columns.add("Total");
        for (String key : bonusKeys) table.columns.add(prettyBonus(key));
        table.columns.add("Avg Perf");
        table.columns.add("Latest Scan");

        List<Map.Entry<String, List<ScoreEvent>>> pirates =
                new ArrayList<>(groupByPirate(forType(events, type)).entrySet());
        pirates.sort((a, b) -> Double.compare(
                Stats.scoreValueAverage(b.getValue()), Stats.scoreValueAverage(a.getValue())));
        int rank = 1;
        for (Map.Entry<String, List<ScoreEvent>> entry : pirates) {
            List<ScoreEvent> scans = entry.getValue();
            Stats.CategorySummary summary = Stats.summarize(type, scans);
            List<Object> row = new ArrayList<>();
            row.add(rank++);
            row.add(entry.getKey());
            row.add(scans.size());
            row.add(Stats.scoreValueAverage(scans));
            row.add(table.lairTotals ? summary.lairTotal : Stats.scoreValueTotal(scans));
            for (String key : bonusKeys) row.add(summary.bonusAverages.getOrDefault(key, 0.0));
            row.add(Stats.performanceAverage(scans));
            row.add(latest(scans));
            table.rows.add(row);
            table.rowKeys.add(entry.getKey());
        }
        return table;
    }

    /**
     * Maneuvers only: one row per station (in canonical maneuver-station order,
     * unexpected stations appended) plus an Overall row aggregating all stations.
     * Uses {@code rawDuty}; works in both modes (scope is filtered upstream).
     * Row keys are the station's rawDuty (empty for Overall) for drill-downs.
     */
    static TableData maneuverStationRows(List<ScoreEvent> events, Mode mode) {
        String type = "maneuvers";
        List<ScoreEvent> typed = forType(events, type);
        List<String> bonusKeys = bonusKeys(type, typed);

        TableData table = new TableData();
        table.columns.add("Station");
        table.columns.add("Scans");
        table.columns.add("Avg");
        for (String key : bonusKeys) table.columns.add(prettyBonus(key));
        table.columns.add("Avg Perf");

        Map<String, List<ScoreEvent>> byStation = groupByRawDuty(typed);
        for (String station : orderStations(byStation.keySet())) {
            List<ScoreEvent> scans = byStation.get(station);
            table.rows.add(stationRow(station, type, scans, bonusKeys));
            table.rowKeys.add(station);
        }
        if (!typed.isEmpty()) {
            table.rows.add(stationRow("Overall", type, typed, bonusKeys));
            table.rowKeys.add(""); // Overall is not a drill-down target
        }
        return table;
    }

    private static List<Object> stationRow(String label, String type,
                                           List<ScoreEvent> scans, List<String> bonusKeys) {
        Stats.CategorySummary summary = Stats.summarize(type, scans);
        List<Object> row = new ArrayList<>();
        row.add(label);
        row.add(scans.size());
        row.add(Stats.scoreValueAverage(scans));
        for (String key : bonusKeys) row.add(summary.bonusAverages.getOrDefault(key, 0.0));
        row.add(Stats.performanceAverage(scans));
        return row;
    }

    private static Map<String, List<ScoreEvent>> groupByRawDuty(List<ScoreEvent> events) {
        Map<String, List<ScoreEvent>> map = new LinkedHashMap<>();
        for (ScoreEvent event : events) {
            map.computeIfAbsent(event.rawDuty, k -> new ArrayList<>()).add(event);
        }
        return map;
    }

    private static List<String> orderStations(java.util.Set<String> present) {
        List<String> ordered = new ArrayList<>();
        for (String canonical : Activities.MANEUVER_STATIONS) {
            for (String station : present) {
                if (station != null && station.trim().equalsIgnoreCase(canonical)
                        && !ordered.contains(station)) {
                    ordered.add(station);
                }
            }
        }
        for (String station : present) if (!ordered.contains(station)) ordered.add(station);
        return ordered;
    }

    /**
     * Per-rating breakdown for one score type: one row per duty-report rating
     * present, ordered by the performance integer (ascending = worst→best, so
     * the ladder is exact). Works for any type. Callers scope the events first
     * (e.g. to a single station) to get a station×rating breakdown.
     * Columns: Rating, Scans, Avg, &lt;bonus columns&gt;.
     */
    static TableData ratingBreakdown(List<ScoreEvent> events, String type) {
        List<ScoreEvent> typed = forType(events, type);
        List<String> bonusKeys = bonusKeys(type, typed);

        TableData table = new TableData();
        table.lairTotals = Stats.isLairType(type);
        table.columns.add("Rating");
        table.columns.add("Scans");
        table.columns.add(table.lairTotals ? "Avg Lair" : "Avg");
        for (String key : bonusKeys) table.columns.add(prettyBonus(key));

        for (Map.Entry<Integer, List<ScoreEvent>> bucket : bucketByRating(typed).entrySet()) {
            List<ScoreEvent> scans = bucket.getValue();
            Stats.CategorySummary summary = Stats.summarize(type, scans);
            List<Object> row = new ArrayList<>();
            row.add(Performance.word(bucket.getKey()));
            row.add(scans.size());
            row.add(Stats.scoreValueAverage(scans));
            for (String key : bonusKeys) row.add(summary.bonusAverages.getOrDefault(key, 0.0));
            table.rows.add(row);
            table.rowKeys.add(String.valueOf(bucket.getKey()));
        }
        return table;
    }

    /** Group events by their performance integer, ascending (worst→best). */
    private static Map<Integer, List<ScoreEvent>> bucketByRating(List<ScoreEvent> events) {
        Map<Integer, List<ScoreEvent>> byRating = new TreeMap<>();
        for (ScoreEvent event : events) {
            byRating.computeIfAbsent(event.performance, k -> new ArrayList<>()).add(event);
        }
        return byRating;
    }

    // Summary text.

    public static String summary(List<ScoreEvent> events, Mode mode, String typeFilter, String selectedPirate) {
        boolean filtered = typeFilter != null && !typeFilter.isEmpty();
        StringBuilder sb = new StringBuilder();
        if (events.isEmpty()) {
            return "No scans match the current filters.";
        }
        if (filtered) {
            sb.append(filterSummary(forType(events, typeFilter), typeFilter, mode));
        } else if (mode == Mode.MY) {
            sb.append("My scans: ").append(events.size())
                    .append(" across ").append(groupByType(events).size())
                    .append(" categories. Pick a category below for details.");
        } else {
            sb.append("Crew: ").append(groupByPirate(events).size())
                    .append(" pirates, ").append(events.size()).append(" scans (all types).");
        }
        if (mode == Mode.CREW && selectedPirate != null && !selectedPirate.isEmpty()) {
            List<ScoreEvent> theirs = forPirate(events, selectedPirate);
            if (filtered) theirs = forType(theirs, typeFilter);
            if (!theirs.isEmpty()) {
                sb.append("\n\n").append(pirateDetail(selectedPirate, theirs, filtered ? typeFilter : null));
            }
        }
        return sb.toString();
    }

    private static String filterSummary(List<ScoreEvent> typed, String type, Mode mode) {
        Stats.CategorySummary s = Stats.summarize(type, typed);
        StringBuilder head = new StringBuilder();
        head.append(label(type).toUpperCase(Locale.ROOT)).append(" (");
        if (mode == Mode.CREW) {
            head.append(groupByPirate(typed).size()).append(" pirates, ");
        }
        head.append(s.count).append(" scans, best ")
                .append(s.lair ? Stats.signed(s.best) : String.valueOf(s.best)).append(')');
        return summaryBlock(head.toString(), s);
    }

    private static String pirateDetail(String pirate, List<ScoreEvent> scans, String type) {
        if (type != null) {
            Stats.CategorySummary s = Stats.summarize(type, scans);
            String head = pirate.toUpperCase(Locale.ROOT) + " (" + scans.size() + " scans, best "
                    + (s.lair ? Stats.signed(s.best) : String.valueOf(s.best)) + ")";
            return summaryBlock(head, s);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(pirate.toUpperCase(Locale.ROOT)).append(" (").append(scans.size())
                .append(" scans across ").append(typesLabel(scans)).append(")\n");
        sb.append("Overall: ").append(Stats.best(scans)).append(" best, ")
                .append(Stats.round1(Stats.scoreValueAverage(scans))).append(" avg");
        return sb.toString();
    }

    /** Renders a heading line followed by an "Overall" line and one line per bonus. */
    private static String summaryBlock(String heading, Stats.CategorySummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading).append('\n');
        if (s.lair) {
            sb.append("Overall: ").append(Stats.signed(s.lairTotal))
                    .append(" total, ").append(Stats.signedAvg(s.lairAverage)).append(" avg");
        } else {
            sb.append("Overall: ").append(s.aggregateTotal)
                    .append(" total, ").append(Stats.round1(s.aggregateAverage)).append(" avg");
        }
        for (Map.Entry<String, Integer> total : s.bonusTotals.entrySet()) {
            double avg = s.bonusAverages.getOrDefault(total.getKey(), 0.0);
            sb.append('\n').append(prettyBonus(total.getKey())).append(": ")
                    .append(total.getValue()).append(" total, ")
                    .append(Stats.round1(avg)).append(" avg");
        }
        return sb.toString();
    }

    // Shared formatting and grouping helpers.

    public static String label(String type) {
        ScoreTypes.ScoreType t = ScoreTypes.byKey(type);
        if (t != null) return t.label;
        return type == null || type.isEmpty() ? "(none)"
                : Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    public static String prettyBonus(String key) {
        if (key == null || key.isEmpty()) return key;
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** Short relative time, e.g. "5m", "3h", "2d", or "now". */
    public static String relativeTime(long epochMillis, long nowMillis) {
        if (epochMillis <= 0) return "";
        long secs = Math.max(0, (nowMillis - epochMillis) / 1000);
        if (secs < 60) return "now";
        long mins = secs / 60;
        if (mins < 60) return mins + "m";
        long hours = mins / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }

    private static List<ScoreEvent> forType(List<ScoreEvent> events, String type) {
        List<ScoreEvent> out = new ArrayList<>();
        for (ScoreEvent event : events) if (type.equals(event.scoreType)) out.add(event);
        return out;
    }

    private static List<ScoreEvent> forPirate(List<ScoreEvent> events, String pirate) {
        List<ScoreEvent> out = new ArrayList<>();
        for (ScoreEvent event : events) if (pirate.equals(event.pirateName)) out.add(event);
        return out;
    }

    private static List<ScoreEvent> sortedByScoreDesc(List<ScoreEvent> events) {
        List<ScoreEvent> copy = new ArrayList<>(events);
        copy.sort((a, b) -> Integer.compare(Stats.scoreValue(b), Stats.scoreValue(a)));
        return copy;
    }

    private static Map<String, List<ScoreEvent>> groupByType(List<ScoreEvent> events) {
        Map<String, List<ScoreEvent>> map = new LinkedHashMap<>();
        for (ScoreEvent event : events) {
            map.computeIfAbsent(event.scoreType, k -> new ArrayList<>()).add(event);
        }
        return map;
    }

    private static Map<String, List<ScoreEvent>> groupByPirate(List<ScoreEvent> events) {
        Map<String, List<ScoreEvent>> map = new LinkedHashMap<>();
        for (ScoreEvent event : events) {
            map.computeIfAbsent(event.pirateName, k -> new ArrayList<>()).add(event);
        }
        return map;
    }

    private static List<String> orderTypes(java.util.Set<String> present) {
        List<String> ordered = new ArrayList<>();
        for (String key : ScoreTypes.keys()) if (present.contains(key)) ordered.add(key);
        for (String key : present) if (!ordered.contains(key)) ordered.add(key);
        return ordered;
    }

    private static List<String> bonusKeys(String type, List<ScoreEvent> events) {
        ScoreTypes.ScoreType t = ScoreTypes.byKey(type);
        if (t != null) return t.bonusOrder;
        TreeSet<String> keys = new TreeSet<>();
        for (ScoreEvent event : forType(events, type)) keys.addAll(event.bonusCounts.keySet());
        return new ArrayList<>(keys);
    }

    private static String typesLabel(List<ScoreEvent> scans) {
        java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
        for (ScoreEvent event : scans) types.add(label(event.scoreType));
        return String.join(", ", types);
    }

    private static long latest(List<ScoreEvent> scans) {
        long max = 0;
        for (ScoreEvent event : scans) max = Math.max(max, epochMillis(event.occurredAt));
        return max;
    }

    static Instant parseInstant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    static long epochMillis(String iso) {
        Instant instant = parseInstant(iso);
        return instant == null ? 0 : instant.toEpochMilli();
    }

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(List.of(cells));
    }

    private DashboardModel() {
    }
}

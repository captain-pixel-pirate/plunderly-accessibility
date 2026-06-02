package plunderlyaccessibility.duty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Chat-ready text renderers for duty-report scores.
 *
 */
public final class CopyText {
    public static final int GAME_CHAT_LIMIT = 240;

    /** Single personal scan: full (default) or compact, with optional average line. */
    public static String singleScan(ScoreEvent scan, List<ScoreEvent> sameTypeScans,
                                    boolean compact, boolean includeAverage, boolean includeBonuses) {
        String type = scan.scoreType;
        boolean lair = Stats.isLairType(type);
        List<String> keys = bonusKeys(type, List.of(scan));
        String headline = lair ? Stats.signed(Stats.lairScore(scan)) : String.valueOf(Stats.rawTotal(scan));

        StringBuilder sb = new StringBuilder();
        if (compact) {
            sb.append(headline);
            if (includeBonuses && !keys.isEmpty()) {
                List<String> counts = new ArrayList<>();
                for (String key : keys) counts.add(String.valueOf(scan.bonusCounts.getOrDefault(key, 0)));
                sb.append(" (").append(String.join(", ", counts)).append(')');
            }
            if (includeAverage) {
                sb.append(" avg ").append(averageValue(type, sameTypeScans, lair))
                        .append(" / ").append(sameTypeScans.size());
            }
            return sb.toString();
        }
        sb.append(headline).append(" total ").append(noun(type));
        if (includeBonuses) {
            List<String> parts = new ArrayList<>();
            for (String key : keys) {
                int value = scan.bonusCounts.getOrDefault(key, 0);
                if (value != 0) parts.add(value + " " + DashboardModel.prettyBonus(key) + " bonus");
            }
            if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(')');
        }
        if (includeAverage) {
            sb.append('\n').append(lair ? "Average lair score: " : "Average total: ")
                    .append(averageValue(type, sameTypeScans, lair))
                    .append(" over ").append(sameTypeScans.size()).append(" scans");
        }
        return sb.toString();
    }

    /** Latest team report batch: grouped by score type, pirates sorted by score desc. */
    public static String teamReport(List<ScoreEvent> latestBatch, List<ScoreEvent> averageScope,
                                    boolean includeAverages, boolean includeBonuses, boolean namedBonuses) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<ScoreEvent>> byType = groupByType(latestBatch);
        boolean firstGroup = true;
        for (String type : orderTypes(byType.keySet())) {
            if (!firstGroup) sb.append('\n');
            firstGroup = false;
            boolean lair = Stats.isLairType(type);
            sb.append(DashboardModel.label(type)).append('\n');
            List<ScoreEvent> rows = new ArrayList<>(byType.get(type));
            rows.sort((a, b) -> Integer.compare(Stats.scoreValue(b), Stats.scoreValue(a)));
            List<String> keys = bonusKeys(type, rows);
            for (ScoreEvent event : rows) {
                String headline = lair ? Stats.signed(Stats.lairScore(event))
                        : String.valueOf(Stats.rawTotal(event));
                sb.append("  ").append(event.pirateName).append(": ").append(headline);
                if (includeBonuses && !keys.isEmpty()) {
                    // Keep zero slots so positional bonus families remain clear.
                    List<String> parts = new ArrayList<>();
                    for (String key : keys) {
                        int count = event.bonusCounts.getOrDefault(key, 0);
                        if (namedBonuses) {
                            parts.add(count + " " + DashboardModel.prettyBonus(key));
                        } else {
                            parts.add(String.valueOf(count));
                        }
                    }
                    if (!parts.isEmpty()) sb.append(" (").append(String.join(", ", parts)).append(')');
                }
                if (includeAverages) {
                    List<ScoreEvent> theirs = forPirateType(averageScope, event.pirateName, type);
                    sb.append(" [avg ").append(averageValue(type, theirs, lair))
                            .append(" / ").append(theirs.size()).append(']');
                }
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Split copy-ready text into chunks that fit the game's chat paste limit.
     * Lines are kept intact whenever possible; an over-long single line is split
     * at word boundaries and, as a final fallback, by character count.
     */
    public static List<String> splitForChat(String text, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        String normalized = text == null ? "" : text.replace("\r\n", "\n")
                .replace('\r', '\n').stripTrailing();
        if (normalized.length() <= limit) return List.of(normalized);

        List<String> chunks = new ArrayList<>();
        String[] lines = normalized.split("\n", -1);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (shouldStartNewChunkBeforeHeading(current, lines, i, limit)) {
                flush(chunks, current);
            }
            for (String piece : splitLine(line, limit)) {
                appendLine(chunks, current, piece, limit);
            }
        }
        flush(chunks, current);
        return chunks.isEmpty() ? List.of("") : chunks;
    }

    // Formatting helpers shared by the copy variants.

    private static boolean shouldStartNewChunkBeforeHeading(StringBuilder current, String[] lines,
                                                            int index, int limit) {
        if (current.isEmpty() || index + 1 >= lines.length) return false;
        String heading = lines[index];
        String firstRow = lines[index + 1];
        if (heading.isBlank() || firstRow.isBlank() || !firstRow.startsWith(" ")) return false;
        if (heading.startsWith(" ")) return false;
        int pairLength = heading.length() + 1 + firstRow.length();
        if (pairLength > limit) return false;
        return current.length() + 1 + pairLength > limit;
    }

    private static List<String> splitLine(String line, int limit) {
        if (line.length() <= limit) return List.of(line);
        List<String> pieces = new ArrayList<>();
        String remaining = line;
        while (remaining.length() > limit) {
            int split = lastWhitespaceAtOrBefore(remaining, limit);
            if (split <= 0) split = limit;
            String piece = remaining.substring(0, split).stripTrailing();
            if (!piece.isEmpty()) pieces.add(piece);
            remaining = remaining.substring(split).stripLeading();
        }
        if (!remaining.isEmpty()) pieces.add(remaining);
        return pieces;
    }

    private static int lastWhitespaceAtOrBefore(String text, int limit) {
        for (int i = Math.min(limit, text.length() - 1); i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private static void appendLine(List<String> chunks, StringBuilder current,
                                   String line, int limit) {
        if (line.isEmpty() && current.isEmpty()) return;
        int nextLength = current.isEmpty() ? line.length() : current.length() + 1 + line.length();
        if (nextLength > limit) {
            flush(chunks, current);
            if (line.isEmpty()) return;
        }
        if (!current.isEmpty()) current.append('\n');
        current.append(line);
    }

    private static void flush(List<String> chunks, StringBuilder current) {
        if (current.isEmpty()) return;
        chunks.add(current.toString());
        current.setLength(0);
    }

    private static String averageValue(String type, List<ScoreEvent> scans, boolean lair) {
        Stats.CategorySummary s = Stats.summarize(type, scans);
        return lair ? Stats.signedAvg(s.lairAverage) : Stats.round1(s.aggregateAverage);
    }

    private static String noun(String type) {
        ScoreTypes.ScoreType t = ScoreTypes.byKey(type);
        return t != null ? t.categoryNoun : "points";
    }

    private static List<String> bonusKeys(String type, List<ScoreEvent> events) {
        ScoreTypes.ScoreType t = ScoreTypes.byKey(type);
        if (t != null) return t.bonusOrder;
        TreeSet<String> keys = new TreeSet<>();
        for (ScoreEvent event : events) keys.addAll(event.bonusCounts.keySet());
        return new ArrayList<>(keys);
    }

    private static Map<String, List<ScoreEvent>> groupByType(List<ScoreEvent> events) {
        Map<String, List<ScoreEvent>> map = new LinkedHashMap<>();
        for (ScoreEvent event : events) map.computeIfAbsent(event.scoreType, k -> new ArrayList<>()).add(event);
        return map;
    }

    private static List<String> orderTypes(Set<String> present) {
        List<String> out = new ArrayList<>();
        for (String key : ScoreTypes.keys()) if (present.contains(key)) out.add(key);
        for (String key : present) if (!out.contains(key)) out.add(key);
        return out;
    }

    private static List<ScoreEvent> forPirateType(List<ScoreEvent> events, String pirate, String type) {
        List<ScoreEvent> out = new ArrayList<>();
        for (ScoreEvent event : events) {
            if (pirate.equals(event.pirateName) && type.equals(event.scoreType)) out.add(event);
        }
        return out;
    }

    private CopyText() {
    }
}

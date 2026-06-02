package plunderlyaccessibility.duty;

import plunderlyaccessibility.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * On-disk duty-report history.
 *
 * Each scan appends a batch of {@link ScoreEvent}s to {@code duty-history.json}.
 * The store deduplicates exact re-reads by fingerprinting batch content rather
 * than relying on report IDs, which are generated fresh for every scan.
 */
public final class HistoryStore {

    private static final int MAX_EVENTS = 5000;

    private final Path file;
    private final List<ScoreEvent> events = new ArrayList<>();
    private final Set<String> batchSignatures = new HashSet<>();

    public HistoryStore(Path dir) {
        this.file = dir.resolve("duty-history.json");
        load();
    }

    public Path file() {
        return file;
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized List<ScoreEvent> all() {
        return new ArrayList<>(events);
    }

    /**
     * Add a scanned batch, skipping exact re-reads. Returns the number of events
     * actually added (0 if the batch duplicates a stored one).
     */
    public synchronized int addBatch(List<ScoreEvent> batch) {
        if (batch.isEmpty()) return 0;
        if (!batchSignatures.add(batchSignature(batch))) return 0;
        events.addAll(batch);
        trim();
        save();
        return batch.size();
    }

    public synchronized void clear() {
        events.clear();
        batchSignatures.clear();
        save();
    }

    /** Delete one scan by id. Returns true if it was present. */
    public synchronized boolean deleteById(String id) {
        boolean removed = events.removeIf(event -> id.equals(event.id));
        if (removed) {
            rebuildSignatures();
            save();
        }
        return removed;
    }

    /** Delete every scan for a pirate (exact canonical name). Returns the count removed. */
    public synchronized int deletePirate(String pirate) {
        int before = events.size();
        events.removeIf(event -> pirate.equals(event.pirateName));
        int removed = before - events.size();
        if (removed > 0) {
            rebuildSignatures();
            save();
        }
        return removed;
    }

    /** Merge events from an external history JSON file, batch-deduped. */
    public synchronized int importFrom(Path source) throws IOException {
        List<ScoreEvent> incoming = readEvents(Files.readString(source, StandardCharsets.UTF_8));
        int added = 0;
        for (List<ScoreEvent> batch : groupByReport(incoming)) {
            if (batchSignatures.add(batchSignature(batch))) {
                events.addAll(batch);
                added += batch.size();
            }
        }
        if (added > 0) {
            trim();
            save();
        }
        return added;
    }

    /** Write the current history to an arbitrary path (export). */
    public synchronized void exportTo(Path target) throws IOException {
        Files.writeString(target, toJson(events), StandardCharsets.UTF_8);
    }

    // Persistence.

    private void load() {
        try {
            if (!Files.exists(file)) return;
            events.addAll(readEvents(Files.readString(file, StandardCharsets.UTF_8)));
            // One-time prune of legacy unmapped rows. New scans never persist
            // unmapped rows, so this only cleans up old junk left by earlier
            // versions; rewrite the file if anything was removed.
            boolean pruned = events.removeIf(HistoryStore::isUnmapped);
            rebuildSignatures();
            if (pruned) save();
        } catch (Exception e) {
            // A damaged history file should not prevent the companion from opening.
            events.clear();
            batchSignatures.clear();
        }
    }

    private static boolean isUnmapped(ScoreEvent event) {
        return event.unmapped
                || event.scoreType == null
                || event.scoreType.isBlank()
                || ScoreTypes.UNMAPPED.equals(event.scoreType);
    }

    private void save() {
        try {
            Files.writeString(file, toJson(events), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void trim() {
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        rebuildSignatures();
    }

    private void rebuildSignatures() {
        batchSignatures.clear();
        for (List<ScoreEvent> batch : groupByReport(events)) {
            batchSignatures.add(batchSignature(batch));
        }
    }

    // Deduplication identity.

    private static List<List<ScoreEvent>> groupByReport(List<ScoreEvent> source) {
        Map<String, List<ScoreEvent>> byReport = new LinkedHashMap<>();
        for (ScoreEvent event : source) {
            byReport.computeIfAbsent(event.reportId, key -> new ArrayList<>()).add(event);
        }
        return new ArrayList<>(byReport.values());
    }

    private static String batchSignature(List<ScoreEvent> batch) {
        List<String> rows = new ArrayList<>();
        for (ScoreEvent event : batch) rows.add(rowSignature(event));
        Collections.sort(rows);
        return String.join("\n", rows);
    }

    private static String rowSignature(ScoreEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append(event.pirateName).append('|')
                .append(event.rawDuty).append('|')
                .append(event.scoreType).append('|')
                .append(event.rating).append('|')
                .append(event.performance).append('|');
        for (Map.Entry<String, Integer> bonus : new TreeMap<>(event.bonusCounts).entrySet()) {
            sb.append(bonus.getKey()).append('=').append(bonus.getValue()).append(',');
        }
        return sb.toString();
    }

    // JSON serialization.

    private static String toJson(List<ScoreEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n\"version\": 1,\n\"events\": [\n");
        for (int i = 0; i < events.size(); i++) {
            writeEvent(sb, events.get(i));
            sb.append(i + 1 < events.size() ? ",\n" : "\n");
        }
        sb.append("]\n}\n");
        return sb.toString();
    }

    private static void writeEvent(StringBuilder sb, ScoreEvent event) {
        sb.append('{');
        strField(sb, "id", event.id);
        strField(sb, "occurredAt", event.occurredAt);
        strField(sb, "reportId", event.reportId);
        numField(sb, "reportIndex", event.reportIndex);
        numField(sb, "reportCount", event.reportCount);
        strField(sb, "pirateName", event.pirateName);
        strField(sb, "rating", event.rating);
        numField(sb, "performance", event.performance);
        boolField(sb, "isUser", event.isUser);
        strField(sb, "activityCategory", event.activityCategory);
        strField(sb, "scoreType", event.scoreType);
        boolField(sb, "unmapped", event.unmapped);
        strField(sb, "rawDuty", event.rawDuty);
        Json.str(sb, "bonusCounts");
        sb.append(":{");
        boolean first = true;
        for (Map.Entry<String, Integer> bonus : event.bonusCounts.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Json.str(sb, bonus.getKey());
            sb.append(':').append(bonus.getValue());
        }
        sb.append("}}");
    }

    private static void strField(StringBuilder sb, String key, String value) {
        Json.str(sb, key);
        sb.append(':');
        Json.str(sb, value == null ? "" : value);
        sb.append(',');
    }

    private static void numField(StringBuilder sb, String key, int value) {
        Json.str(sb, key);
        sb.append(':').append(value).append(',');
    }

    private static void boolField(StringBuilder sb, String key, boolean value) {
        Json.str(sb, key);
        sb.append(':').append(value).append(',');
    }

    private static List<ScoreEvent> readEvents(String json) {
        Object root = Json.parse(json);
        List<?> list = null;
        if (root instanceof Map<?, ?> obj && obj.get("events") instanceof List<?> evs) {
            list = evs;
        } else if (root instanceof List<?> bare) {
            list = bare; // tolerate a bare array
        }
        List<ScoreEvent> out = new ArrayList<>();
        if (list != null) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) out.add(fromMap(map));
            }
        }
        return out;
    }

    private static ScoreEvent fromMap(Map<?, ?> map) {
        ScoreEvent event = new ScoreEvent();
        event.id = str(map, "id");
        event.occurredAt = str(map, "occurredAt");
        event.reportId = str(map, "reportId");
        event.reportIndex = intOf(map, "reportIndex");
        event.reportCount = intOf(map, "reportCount");
        event.pirateName = str(map, "pirateName");
        event.rating = str(map, "rating");
        // Migration: old files have no "performance" key — back-fill it from the
        // stored word (or leave it unknown if the word isn't recognized).
        event.performance = map.containsKey("performance")
                ? intOf(map, "performance")
                : Performance.ordinal(event.rating);
        event.isUser = boolOf(map, "isUser");
        event.activityCategory = str(map, "activityCategory");
        event.scoreType = str(map, "scoreType");
        event.unmapped = boolOf(map, "unmapped");
        event.rawDuty = str(map, "rawDuty");
        if (map.get("bonusCounts") instanceof Map<?, ?> counts) {
            for (Map.Entry<?, ?> bonus : counts.entrySet()) {
                event.bonusCounts.put(String.valueOf(bonus.getKey()), toInt(bonus.getValue()));
            }
        }
        return event;
    }

    private static String str(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static int intOf(Map<?, ?> map, String key) {
        return toInt(map.get(key));
    }

    private static boolean boolOf(Map<?, ?> map, String key) {
        return map.get(key) instanceof Boolean b && b;
    }

    private static int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}

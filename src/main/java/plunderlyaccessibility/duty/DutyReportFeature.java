package plunderlyaccessibility.duty;

import plunderlyaccessibility.CompanionApp;
import plunderlyaccessibility.CompanionFeature;
import plunderlyaccessibility.FeatureView;
import plunderlyaccessibility.RuntimeConfig;

import javax.accessibility.AccessibleContext;
import javax.swing.Icon;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Window;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Companion feature that reads Puzzle Pirates duty reports.
 *
 * It locates the live DutyReportView in the game window, extracts each pirate's
 * station, rating, and bonus panels, normalizes that into score events, writes a
 * readable report, and appends durable history for the dashboard.
 *
 * File triggers are supported for hands-free use: {@code read-duty-report},
 * {@code read-now}, and {@code dump-now} all request a scan.
 */
public class DutyReportFeature implements CompanionFeature {

    private static final String DUTY_REPORT_VIEW =
            "com.threerings.yohoho.sea.vessel.client.DutyReportView";
    private static final String DUTY_HEADER =
            "com.threerings.yohoho.sea.vessel.client.DutyReportView$DutyHeader";
    private static final String ENTRY_VIEW =
            "com.threerings.yohoho.sea.vessel.client.DutyReportView$EntryView";

    private static final String NO_BONUS_DATA_MESSAGE =
            "No duty bonuses detected — nothing saved. (Looks like a plain pillage "
            + "with no maneuver / cannon / chest puzzle.)";

    private final Path report;
    private final Path triggerMain;
    private final Path triggerShort;
    private final Path triggerLegacy;
    private final Path triggerClear;
    private final HistoryStore history;
    private final RuntimeConfig config;
    private DashboardPanel dashboard;

    public DutyReportFeature(Path outDir, RuntimeConfig config) {
        this.report = outDir.resolve("duty-report.txt");
        this.triggerMain = outDir.resolve("read-duty-report");
        this.triggerShort = outDir.resolve("read-now");
        this.triggerLegacy = outDir.resolve("dump-now");
        this.triggerClear = outDir.resolve("clear-duty-history");
        this.history = new HistoryStore(outDir);
        this.config = config;
    }

    /** The embedded dashboard is this feature's companion view. */
    @Override
    public FeatureView view() {
        if (dashboard == null) dashboard = new DashboardPanel(history);
        return dashboard;
    }

    @Override
    public String displayName() {
        return "Duty Report";
    }

    @Override
    public String slug() {
        return "duty-report";
    }

    @Override
    public String initialText(Path outputDir, Path debugDir) {
        return "Duty Report loaded.\n\nOutput directory:\n" + outputDir +
                "\n\nDebug tree directory:\n" + debugDir +
                "\n\nOpen a duty report in-game, then press Scan Duty Report.";
    }

    @Override
    public boolean hasPrimaryAction() {
        return true;
    }

    @Override
    public String primaryActionLabel() {
        return "Scan Duty Report";
    }

    @Override
    public String primaryActionWorkingText() {
        return "Reading duty report...";
    }

    @Override
    public String toString() {
        return displayName();
    }

    @Override
    public void runPrimaryAction(CompanionApp app, Window window) {
        readNow(app, window);
    }

    public Path preferredTrigger() {
        return triggerMain;
    }

    public String triggerSummary() {
        return triggerMain + "  (aliases: " + triggerShort + ", " + triggerLegacy + ")";
    }

    public Path findTrigger() {
        if (Files.exists(triggerMain)) return triggerMain;
        if (Files.exists(triggerShort)) return triggerShort;
        if (Files.exists(triggerLegacy)) return triggerLegacy;
        return null;
    }

    public void deleteTriggers(CompanionApp app) {
        for (Path p : List.of(triggerMain, triggerShort, triggerLegacy)) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ioe) {
                app.log("could not delete trigger " + p + ": " + ioe);
            }
        }
    }

    public void readNow(CompanionApp app, Window window) {
        try {
            if (window == null) window = app.findPuzzlePiratesWindow();
            Report scanned = buildReport(window);

            // A report with no bonus puzzle (or no scored rows left after
            // dropping unmapped) is not worth saving. Skip both history and the
            // text-report write, and tell the user why. Genuine scan problems
            // (no window/view/rows) still fall through to the normal path.
            if (isBlank(scanned.problem) && (!scanned.hasBonusData || scanned.events.isEmpty())) {
                app.showCompanion(this, NO_BONUS_DATA_MESSAGE);
                return;
            }

            String text = format(scanned);
            boolean wroteTextReport = writeTextReport(app, text);
            String note = persist(app, scanned);
            app.showCompanion(this, text + savedMessage(wroteTextReport, note));
            // After a successful scan, land on copy-ready team report text.
            boolean hasReport = isBlank(scanned.problem) && !scanned.events.isEmpty();
            if (hasReport && dashboard != null) {
                EventQueue.invokeLater(dashboard::afterSuccessfulScan);
            }
        } catch (Throwable th) {
            String text = errorReport(th);
            app.log("read failed: " + th);
            writeTextReport(app, text);
            app.showCompanion(this, text);
        }
    }

    public boolean writeTextReport(CompanionApp app, String text) {
        if (!config.textReportsEnabled()) return false;
        try {
            Files.writeString(report, text, StandardCharsets.UTF_8);
            app.log("wrote report: " + report);
            return true;
        } catch (IOException ioe) {
            app.log("could not write report " + report + ": " + ioe);
            return false;
        }
    }

    private String savedMessage(boolean wroteTextReport, String historyNote) {
        if (wroteTextReport) {
            return "\n\nSaved to:\n" + report + historyNote;
        }
        if (historyNote == null || historyNote.isEmpty()) {
            return "";
        }
        return historyNote;
    }

    // Append the scan to history, skipping exact re-reads, and return a short
    // note for the companion display.
    private String persist(CompanionApp app, Report scanned) {
        if (!isBlank(scanned.problem) || scanned.events.isEmpty()) return "";
        int added = history.addBatch(new ArrayList<>(scanned.events));
        // showCompanion routes this scan through the dashboard, which refreshes itself.
        if (added > 0) {
            app.log("history: added " + added + "; total " + history.size());
            return "\n\nAdded " + added + " events to history (" + history.size() + " total).";
        }
        app.log("history: duplicate report; total " + history.size());
        return "\n\nDuplicate of a saved report — history unchanged (" + history.size() + " total).";
    }

    // File-triggered history management actions.
    public void processSideTriggers(CompanionApp app) {
        try {
            if (Files.deleteIfExists(triggerClear)) {
                history.clear();
                app.log("history cleared: " + history.file());
                app.showCompanion(this, "Duty history cleared.\n\n0 events.\n\nFile: " + history.file());
            }
        } catch (IOException ioe) {
            app.log("clear-history failed: " + ioe);
        }
    }

    private Report buildReport(Window window) throws Exception {
        final Report[] holder = new Report[1];
        Runnable job = () -> holder[0] = scan(window);
        if (EventQueue.isDispatchThread()) {
            job.run();
        } else {
            EventQueue.invokeAndWait(job);
        }
        return holder[0];
    }

    private Report scan(Window window) {
        Report report = new Report();
        report.generatedAt = stamp();
        report.window = describeWindow(window);
        if (window == null) {
            report.problem = "No Puzzle Pirates window was found. Launch/log in first, then open the duty report.";
            return report;
        }

        List<Component> views = new ArrayList<>();
        findComponents(window, DUTY_REPORT_VIEW, views);
        report.dutyReportViews = views.size();
        if (views.isEmpty()) {
            report.problem = "No DutyReportView is currently visible. Open a duty report in-game and trigger again.";
            return report;
        }

        List<Row> best = List.of();
        Component bestView = null;
        for (Component view : views) {
            List<Row> rows = scanDutyReportView(view);
            if (rows.size() > best.size()) {
                best = rows;
                bestView = view;
            }
        }
        List<Row> rows = new ArrayList<>();
        for (Row row : best) {
            if (isPlayerPirateName(row.name)) rows.add(row);
        }

        // Rows are transient parse artifacts; score events are the durable model
        // used by history, dashboard calculations, and copy text.
        String reportId = UUID.randomUUID().toString();
        String occurredAt = isoStamp();
        for (int i = 0; i < rows.size(); i++) {
            report.events.add(toEvent(rows.get(i), reportId, occurredAt, i, rows.size()));
        }

        if (report.events.isEmpty()) {
            report.problem = "Found DutyReportView but did not find any one-word pirate names.";
            return report;
        }

        // Whether the report contains any real bonus panel — computed before
        // unmapped rows are dropped. Drives the "nothing saved" decision.
        for (ScoreEvent event : report.events) {
            if (!event.bonuses.isEmpty()) {
                report.hasBonusData = true;
                break;
            }
        }

        // Classify zero-bonus rows from related rows first, then from the
        // activity/station mapping where it is unambiguous.
        backfillScoreTypes(report);
        report.activityCategory = detectActivity(report);
        stationBackfill(report);

        // Persist only scored rows: drop anything still unmapped so "Unmapped"
        // never enters history or the dashboard.
        report.events.removeIf(event -> event.unmapped);

        // Mark the local pirate and copy the report activity onto every event.
        report.localPirate = localPirateName(window);
        for (ScoreEvent event : report.events) {
            event.activityCategory = report.activityCategory;
            event.isUser = !isBlank(report.localPirate)
                    && event.pirateName.equalsIgnoreCase(report.localPirate);
        }

        // Cross-check / fallback: the view retains the local pirate's integer
        // rating (may be null). Use it only to fill a rating we couldn't read.
        crossCheckSelfPerformance(bestView, report);

        // Learning (performance 6) is the special new-pirate tier: the pirate
        // earns no bonus and has no real rank, so a Learning row is never a valid
        // scored candidate. Drop it regardless of any station backfill (run last,
        // since the self cross-check above can be what reveals the 6).
        report.events.removeIf(event -> Performance.isLearning(event.performance));
        return report;
    }

    // The DutyReportView keeps only _self (the local pirate's report) with an
    // exact integer performance. Use it as a fallback for the local pirate when
    // the per-row label was unreadable; never override a good label read.
    private void crossCheckSelfPerformance(Component view, Report report) {
        if (view == null) return;
        Object self = fieldValue(view, "_self");
        if (self == null) return;
        Object perf = fieldValue(self, "performance");
        if (!(perf instanceof Number number)) return;
        int performance = number.intValue();
        if (performance < 0) return;
        String selfName = cleanText(String.valueOf(fieldValue(self, "name")));
        for (ScoreEvent event : report.events) {
            boolean isLocal = event.isUser
                    || (!isBlank(selfName) && event.pirateName.equalsIgnoreCase(selfName));
            if (isLocal && event.performance < 0) {
                event.performance = performance;
                event.rating = Performance.word(performance);
            }
        }
    }

    private ScoreEvent toEvent(Row row, String reportId, String occurredAt, int index, int count) {
        ScoreEvent event = new ScoreEvent();
        event.reportId = reportId;
        event.reportIndex = index;
        event.reportCount = count;
        event.id = reportId + ":" + index;
        event.occurredAt = occurredAt;
        event.pirateName = row.name;
        // The integer is the durable source of truth; the word is derived. An
        // unreadable/unrecognized rating stays unknown — we never guess.
        event.performance = Performance.ordinal(row.ratingText);
        event.rating = event.performance >= 0
                ? Performance.word(event.performance)
                : Performance.UNKNOWN_WORD;
        event.isUser = false; // filled after the local pirate is known
        event.rawDuty = row.duty;
        event.bonuses.addAll(row.bonuses);
        normalize(event);
        return event;
    }

    // Derive the normalized score type and named bonus counts from raw bonus
    // panels. The first recognized panel classifies the row.
    private void normalize(ScoreEvent event) {
        String scoreType = ScoreTypes.UNMAPPED;
        RawBonus primary = null;
        for (RawBonus bonus : event.bonuses) {
            String resolved = ScoreTypes.fromBonusPanel(bonus.panelClass, bonus.typeKey);
            if (!ScoreTypes.UNMAPPED.equals(resolved)) {
                scoreType = resolved;
                primary = bonus;
                break;
            }
        }
        event.scoreType = scoreType;
        event.unmapped = ScoreTypes.UNMAPPED.equals(scoreType);
        if (primary != null) {
            event.bonusCounts.putAll(ScoreTypes.namedCounts(scoreType, primary.counts));
        }
    }

    // Identify the logged-in pirate. Environment overrides help testing; the
    // normal path derives the name from the game window title.
    private String localPirateName(Window window) {
        String override = System.getenv("YPP_PIRATE");
        if (isBlank(override)) override = System.getenv("PROBE_PIRATE");
        if (!isBlank(override)) return cleanText(override);
        return pirateFromTitle(windowTitle(window));
    }

    private static String windowTitle(Window window) {
        if (window instanceof Frame frame) {
            String title = cleanText(frame.getTitle());
            if (!isBlank(title)) return title;
        }
        return cleanText(accessibleName(window));
    }

    static String pirateFromTitle(String title) {
        String clean = cleanText(title);
        int dash = clean.indexOf(" - ");
        if (dash < 0) return "";
        String rest = clean.substring(dash + 3).trim();
        int onThe = rest.indexOf(" on the ");
        if (onThe >= 0) rest = rest.substring(0, onThe).trim();
        return rest;
    }

    private String detectActivity(Report report) {
        Set<String> present = new LinkedHashSet<>();
        for (ScoreEvent event : report.events) {
            if (!event.unmapped) present.add(event.scoreType);
        }
        return Activities.detect(present);
    }

    // Classify remaining zero-bonus rows using activity-aware station rules.
    // Ambiguous stations and unknown activities stay visibly unmapped.
    private void stationBackfill(Report report) {
        for (ScoreEvent event : report.events) {
            if (!event.unmapped) continue;
            String inferred = Activities.stationScoreType(report.activityCategory, event.rawDuty);
            if (isBlank(inferred)) continue;
            event.scoreType = inferred;
            event.unmapped = false;
            event.bonusCounts.putAll(ScoreTypes.namedCounts(inferred, new int[0]));
        }
    }

    // A zero-bonus row has no bonus panel. If another row under the same duty
    // header has a classified bonus panel, propagate that score type with zeroed
    // bonus counts.
    private void backfillScoreTypes(Report report) {
        Map<String, String> dutyToType = new LinkedHashMap<>();
        for (ScoreEvent event : report.events) {
            if (!event.unmapped) dutyToType.putIfAbsent(event.rawDuty, event.scoreType);
        }
        for (ScoreEvent event : report.events) {
            if (!event.unmapped) continue;
            String inferred = dutyToType.get(event.rawDuty);
            if (inferred == null) continue;
            event.scoreType = inferred;
            event.unmapped = false;
            event.bonusCounts.putAll(ScoreTypes.namedCounts(inferred, new int[0]));
        }
    }

    private boolean isPlayerPirateName(String name) {
        return !isBlank(name) && !name.matches(".*\\s+.*");
    }

    private void findComponents(Component component, String className, List<Component> out) {
        if (component == null) return;
        if (component.getClass().getName().equals(className)) out.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                findComponents(child, className, out);
            }
        }
    }

    private List<Row> scanDutyReportView(Component view) {
        List<Row> rows = new ArrayList<>();
        ScanState state = new ScanState();
        scanSequential(view, state, rows);
        return rows;
    }

    private void scanSequential(Component component, ScanState state, List<Row> rows) {
        if (component == null) return;
        String className = component.getClass().getName();
        if (DUTY_HEADER.equals(className)) {
            String duty = findFirstLabelText(component);
            if (!isBlank(duty)) state.currentDuty = duty;
            return;
        }
        if (ENTRY_VIEW.equals(className)) {
            rows.add(parseEntry(component, state.currentDuty));
            return;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                scanSequential(child, state, rows);
            }
        }
    }

    private Row parseEntry(Component entry, String duty) {
        Row row = new Row();
        row.duty = isBlank(duty) ? "(unknown duty)" : duty;
        row.name = findFirstLabelText(entry);
        if (isBlank(row.name)) row.name = "(unknown pirate)";

        row.ratingText = findPerformanceText(entry);
        row.bonuses.addAll(findBonuses(entry));
        return row;
    }

    private String findFirstLabelText(Component root) {
        List<JLabel> labels = new ArrayList<>();
        collectLabels(root, labels);
        for (JLabel label : labels) {
            String text = cleanText(label.getText());
            if (!isBlank(text)) return text;
            String name = cleanText(accessibleName(label));
            if (!isBlank(name)) return name;
        }
        return "";
    }

    private void collectLabels(Component component, List<JLabel> out) {
        if (component instanceof JLabel label) out.add(label);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectLabels(child, out);
            }
        }
    }

    private String findPerformanceText(Component root) {
        List<Component> all = new ArrayList<>();
        collectComponents(root, all);
        // Primary read: the rating word lives in the EntryView's YoMultiLineLabel
        // (field _label.b). Match on the simple name ending in "MultiLineLabel" —
        // YoMultiLineLabel does NOT end in ".MultiLineLabel", which was the bug
        // that forced the lossy avatar-icon fallback.
        for (Component component : all) {
            if (component.getClass().getSimpleName().endsWith("MultiLineLabel")) {
                String candidate = performanceCandidate(readMultiLineLabel(component));
                if (!isBlank(candidate)) return candidate;
            }
        }
        // Fallback deep scan for builds where the word lives elsewhere.
        for (Component component : all) {
            String text = extractPerformanceText(component, 0, new LinkedHashSet<>());
            if (!isBlank(text)) return text;
        }
        return "";
    }

    private void collectComponents(Component component, List<Component> out) {
        if (component == null) return;
        out.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectComponents(child, out);
            }
        }
    }

    private String readMultiLineLabel(Component component) {
        String name = accessibleName(component);
        if (!isBlank(name)) return name;

        Object label = fieldValue(component, "_label");
        String text = readTextCarrier(label);
        if (!isBlank(text)) return text;

        for (Field field : allFields(component.getClass())) {
            try {
                field.setAccessible(true);
                Object value = field.get(component);
                text = readTextCarrier(value);
                if (!isBlank(text)) return text;
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private String readTextCarrier(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;

        for (Method method : allMethods(value.getClass())) {
            if (method.getParameterCount() != 0 || method.getReturnType() != String.class) continue;
            String name = method.getName();
            if (!("a".equals(name) || "getText".equals(name))) continue;
            try {
                method.setAccessible(true);
                Object result = method.invoke(value);
                if (result instanceof String s && !isBlank(s)) return s;
            } catch (Throwable ignored) {
            }
        }

        for (String fieldName : List.of("b", "c", "_text", "text")) {
            Object field = fieldValue(value, fieldName);
            if (field instanceof String s && !isBlank(s)) return s;
        }
        return "";
    }

    private String extractPerformanceText(Object object, int depth, Set<Object> seen) {
        if (object == null || depth > 3 || seen.contains(object)) return "";
        seen.add(object);

        if (object instanceof String s) return performanceCandidate(s);
        if (object instanceof JLabel || object instanceof Icon) return "";

        String className = object.getClass().getName();
        if (className.startsWith("java.") || className.startsWith("javax.swing.plaf.") ||
                className.startsWith("sun.") || className.startsWith("com.sun.")) {
            return "";
        }

        String carrier = performanceCandidate(readTextCarrier(object));
        if (!isBlank(carrier)) return carrier;

        for (Field field : allFields(object.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(object);
                String text = performanceCandidate(value instanceof String s ? s : "");
                if (!isBlank(text)) return text;
                if (value != null && shouldDescendInto(value)) {
                    text = extractPerformanceText(value, depth + 1, seen);
                    if (!isBlank(text)) return text;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private boolean shouldDescendInto(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof Character ||
                value instanceof Icon) {
            return false;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.isEnum()) return false;
        String name = type.getName();
        return name.startsWith("com.samskivert.") ||
                name.startsWith("com.threerings.") ||
                name.startsWith("javax.swing.");
    }

    // Returns the cleaned text only if it is a recognized rating word or
    // m.performance{N} key (across both ladders), else "".
    private static String performanceCandidate(String text) {
        String clean = cleanText(text);
        return Performance.ordinal(clean) >= 0 ? clean : "";
    }

    private Map<String, NumericField> readNumericFields(Object object) {
        Map<String, NumericField> out = new LinkedHashMap<>();
        if (object == null) return out;

        Class<?> c = object.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            for (Field field : safeDeclaredFields(c)) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (!(type == int.class || type == short.class || type == byte.class)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value instanceof Number number) {
                        String key = c.getSimpleName() + "." + field.getName();
                        out.put(key, new NumericField(number.intValue(), type.getSimpleName()));
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private List<RawBonus> findBonuses(Component entry) {
        List<Component> all = new ArrayList<>();
        collectComponents(entry, all);
        List<RawBonus> bonuses = new ArrayList<>();
        for (Component component : all) {
            String cn = component.getClass().getName();
            if (!cn.startsWith("com.threerings.yohoho.sea.vessel.client.DutyReportView$") ||
                    !cn.endsWith("BonusPanel")) {
                continue;
            }
            RawBonus bonus = new RawBonus();
            bonus.panelClass = cn;
            bonus.typeKey = bonusTypeKey(component, cn);
            bonus.counts = bonusCounts(component);
            String details = formatBonus(component, cn);
            bonus.display = isBlank(details) ? simpleBonusName(cn) + " bonus" : details;
            bonuses.add(bonus);
        }
        return bonuses;
    }

    // The family discriminator distinguishes chest/counter panels that share a
    // class but represent different score types.
    private String bonusTypeKey(Object object, String className) {
        if (className.endsWith("$ChestBonusPanel")) {
            return chestBonusKey(fieldValue(object, "chestType"));
        }
        if (className.endsWith("$CounterBonusPanel")) {
            return counterBonusKey(fieldValue(object, "counterTypes"));
        }
        return "";
    }

    private String formatBonus(Object object, String className) {
        int[] counts = bonusCounts(object);
        if (className.endsWith("$ManeuverBonusPanel")) {
            return formatManeuverBonus(counts);
        }
        if (className.endsWith("$CannonBonusPanel")) {
            int total = sum(counts);
            return total == 1 ? "Cannon: 1 filled" : "Cannons: " + total + " filled";
        }
        if (className.endsWith("$ChestBonusPanel")) {
            return formatChestBonus(object, counts);
        }
        if (className.endsWith("$CounterBonusPanel")) {
            return formatCounterBonus(object, counts);
        }
        return formatTypedCountBonus(simpleBonusName(className), counts, "");
    }

    private int[] bonusCounts(Object object) {
        Object rows = fieldValue(object, "_rows");
        if (rows instanceof Iterable<?> iterable) {
            int[] total = null;
            for (Object row : iterable) {
                if (!(row instanceof int[] ints)) continue;
                if (total == null) total = new int[ints.length];
                if (total.length < ints.length) total = Arrays.copyOf(total, ints.length);
                for (int i = 0; i < ints.length; i++) {
                    total[i] += ints[i];
                }
            }
            return total == null ? new int[0] : total;
        }

        for (Field field : allFields(object.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(object);
                if (value instanceof int[] ints) return ints;
            } catch (Throwable ignored) {
            }
        }
        return new int[0];
    }

    private String formatManeuverBonus(int[] counts) {
        String[] names = new String[]{"Circle", "Diamond", "Plus", "Cross", "Flower"};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            String name = i < names.length ? names[i] : "Type " + i;
            parts.add(name + " x" + counts[i]);
        }
        if (parts.isEmpty()) return "Maneuvers";
        return "Maneuvers: " + String.join(", ", parts);
    }

    private String formatCounterBonus(Object object, int[] counts) {
        Object counterType = fieldValue(object, "counterTypes");
        String[][] names = counterBonusNames(counterType);
        if (names == null) {
            return formatTypedCountBonus("Counters", counts, enumDescription(counterType));
        }
        return formatNamedCountBonus(counterBonusLabel(counterType), counts, names[0], names[1]);
    }

    private String counterBonusLabel(Object counterType) {
        return switch (counterBonusKey(counterType)) {
            case "VAMPIRATE_PATCHES" -> "Vampirate patches";
            default -> "Counters";
        };
    }

    private String[][] counterBonusNames(Object counterType) {
        return switch (counterBonusKey(counterType)) {
            case "VAMPIRATE_PATCHES" -> countNames(
                    new String[]{"Creaky coffin", "Slipshod", "Vampirate proof"},
                    new String[]{"Creaky coffins", "Slipshod", "Vampirate proof"});
            default -> null;
        };
    }

    private String counterBonusKey(Object counterType) {
        String enumName = enumName(counterType);
        if ("VAMPIRATE_PATCHES".equals(enumName)) return enumName;

        String tileSetPath = enumStringField(counterType, "tileSetPath");
        return switch (tileSetPath) {
            case "vampirate_patches.png" -> "VAMPIRATE_PATCHES";
            default -> "";
        };
    }

    private String formatChestBonus(Object object, int[] counts) {
        Object chestType = fieldValue(object, "chestType");
        String label = chestBonusLabel(chestType);
        String[][] names = chestBonusNames(chestType);
        if (names == null) {
            return formatTypedCountBonus("Chests", counts, enumDescription(chestType));
        }
        return formatNamedCountBonus(label, counts, names[0], names[1]);
    }

    private String formatNamedCountBonus(String label, int[] counts, String[] singularNames, String[] pluralNames) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] <= 0) continue;
            String name;
            if (i < singularNames.length) {
                name = counts[i] == 1 ? singularNames[i] : pluralNames[i];
            } else {
                name = "type " + i;
            }
            parts.add(name + " x" + counts[i]);
        }
        if (parts.isEmpty()) return label;
        return label + ": " + String.join(", ", parts);
    }

    private String chestBonusLabel(Object chestType) {
        return switch (chestBonusKey(chestType)) {
            case "ATLANTEAN_CHESTS" -> "Atlantean chests";
            case "CURSED_CHESTS" -> "Cursed chests";
            case "BURIED_CHESTS" -> "Buried chests";
            case "HAUNTED_CHESTS" -> "Haunted chests";
            case "VAMPIRATE_CHESTS" -> "Vampirate chests";
            case "KRAKEN_CHESTS" -> "Kraken chests";
            default -> "Chests";
        };
    }

    private String[][] chestBonusNames(Object chestType) {
        return switch (chestBonusKey(chestType)) {
            case "ATLANTEAN_CHESTS" -> countNames(
                    new String[]{"Sunken box", "Ancient locker", "Antediluvian chest"},
                    new String[]{"Sunken boxes", "Ancient lockers", "Antediluvian chests"});
            case "CURSED_CHESTS" -> countNames(
                    new String[]{"Bone box", "Fetish jar", "Cursed chest"},
                    new String[]{"Bone boxes", "Fetish jars", "Cursed chests"});
            case "BURIED_CHESTS" -> countNames(
                    new String[]{"Strong box", "Ship's locker", "Treasure chest"},
                    new String[]{"Strong boxes", "Ship's lockers", "Treasure chests"});
            case "HAUNTED_CHESTS" -> countNames(
                    new String[]{"Ghostly box", "Ethereal locker", "Spectral chest"},
                    new String[]{"Ghostly boxes", "Ethereal lockers", "Spectral chests"});
            case "VAMPIRATE_CHESTS" -> countNames(
                    new String[]{"Blood box", "Nocturnal locker", "Immortal chest"},
                    new String[]{"Blood boxes", "Nocturnal lockers", "Immortal chests"});
            case "KRAKEN_CHESTS" -> countNames(
                    new String[]{"Cuttle box", "Tentacle locker", "Cephalo pod", "Kraken's egg"},
                    new String[]{"Cuttle boxes", "Tentacle lockers", "Cephalo pods", "Kraken's eggs"});
            default -> null;
        };
    }

    private String chestBonusKey(Object chestType) {
        String enumName = enumName(chestType);
        if (isKnownChestBonusKey(enumName)) return enumName;

        String tileSetPath = enumStringField(chestType, "tileSetPath");
        return switch (tileSetPath) {
            case "chests.png" -> "ATLANTEAN_CHESTS";
            case "cursed_chests.png" -> "CURSED_CHESTS";
            case "buried_chests.png" -> "BURIED_CHESTS";
            case "haunted_chests.png" -> "HAUNTED_CHESTS";
            case "vampirate_chests.png" -> "VAMPIRATE_CHESTS";
            case "kraken_chests.png" -> "KRAKEN_CHESTS";
            default -> "";
        };
    }

    private boolean isKnownChestBonusKey(String value) {
        return "ATLANTEAN_CHESTS".equals(value) ||
                "CURSED_CHESTS".equals(value) ||
                "BURIED_CHESTS".equals(value) ||
                "HAUNTED_CHESTS".equals(value) ||
                "VAMPIRATE_CHESTS".equals(value) ||
                "KRAKEN_CHESTS".equals(value);
    }

    private String[][] countNames(String[] singular, String[] plural) {
        return new String[][]{singular, plural};
    }

    private String formatTypedCountBonus(String label, int[] counts, String descriptor) {
        int total = sum(counts);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) parts.add("type " + i + " x" + counts[i]);
        }
        StringBuilder out = new StringBuilder(label);
        if (!isBlank(descriptor)) out.append(" (").append(descriptor).append(")");
        if (total > 0) {
            out.append(": ");
            out.append(parts.size() <= 1 ? String.valueOf(total) : String.join(", ", parts));
        }
        return out.toString();
    }

    private String simpleBonusName(String className) {
        String simple = className.substring(className.lastIndexOf('$') + 1).replace("BonusPanel", "");
        return isBlank(simple) ? "Bonus" : simple;
    }

    private String enumDescription(Object enumObject) {
        if (enumObject == null) return "";
        Object desc = fieldValue(enumObject, "description");
        if (desc instanceof String s && !isBlank(s)) return s;
        String name = enumName(enumObject);
        if (!isBlank(name)) return name;
        return cleanText(String.valueOf(enumObject));
    }

    private String enumName(Object enumObject) {
        if (enumObject instanceof Enum<?> e) return e.name();
        return "";
    }

    private String enumStringField(Object enumObject, String fieldName) {
        Object value = fieldValue(enumObject, fieldName);
        if (value instanceof String s && !isBlank(s)) return s;
        return cleanText(String.valueOf(enumObject));
    }

    private int sum(int[] counts) {
        int total = 0;
        for (int count : counts) total += count;
        return total;
    }

    private String format(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("Puzzle Pirates Duty Report\n");
        out.append("Generated: ").append(report.generatedAt).append('\n');
        out.append("Window: ").append(report.window).append('\n');
        if (!isBlank(report.localPirate)) {
            out.append("You: ").append(report.localPirate).append('\n');
        }
        out.append('\n');

        if (!isBlank(report.problem)) {
            out.append(report.problem).append('\n');
            return out.toString();
        }

        Map<String, List<ScoreEvent>> byDuty = new LinkedHashMap<>();
        for (ScoreEvent event : report.events) {
            byDuty.computeIfAbsent(event.rawDuty, ignored -> new ArrayList<>()).add(event);
        }
        for (Map.Entry<String, List<ScoreEvent>> entry : byDuty.entrySet()) {
            out.append(entry.getKey()).append('\n');
            for (ScoreEvent event : entry.getValue()) {
                out.append("  ").append(event.rating).append(" - ").append(event.pirateName);
                if (event.isUser) out.append(" (you)");
                if (!event.bonuses.isEmpty()) {
                    List<String> displays = new ArrayList<>();
                    for (RawBonus bonus : event.bonuses) displays.add(bonus.display);
                    out.append(" [").append(String.join("; ", displays)).append(']');
                }
                out.append('\n');
            }
            out.append('\n');
        }

        out.append("Rows: ").append(report.events.size()).append('\n');
        out.append("DutyReportView instances: ").append(report.dutyReportViews).append('\n');
        out.append("Rating source: YoMultiLineLabel word; local pirate cross-checked against _self.performance\n");

        appendNormalized(out, report);
        return out.toString();
    }

    // Include normalized score data in the text report so contributors can see
    // exactly what the scanner persisted and diagnose unmapped rows.
    private void appendNormalized(StringBuilder out, Report report) {
        if (report.events.isEmpty()) return;
        out.append("\nActivity: ").append(Activities.label(report.activityCategory));
        if (Activities.UNKNOWN.equals(report.activityCategory)) {
            out.append(" (no distinctive chests — needs manual selection)");
        }
        out.append('\n');
        out.append("Normalized score events:\n");
        for (ScoreEvent event : report.events) {
            out.append("  ").append(event.pirateName);
            if (event.isUser) out.append(" (you)");
            out.append(" — ");
            if (event.unmapped) {
                out.append("unmapped (rawDuty=\"").append(event.rawDuty).append('"');
                List<String> panels = new ArrayList<>();
                for (RawBonus bonus : event.bonuses) {
                    panels.add(simpleBonusName(bonus.panelClass)
                            + (isBlank(bonus.typeKey) ? "" : "/" + bonus.typeKey)
                            + Arrays.toString(bonus.counts));
                }
                if (!panels.isEmpty()) out.append(", panels=").append(panels);
                out.append(")\n");
                continue;
            }
            out.append(event.scoreType);
            ScoreTypes.ScoreType type = ScoreTypes.byKey(event.scoreType);
            if (type != null && !type.verified) out.append(" [unverified]");
            out.append(" — ");
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> bonus : event.bonusCounts.entrySet()) {
                parts.add(bonus.getKey() + "=" + bonus.getValue());
            }
            out.append(parts.isEmpty() ? "(no bonuses)" : String.join(", ", parts));
            out.append('\n');
        }
    }

    public static String errorReport(Throwable th) {
        return "Puzzle Pirates Duty Report\nGenerated: " + stamp() + "\n\nReader error: " + th + "\n";
    }

    @Override
    public void appendDebugDetails(Component component, StringBuilder out) {
        String className = component.getClass().getName();
        if (DUTY_REPORT_VIEW.equals(className)) {
            // Phase 0: the view retains only _self (the local pirate). Its
            // performance int is ground truth to calibrate the per-row read.
            appendSelfPerformance(component, out);
        }
        if (ENTRY_VIEW.equals(className)) {
            out.append(" extractedPerformance=\"").append(cleanText(findPerformanceText(component))).append("\"");
            // Phase 0: dump the raw content of the rating label so we can see
            // exactly what string is available per row (localized word vs the
            // "m.performanceN" key) and stop relying on the lossy avatar field.
            appendPerformanceLabelDump(component, out);
        }
        if (className.startsWith("com.threerings.yohoho.sea.vessel.client.DutyReportView$") &&
                className.endsWith("BonusPanel")) {
            out.append(" bonus=\"").append(formatBonus(component, className)).append("\"");
            out.append(" counts=").append(Arrays.toString(bonusCounts(component)));
        }
        if (className.equals("com.samskivert.swing.MultiLineLabel") || className.endsWith(".MultiLineLabel")) {
            out.append(" multiLineText=\"").append(cleanText(readMultiLineLabel(component))).append("\"");
            out.append(" extractedPerformance=\"")
                    .append(cleanText(extractPerformanceText(component, 0, new LinkedHashSet<>())))
                    .append("\"");
        }
    }

    // Phase 0 diagnostic (dev-only): reflect the local pirate's DutyReport and
    // print its integer performance + name. This is the rating ground truth we
    // can cross-check the per-row label extraction against.
    private void appendSelfPerformance(Component view, StringBuilder out) {
        Object self = fieldValue(view, "_self");
        if (self == null) {
            out.append(" self=null");
            return;
        }
        Object performance = fieldValue(self, "performance");
        out.append(" self.performance=").append(performance == null ? "?" : performance);
        Object name = fieldValue(self, "name");
        if (name != null) {
            out.append(" self.name=\"").append(cleanText(String.valueOf(name))).append('"');
        }
        // Surface any other small int fields on the report in case the rating
        // lives under a different name in this build.
        out.append(" self.ints=").append(readNumericFields(self).keySet());
    }

    // Phase 0 diagnostic (dev-only): exhaustively dump non-empty String / char[]
    // values reachable from each MultiLineLabel inside an EntryView, with field
    // paths, so we can locate where the rating text (word or key) is stored.
    private void appendPerformanceLabelDump(Component entry, StringBuilder out) {
        List<Component> all = new ArrayList<>();
        collectComponents(entry, all);
        int idx = 0;
        for (Component component : all) {
            String cn = component.getClass().getName();
            if (!cn.endsWith("MultiLineLabel")) continue;
            out.append(" perfLabel").append(idx++).append("={class=").append(cn);
            out.append(" accName=\"").append(cleanText(accessibleName(component))).append('"');
            List<String> strings = new ArrayList<>();
            collectStringValues(component, "", strings, 0, new LinkedHashSet<>());
            out.append(" strings=").append(strings).append('}');
        }
    }

    // Walk fields of game/samskivert objects collecting String and char[] values.
    // Capped in depth/breadth so a debug line stays bounded.
    private void collectStringValues(Object object, String path, List<String> out, int depth, Set<Object> seen) {
        if (object == null || depth > 4 || out.size() >= 40 || !seen.add(object)) return;
        for (Field field : allFields(object.getClass())) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(object);
                if (value == null) continue;
                String fieldPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
                if (value instanceof String s) {
                    if (!isBlank(s)) out.add(fieldPath + "=\"" + cleanText(s) + "\"");
                } else if (value instanceof char[] chars) {
                    String s = new String(chars);
                    if (!isBlank(s)) out.add(fieldPath + "(char[])=\"" + cleanText(s) + "\"");
                } else if (shouldDescendForStrings(value)) {
                    collectStringValues(value, fieldPath, out, depth + 1, seen);
                }
            } catch (Throwable ignored) {
            }
            if (out.size() >= 40) return;
        }
    }

    // Only descend into game/samskivert objects (skip JDK/Swing internals so the
    // dump stays focused on where the label actually keeps its text).
    private boolean shouldDescendForStrings(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof Icon) {
            return false;
        }
        String name = value.getClass().getName();
        return name.startsWith("com.samskivert.") || name.startsWith("com.threerings.");
    }

    private static String cleanText(String text) {
        if (text == null) return "";
        return text.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Object fieldValue(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                Field field = c.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = type;
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            fields.addAll(Arrays.asList(safeDeclaredFields(c)));
            c = c.getSuperclass();
        }
        return fields;
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Class<?> c = type;
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                methods.addAll(Arrays.asList(c.getDeclaredMethods()));
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return methods;
    }

    private static Field[] safeDeclaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private static String accessibleName(Component component) {
        try {
            AccessibleContext ac = component.getAccessibleContext();
            return ac == null ? "" : ac.getAccessibleName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String describeWindow(Window window) {
        if (window == null) return "none";
        String name = cleanText(accessibleName(window));
        String title = (window instanceof Frame f) ? cleanText(f.getTitle()) : "";
        if (!isBlank(title) && !title.equals(name)) {
            return window.getClass().getName() + " title=\"" + title + "\" name=\"" + name + "\"";
        }
        return window.getClass().getName() + " name=\"" + name + "\" visible=" + window.isVisible();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static String isoStamp() {
        return Instant.now().toString();
    }

    private static class ScanState {
        String currentDuty = "";
    }

    private static class Report {
        String generatedAt = "";
        String window = "";
        String problem = "";
        int dutyReportViews;
        String activityCategory = "";
        String localPirate = "";
        // True if any parsed row carried a real bonus panel, computed before
        // unmapped rows are dropped. A report with no bonus data is not saved.
        boolean hasBonusData;
        final List<ScoreEvent> events = new ArrayList<>();
    }

    /** Transient parse artifact for one entry row before it becomes a ScoreEvent. */
    private static class Row {
        String duty = "";
        String name = "";
        String ratingText = "";
        final List<RawBonus> bonuses = new ArrayList<>();
    }

    private static class NumericField {
        final int value;
        final String type;

        NumericField(int value, String type) {
            this.value = value;
            this.type = type;
        }
    }
}

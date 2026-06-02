package plunderlyaccessibility;

import plunderlyaccessibility.duty.DutyReportFeature;

import javax.accessibility.AccessibleContext;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Main companion window and background watcher.
 *
 */
public class CompanionApp {
    public static final String VERSION = "1.0.0";

    private final Path outDir = resolveDir();
    private final Path logFile = outDir.resolve("duty-reader.log");
    private final RuntimeConfig config = new RuntimeConfig();
    private final DutyReportFeature dutyReportFeature = new DutyReportFeature(outDir, config);
    private final CompanionFeature[] features = new CompanionFeature[]{
            dutyReportFeature
    };
    // DebugTreeWriter is a dev-only reverse-engineering tool that is not bundled in
    // public builds. Load it reflectively so the companion compiles and runs without it;
    // it is null when the class is absent (the "Save Debug Tree" button stays hidden).
    private final Object debugTreeWriter = newDebugTreeWriter();

    private JFrame companion;
    private JLabel titleLabel;
    private JTextArea companionText;
    private final java.util.Map<CompanionFeature, JToggleButton> featureButtons = new java.util.LinkedHashMap<>();
    private final java.util.Map<CompanionFeature, FeatureView> featureViews = new java.util.LinkedHashMap<>();
    private static final String TEXT_CARD = "__text__";
    private final java.awt.CardLayout centerLayout = new java.awt.CardLayout();
    private JPanel centerCards;
    private JButton primaryAction;
    private CompanionFeature selectedFeature = dutyReportFeature;
    private boolean announcedWindow;

    public CompanionApp() {
        ensureOutputDirs();
        log("=== COMPANION APP LOADED ===");
        log("version         = " + VERSION);
        log("java.version    = " + System.getProperty("java.version"));
        log("java.home       = " + System.getProperty("java.home"));
        log("assistive_tech  = " + System.getProperty("javax.accessibility.assistive_technologies"));
        log("output dir      = " + outDir);
        log("dev mode        = " + config.devMode());
        if (config.debugFeaturesEnabled()) {
            for (CompanionFeature feature : features) {
                log("debug dir [" + feature.displayName() + "] = " + featureDebugDir(feature));
            }
        }
        log("To read: touch " + dutyReportFeature.triggerSummary());
        showCompanion(dutyReportFeature.initialText(outDir, featureDebugDir(dutyReportFeature), config.debugFeaturesEnabled()));

        Thread t = new Thread(this::run, "ypp-companion");
        t.setDaemon(true);
        t.start();
    }

    private static Path resolveDir() {
        String env = System.getenv("PLUNDERLY_OUTPUT_DIR");
        if (env == null || env.isEmpty()) env = System.getenv("PROBE_DIR");
        if (env == null || env.isEmpty()) env = System.getProperty("plunderly.outputDir");
        if (env != null && !env.isEmpty()) return Path.of(env);

        Path repo = repoDirFromClassPath();
        if (repo != null) return repo.resolve("output");
        return appDataDir();
    }

    /**
     * Per-user data directory for a deployed companion, following each
     * platform's convention: {@code ~/Library/Application Support/Plunderly} on
     * macOS, {@code %APPDATA%\Plunderly} on Windows.
     */
    private static Path appDataDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Plunderly");
        }
        if (os.contains("win")) {
            String roaming = System.getenv("APPDATA");
            Path base = (roaming != null && !roaming.isEmpty())
                    ? Path.of(roaming)
                    : Path.of(home, "AppData", "Roaming");
            return base.resolve("Plunderly");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = (xdg != null && !xdg.isEmpty())
                ? Path.of(xdg)
                : Path.of(home, ".local", "share");
        return base.resolve("Plunderly");
    }

    private static Path repoDirFromClassPath() {
        try {
            // Locate our class file even when the companion is appended to the
            // boot class path, where CodeSource is commonly unavailable.
            String selfRes = CompanionApp.class.getSimpleName() + ".class";
            URL url = CompanionApp.class.getResource(selfRes);
            if (url == null || !"file".equals(url.getProtocol())) return null;
            // Source checkouts use build/ as the compiled class root.
            Path classFile = Path.of(url.toURI());
            Path buildDir = classFile.getParent();
            if (buildDir != null && buildDir.endsWith("build")) {
                return buildDir.getParent();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void ensureOutputDirs() {
        try {
            Files.createDirectories(outDir);
            if (config.debugFeaturesEnabled()) {
                for (CompanionFeature feature : features) {
                    Files.createDirectories(featureDebugDir(feature));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Path featureDebugDir(CompanionFeature feature) {
        return outDir.resolve(feature.slug());
    }

    private Path latestTree(CompanionFeature feature) {
        return featureDebugDir(feature).resolve("debug-tree-latest.txt");
    }

    private void run() {
        log("watcher thread started; waiting for a Puzzle Pirates window...");
        while (true) {
            try {
                Window window = findPuzzlePiratesWindow();
                if (window != null && !announcedWindow) {
                    log("FOUND target window: " + describeWindow(window));
                    log("Open the duty report in-game, then run: touch " + dutyReportFeature.preferredTrigger());
                    announcedWindow = true;
                }

                Path trigger = dutyReportFeature.findTrigger();
                if (trigger != null) {
                    log("trigger seen: " + trigger);
                    selectedFeature = dutyReportFeature;
                    dutyReportFeature.readNow(this, window);
                    dutyReportFeature.deleteTriggers(this);
                }
                dutyReportFeature.processSideTriggers(this);

                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable th) {
                log("watcher error: " + th);
                if (config.textReportsEnabled()) {
                    dutyReportFeature.writeTextReport(this, DutyReportFeature.errorReport(th));
                }
                dutyReportFeature.deleteTriggers(this);
            }
        }
    }

    public Window findPuzzlePiratesWindow() {
        for (Window w : Window.getWindows()) {
            if (!w.isDisplayable()) continue;
            String name = accessibleName(w);
            String title = (w instanceof Frame f) ? f.getTitle() : "";
            if (startsPuzzlePirates(name) || startsPuzzlePirates(title)) {
                return w;
            }
        }
        return null;
    }

    private static boolean startsPuzzlePirates(String value) {
        return value != null && value.startsWith("Puzzle Pirates");
    }

    private static Object newDebugTreeWriter() {
        try {
            return Class.forName("plunderlyaccessibility.debug.DebugTreeWriter")
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable absent) {
            return null;
        }
    }

    private void saveDebugTreeNow(Window window, CompanionFeature feature) {
        try {
            if (debugTreeWriter == null) {
                String text = "Debug tree writer is not bundled in this build.";
                showCompanion(feature, text);
                log(text);
                return;
            }
            if (window == null) window = findPuzzlePiratesWindow();
            if (window == null) {
                String text = "No Puzzle Pirates window was found. Launch/log in first.";
                showCompanion(feature, text);
                log(text);
                return;
            }

            Files.createDirectories(featureDebugDir(feature));
            Path target = featureDebugDir(feature).resolve("debug-tree-" + fileStamp() + ".txt");
            Path latest = latestTree(feature);
            debugTreeWriter.getClass()
                    .getMethod("write", Window.class, Path.class, CompanionFeature.class)
                    .invoke(debugTreeWriter, window, target, feature);
            Files.copy(target, latest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String text = "Saved " + feature.displayName() + " debug tree:\n" + target +
                    "\n\nLatest copy:\n" + latest;
            showCompanion(feature, text);
            log(text.replace('\n', ' '));
        } catch (Throwable th) {
            String text = "Debug tree failed: " + th;
            showCompanion(feature, text);
            log(text);
        }
    }

    public void showCompanion(CompanionFeature feature, String text) {
        selectedFeature = feature;
        SwingUtilities.invokeLater(() -> {
            ensureUiBuilt();
            refreshFeatureActions();
            applyText(feature, text);
            showCardFor(feature);
            companion.setVisible(true);
            companion.toFront();
        });
    }

    public void showCompanion(String text) {
        showCompanion(selectedFeature, text);
    }

    // Features with custom dashboards receive updates directly; simpler
    // features share the plain text card.
    private void applyText(CompanionFeature feature, String text) {
        FeatureView view = featureViews.get(feature);
        if (view != null) {
            view.update(text);
        } else {
            companionText.setText(text);
            companionText.setCaretPosition(0);
        }
    }

    private void showCardFor(CompanionFeature feature) {
        centerLayout.show(centerCards, featureViews.containsKey(feature) ? feature.slug() : TEXT_CARD);
    }

    private void ensureUiBuilt() {
        if (companion == null) {
                companion = new JFrame("Plunderly Accessibility Companion v" + VERSION);
                companion.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
                companionText = new JTextArea();
                companionText.setEditable(false);
                companionText.setFont(Theme.mono(15));
                companionText.setBackground(Theme.PAPER);
                companionText.setForeground(Theme.TEXT_PRIMARY);
                companionText.setCaretColor(Theme.PRIMARY);
                companionText.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

                // The game process has an active renderer, so heavyweight popup
                // controls can miss clicks. Toggle buttons keep feature switching
                // predictable in the companion window.
                ButtonGroup featureGroup = new ButtonGroup();
                for (CompanionFeature feature : features) {
                    JToggleButton tb = new Theme.AccentToggle(feature.displayName(),
                            feature == selectedFeature);
                    tb.addActionListener(event -> selectFeature(feature));
                    featureGroup.add(tb);
                    featureButtons.put(feature, tb);
                }

                primaryAction = Theme.flatButton("", Theme.SUCCESS, Theme.ON_ACCENT);
                primaryAction.addActionListener(event -> {
                    CompanionFeature feature = selectedFeature;
                    applyText(feature, feature.primaryActionWorkingText());
                    Thread t = new Thread(
                            () -> feature.runPrimaryAction(this, findPuzzlePiratesWindow()),
                            "ypp-" + feature.slug() + "-primary-button");
                    t.setDaemon(true);
                    t.start();
                });

                // Apply the Plunderly dark palette (mirrored from the web app)
                // ourselves rather than leaning on the host look-and-feel, so the
                // companion looks the same on every machine and inside the game.
                JPanel root = new JPanel(new BorderLayout(8, 8));
                Theme.asCanvas(root);
                root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

                // A slim top toolbar: the active feature's name on the left, the
                // one prominent primary action (plus dev tools) on the right,
                // with a divider rule separating it from the content below.
                JPanel header = new JPanel();
                header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
                Theme.asCanvas(header);
                header.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER_SOFT),
                        BorderFactory.createEmptyBorder(0, 2, 8, 2)));

                JPanel toolbar = new JPanel(new BorderLayout(8, 0));
                Theme.asCanvas(toolbar);
                titleLabel = new JLabel(selectedFeature.displayName());
                titleLabel.setForeground(Theme.TEXT_PRIMARY);
                titleLabel.setFont(Theme.heading(16));
                toolbar.add(titleLabel, BorderLayout.WEST);

                JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
                Theme.asCanvas(actionRow);
                if (config.debugFeaturesEnabled() && debugTreeWriter != null) {
                    JButton debug = Theme.flatButton("Save Debug Tree");
                    debug.addActionListener(event -> {
                        CompanionFeature feature = selectedFeature;
                        applyText(feature, feature.debugWorkingText());
                        Thread t = new Thread(
                                () -> saveDebugTreeNow(findPuzzlePiratesWindow(), feature),
                                "ypp-" + feature.slug() + "-debug-button");
                        t.setDaemon(true);
                        t.start();
                    });
                    actionRow.add(debug);
                }
                JButton openFolder = Theme.flatButton("Open Data Folder");
                openFolder.addActionListener(event -> openDataFolder());
                actionRow.add(openFolder);
                actionRow.add(primaryAction);
                toolbar.add(actionRow, BorderLayout.EAST);

                JPanel featureRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
                Theme.asCanvas(featureRow);
                featureRow.add(headerLabel("Feature:"));
                for (JToggleButton tb : featureButtons.values()) {
                    featureRow.add(tb);
                }

                header.add(toolbar);
                // Only show the feature switcher when there's an actual choice
                // to make. With a single feature the row is just noise; it
                // reappears automatically once a second feature is registered
                // in features[].
                if (features.length > 1) {
                    header.add(featureRow);
                }

                // Center is a card stack: a shared text view plus any feature
                // views supplied by registered features.
                centerCards = new JPanel(centerLayout);
                Theme.asCanvas(centerCards);
                JScrollPane textScroll = new JScrollPane(companionText);
                textScroll.setBorder(BorderFactory.createLineBorder(Theme.DIVIDER_SOFT));
                textScroll.getViewport().setBackground(Theme.PAPER);
                centerCards.add(textScroll, TEXT_CARD);
                for (CompanionFeature feature : features) {
                    FeatureView view = feature.view();
                    if (view != null) {
                        featureViews.put(feature, view);
                        centerCards.add(view.component(), feature.slug());
                    }
                }

                root.add(header, BorderLayout.NORTH);
                root.add(centerCards, BorderLayout.CENTER);
                companion.setContentPane(root);
                companion.setSize(new Dimension(960, 700));
                companion.setMinimumSize(new Dimension(0, 0));
                companion.setLocationByPlatform(true);
                refreshFeatureActions();
        }
    }

    // Reveal the data/output folder in the OS file browser. History, reports,
    // and logs all live here; this spares users from hand-navigating the hidden
    // app-data dirs (~/Library/... on macOS, %APPDATA%\... on Windows). Runs off
    // the EDT since the game's active renderer can stall blocking UI calls.
    private void openDataFolder() {
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(outDir);
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(outDir.toFile());
                } else {
                    log("Open Data Folder: Desktop API unavailable; folder is " + outDir);
                }
            } catch (Throwable ex) {
                log("Open Data Folder failed: " + ex);
            }
        }, "ypp-open-data-folder");
        t.setDaemon(true);
        t.start();
    }

    private void selectFeature(CompanionFeature feature) {
        selectedFeature = feature;
        refreshFeatureActions();
        FeatureView view = featureViews.get(feature);
        if (view != null) {
            view.refresh();
        } else {
            companionText.setText(feature.initialText(outDir, featureDebugDir(feature), config.debugFeaturesEnabled()));
            companionText.setCaretPosition(0);
        }
        showCardFor(feature);
    }

    private void refreshFeatureActions() {
        JToggleButton selectedButton = featureButtons.get(selectedFeature);
        if (selectedButton != null && !selectedButton.isSelected()) {
            selectedButton.setSelected(true);
        }
        if (titleLabel != null) {
            titleLabel.setText(selectedFeature.displayName());
        }
        if (primaryAction != null) {
            primaryAction.setText(selectedFeature.primaryActionLabel());
            primaryAction.setVisible(selectedFeature.hasPrimaryAction());
            primaryAction.getParent().revalidate();
        }
    }

    /** Row label rendered in the theme's secondary text colour. */
    private static JLabel headerLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT_SECONDARY);
        label.setFont(Theme.ui(Font.BOLD, 13));
        return label;
    }

    private static String accessibleName(Window window) {
        try {
            AccessibleContext ac = window.getAccessibleContext();
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

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static String fileStamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    public synchronized void log(String msg) {
        // Diagnostic log is a dev/test-only artifact (like the debug tree and
        // plaintext report). End users get nothing written to their data dir.
        if (!config.devMode()) {
            return;
        }
        String line = "[" + stamp() + "] " + msg;
        try (FileWriter fw = new FileWriter(logFile.toFile(), true)) {
            fw.write(line);
            fw.write(System.lineSeparator());
        } catch (IOException ignored) {
        }
    }
}

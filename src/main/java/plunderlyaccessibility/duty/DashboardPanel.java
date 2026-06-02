package plunderlyaccessibility.duty;

import plunderlyaccessibility.FeatureView;
import plunderlyaccessibility.Theme;
import plunderlyaccessibility.Theme.AccentToggle;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Swing dashboard over saved duty-report history.
 *
 * The dashboard owns controls, table rendering, clipboard actions, deletion
 * prompts, and saved preferences. Filtering, grouping, rankings, and summary
 * calculations live in {@link DashboardModel}.
 */
public final class DashboardPanel extends JPanel implements FeatureView {

    private static final String CARD_TABLE = "table";
    private static final String CARD_TEXT = "scanText";

    private final HistoryStore history;
    private final Path prefsFile;

    private DashboardModel.Mode mode = DashboardModel.Mode.MY;
    private DashboardModel.DateRange range = DashboardModel.DateRange.ALL;
    private String typeFilter;       // null = all types
    private boolean byStation;       // maneuvers-only: per-station breakdown view
    private boolean byRating;        // filtered type: per-rating breakdown view
    private String drillStation;     // station drilled into for station×rating (maneuvers)
    private String drillPirate;      // crew mode: pirate drilled into for their individual scans
    private String selectedPirate;   // crew mode selection

    private final JToggleButton myButton = new Theme.SegmentToggle("My Scans", Theme.Seg.FIRST, true);
    private final JToggleButton crewButton = new Theme.SegmentToggle("Crew Scans", Theme.Seg.LAST, false);
    private final JToggleButton allDates = new Theme.SegmentToggle("All", Theme.Seg.FIRST, true);
    private final JToggleButton weekDates = new Theme.SegmentToggle("Week", Theme.Seg.MIDDLE, false);
    private final JToggleButton todayDates = new Theme.SegmentToggle("Today", Theme.Seg.LAST, false);
    // Bonus counts is the only option on by default; the rest are opt-in.
    private final JToggleButton avgToggle = new AccentToggle("Averages", false);
    private final JToggleButton bonusCountsToggle = new AccentToggle("Bonus counts", true);
    private final JToggleButton bonusNamesToggle = new AccentToggle("Bonus names", false);

    private final JButton copyReport = Theme.flatButton("Copy Latest Report", Theme.PRIMARY, Theme.ON_ACCENT);
    private final JButton copyPrevious = Theme.flatButton("Copy Previous");
    private final JButton copyNext = Theme.flatButton("Copy Next");
    private final JLabel copyPartLabel = label(" ");

    private String selectedEventId;  // selected per-scan row (My + type filter)
    private final JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JPanel summaryPanel = new JPanel(new BorderLayout());
    private final JTextArea summary = new JTextArea(4, 80);
    private final JTextArea scanText = new JTextArea();
    private final JTable table = new JTable();
    private final JTable detailTable = new JTable();
    private final JPanel detailPanel = new JPanel(new BorderLayout());
    private final JLabel detailTitle = label(" ");
    private final JLabel status = new JLabel(" ");
    private final CardLayout centerLayout = new CardLayout();
    private final JPanel center = new JPanel(centerLayout);
    private String currentCard = CARD_TABLE;

    // Remembers the active copy generator so option toggles can update the
    // displayed clipboard text immediately.
    private Supplier<String> lastCopy;
    private boolean lastCopySplitForChat;
    private List<String> copyChunks = List.of();
    private int copyChunkIndex;

    public DashboardPanel(HistoryStore history) {
        super(new BorderLayout(6, 6));
        this.history = history;
        this.prefsFile = history.file().getParent().resolve("dashboard-prefs.properties");
        build();
        loadPrefs();
        refresh();
    }

    // FeatureView integration.

    @Override
    public Component component() {
        return this;
    }

    @Override
    public void update(String text) {
        if (text != null) {
            // Show raw scan feedback immediately. Successful scans later replace
            // this with copy-ready report text; failures stay visible for the user.
            lastCopy = null;
            clearCopyChunks();
            setScanText(text);
            showCenter(CARD_TEXT);
        }
        refresh();
    }

    private void showCenter(String card) {
        currentCard = card;
        centerLayout.show(center, card);
    }

    /**
     * Called by the feature after a successful scan: copy the latest team report
     * to the clipboard and show it, so a scan lands the user straight on the
     * "Copy Latest Report" output.
     */
    public void afterSuccessfulScan() {
        copyLatestReport();
    }

    @Override
    public void refresh() {
        List<ScoreEvent> events = DashboardModel.filter(history.all(), mode, range, Instant.now());
        rebuildFilterButtons(events);
        DashboardModel.TableData data = DashboardModel.buildTable(events, mode, typeFilter, byStation, byRating);
        applyTable(data);
        setSummary(DashboardModel.summary(events, mode, typeFilter, selectedPirate));
        status.setText("  " + events.size() + " scans  •  history total " + history.size());
        updateDetail();
    }

    public void setScanText(String text) {
        scanText.setText(text);
        scanText.setCaretPosition(0);
    }

    /** Set the summary text and grow the band to fit it (capped, then scrolls). */
    private void setSummary(String text) {
        summary.setText(text);
        summary.setCaretPosition(0);
        adjustSummaryHeight();
    }

    /**
     * Size the summary band to its content so selecting a crew pirate (which
     * appends a personal block) reveals the extra lines without scrolling. A
     * floor keeps it stable for short text; a ceiling stops a long crew summary
     * from crowding out the table, falling back to the scrollbar past that.
     */
    private void adjustSummaryHeight() {
        int lines = Math.max(1, summary.getLineCount());
        int lineHeight = summary.getFontMetrics(summary.getFont()).getHeight();
        int chrome = 40; // titled border + text-area insets
        int height = Math.max(120, Math.min(360, lines * lineHeight + chrome));
        summaryPanel.setPreferredSize(new Dimension(10, height));
        summaryPanel.revalidate();
        revalidate();
    }

    // UI construction.

    private void build() {
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(myButton);
        modeGroup.add(crewButton);
        myButton.addActionListener(e -> setMode(DashboardModel.Mode.MY));
        crewButton.addActionListener(e -> setMode(DashboardModel.Mode.CREW));

        ButtonGroup dateGroup = new ButtonGroup();
        dateGroup.add(allDates);
        dateGroup.add(weekDates);
        dateGroup.add(todayDates);
        allDates.addActionListener(e -> setRange(DashboardModel.DateRange.ALL));
        weekDates.addActionListener(e -> setRange(DashboardModel.DateRange.WEEK));
        todayDates.addActionListener(e -> setRange(DashboardModel.DateRange.TODAY));

        JButton refresh = Theme.flatButton("Refresh");
        refresh.addActionListener(e -> refresh());
        JButton clear = Theme.flatButton("Clear History");
        clear.addActionListener(e -> {
            if (!confirm("Delete the entire saved history? This cannot be undone.")) return;
            history.clear();
            selectedPirate = null;
            typeFilter = null;
            refresh();
        });

        // Copy options are preferences and also update any visible copied text.
        avgToggle.addActionListener(e -> { savePrefs(); reRenderCopy(); });
        bonusCountsToggle.addActionListener(e -> {
            refreshCopyOptionState();
            savePrefs();
            reRenderCopy();
        });
        bonusNamesToggle.addActionListener(e -> { savePrefs(); reRenderCopy(); });

        // One toolbar of navigation: the two choose-one groups (view + date
        // range) render as connected segmented controls, separated from each
        // other and from the plain actions by thin vertical rules.
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        Theme.asCanvas(controls);

        JPanel controlRow = row();
        controlRow.add(label("View"));
        controlRow.add(Theme.segmented(myButton, crewButton));
        controlRow.add(Theme.vDivider(22));
        controlRow.add(label("Dates"));
        controlRow.add(Theme.segmented(allDates, weekDates, todayDates));
        controlRow.add(Theme.vDivider(22));
        controlRow.add(refresh);
        controlRow.add(clear);
        controls.add(controlRow);

        Theme.asCanvas(filterPanel);
        filterPanel.setBorder(titledBorder("Score type"));
        controls.add(filterPanel);

        // Copy actions + display options live in a bottom bar, next to the
        // output they govern rather than stacked above the table.
        copyReport.addActionListener(e -> copyLatestReport());
        copyPrevious.addActionListener(e -> copyRelativeChunk(-1));
        copyNext.addActionListener(e -> copyRelativeChunk(1));
        updateCopyChunkControls();

        JPanel actionBar = row();
        actionBar.add(copyReport);
        actionBar.add(copyPrevious);
        actionBar.add(copyNext);
        actionBar.add(copyPartLabel);
        actionBar.add(Theme.vDivider(22));
        actionBar.add(label("Options"));
        actionBar.add(avgToggle);
        actionBar.add(bonusCountsToggle);
        actionBar.add(bonusNamesToggle);

        summary.setEditable(false);
        summary.setFont(Theme.mono(12));
        summary.setForeground(Theme.TEXT_PRIMARY);
        summary.setBackground(Theme.PAPER);
        summary.setCaretColor(Theme.PRIMARY);
        summary.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        scanText.setEditable(false);
        scanText.setFont(Theme.mono(12));
        scanText.setForeground(Theme.TEXT_PRIMARY);
        scanText.setBackground(Theme.PAPER);
        scanText.setCaretColor(Theme.PRIMARY);

        styleTable(table);
        styleTable(detailTable);
        detailTable.setAutoCreateRowSorter(true);
        detailTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSelect();
        });
        // Double-click a personal scan row to copy that row's compact text.
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewCol = table.columnAtPoint(e.getPoint());
                if (viewRow >= 0 && viewCol >= 0
                        && table.getModel() instanceof DataModel model
                        && table.convertColumnIndexToModel(viewCol) == model.deleteColumn()) {
                    deleteRow(table.convertRowIndexToModel(viewRow));
                    return;
                }
                if (e.getClickCount() == 2) onDoubleClick();
            }
        });

        // The detail panel below the main table shows a station's per-rating
        // breakdown when a maneuvers station row is selected; hidden otherwise.
        JButton closeDetail = Theme.flatButton("✕ Close");
        closeDetail.addActionListener(e -> closeDrill());
        JPanel detailBar = row();
        detailBar.add(detailTitle);
        detailBar.add(closeDetail);
        JScrollPane detailScroll = darkScroll(detailTable);
        detailScroll.setPreferredSize(new Dimension(10, 160));
        detailPanel.add(detailBar, BorderLayout.NORTH);
        detailPanel.add(detailScroll, BorderLayout.CENTER);
        detailPanel.setVisible(false);
        Theme.asCanvas(detailPanel);

        JPanel tableCard = new JPanel(new BorderLayout(0, 6));
        Theme.asCanvas(tableCard);
        tableCard.add(darkScroll(table), BorderLayout.CENTER);
        tableCard.add(detailPanel, BorderLayout.SOUTH);

        Theme.asCanvas(center);
        center.add(tableCard, CARD_TABLE);

        // Copied text uses a separate card so it can be selected manually if the
        // system clipboard is unavailable.
        JPanel textCard = new JPanel(new BorderLayout());
        Theme.asCanvas(textCard);
        JPanel textBar = row();
        JButton backToTable = Theme.flatButton("← Back to Table");
        backToTable.addActionListener(e -> showCenter(CARD_TABLE));
        textBar.add(backToTable);
        textBar.add(label("Copied text (select to copy manually):"));
        textCard.add(textBar, BorderLayout.NORTH);
        textCard.add(darkScroll(scanText), BorderLayout.CENTER);
        center.add(textCard, CARD_TEXT);

        // Summary sits in a short full-width band above the table so the table
        // spans the entire window width.
        JScrollPane summaryScroll = new JScrollPane(summary);
        summaryScroll.setBorder(null);
        summaryScroll.setBackground(Theme.PAPER);
        summaryScroll.getViewport().setBackground(Theme.PAPER);
        Theme.asCanvas(summaryPanel);
        summaryPanel.setBorder(titledBorder("Summary"));
        summaryPanel.add(summaryScroll, BorderLayout.CENTER);
        adjustSummaryHeight();

        JPanel top = new JPanel(new BorderLayout());
        Theme.asCanvas(top);
        top.add(controls, BorderLayout.NORTH);
        top.add(summaryPanel, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 2));
        Theme.asCanvas(south);
        south.add(actionBar, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);

        Theme.asCanvas(this);
        status.setForeground(Theme.TEXT_SECONDARY);
        status.setFont(Theme.ui(Font.PLAIN, 12));
        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    /** Label with theme secondary text colour. */
    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Theme.TEXT_SECONDARY);
        l.setFont(Theme.ui(Font.PLAIN, 13));
        return l;
    }

    /** Titled border with theme divider/title colours. */
    private static javax.swing.border.TitledBorder titledBorder(String title) {
        return Theme.titledBorder(title);
    }

    /** Renders the trailing delete cell as a centred "✕" in the error colour. */
    private static DefaultTableCellRenderer deleteRenderer() {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.CENTER);
        r.setForeground(Theme.ERROR);
        return r;
    }

    /** A left-aligned control row painted on the dark canvas. */
    private static JPanel row() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        Theme.asCanvas(p);
        return p;
    }

    /** Scroll pane themed to the dark paper surface with a divider border. */
    private static JScrollPane darkScroll(Component view) {
        JScrollPane sp = new JScrollPane(view);
        sp.setBorder(BorderFactory.createLineBorder(Theme.DIVIDER_SOFT));
        sp.setBackground(Theme.BACKGROUND);
        sp.getViewport().setBackground(Theme.PAPER);
        return sp;
    }

    /** Apply the dark palette to a history table and its header. */
    private void styleTable(JTable t) {
        t.setBackground(Theme.PAPER);
        t.setForeground(Theme.TEXT_PRIMARY);
        t.setGridColor(Theme.DIVIDER_SOFT);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setRowHeight(22);
        t.setFont(Theme.ui(Font.PLAIN, 13));
        t.setSelectionBackground(Theme.PRIMARY);
        t.setSelectionForeground(Theme.ON_ACCENT);
        t.setFillsViewportHeight(true);
        JTableHeader header = t.getTableHeader();
        header.setReorderingAllowed(false);
        header.setDefaultRenderer(new HeaderRenderer());
    }

    /** Header cell renderer that forces the dark palette across host L&Fs. */
    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        HeaderRenderer() {
            setOpaque(true);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(Theme.ui(Font.BOLD, 13));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER_SOFT),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                       boolean focus, int row, int col) {
            super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            setBackground(Theme.PAPER_RAISED);
            setForeground(Theme.TEXT_SECONDARY);
            return this;
        }
    }

    private void setMode(DashboardModel.Mode newMode) {
        mode = newMode;
        typeFilter = null;
        byStation = false;
        byRating = false;
        drillStation = null;
        drillPirate = null;
        selectedPirate = null;
        selectedEventId = null;
        savePrefs();
        refresh();
    }

    private void setRange(DashboardModel.DateRange newRange) {
        range = newRange;
        drillPirate = null;
        savePrefs();
        refresh();
    }

    private void setTypeFilter(String type) {
        typeFilter = type;
        // Breakdown views are opted into per score-type selection.
        byStation = false;
        byRating = false;
        drillStation = null;
        drillPirate = null;
        selectedPirate = null;
        selectedEventId = null;
        savePrefs();
        refresh();
    }

    private void setByStation(boolean on) {
        byStation = on;
        drillStation = null; // leaving/entering station view closes any drill
        drillPirate = null;
        selectedPirate = null;
        selectedEventId = null;
        savePrefs();
        refresh();
    }

    private void setByRating(boolean on) {
        byRating = on;
        drillPirate = null;
        selectedPirate = null;
        selectedEventId = null;
        savePrefs();
        refresh();
    }

    private void closeDrill() {
        drillStation = null;
        drillPirate = null;
        updateDetail();
    }

    private void rebuildFilterButtons(List<ScoreEvent> events) {
        filterPanel.removeAll();
        // Rebuild the group from scratch so button selection state stays in sync
        // with the regenerated score-type filters.
        ButtonGroup filterGroup = new ButtonGroup();

        JToggleButton all = new AccentToggle("All", typeFilter == null);
        all.addActionListener(e -> setTypeFilter(null));
        filterGroup.add(all);
        filterPanel.add(all);

        for (Map.Entry<String, Integer> entry : DashboardModel.scoreTypeCounts(events).entrySet()) {
            String type = entry.getKey();
            JToggleButton button = new AccentToggle(
                    DashboardModel.label(type) + " (" + entry.getValue() + ")", type.equals(typeFilter));
            button.addActionListener(e -> setTypeFilter(type));
            filterGroup.add(button);
            filterPanel.add(button);
        }
        // Contextual breakdown toggles (independent of the score-type group):
        // maneuvers can be split by station (and drilled to station×rating);
        // any other filtered type can be split by rating.
        if ("maneuvers".equals(typeFilter)) {
            JToggleButton byStationButton = new AccentToggle("By station", byStation);
            byStationButton.addActionListener(e -> setByStation(byStationButton.isSelected()));
            filterPanel.add(byStationButton);
        } else if (typeFilter != null) {
            JToggleButton byRatingButton = new AccentToggle("By rating", byRating);
            byRatingButton.addActionListener(e -> setByRating(byRatingButton.isSelected()));
            filterPanel.add(byRatingButton);
        }
        filterPanel.revalidate();
        filterPanel.repaint();
    }

    private void applyTable(DashboardModel.TableData data) {
        // Breakdown views (station / rating) list aggregate buckets, not single
        // pirates or scans, so their rows are not individually deletable.
        boolean breakdownView = isStationView() || isRatingView();
        boolean deletable = !breakdownView
                && (mode == DashboardModel.Mode.CREW
                || (mode == DashboardModel.Mode.MY && typeFilter != null));
        populate(table, data, deletable);
    }

    /** Bind a TableData to a JTable: model, per-column renderers, delete column. */
    private void populate(JTable target, DashboardModel.TableData data, boolean deletable) {
        target.setModel(new DataModel(data, deletable));
        DefaultTableCellRenderer renderer = cellRenderer(data);
        DataModel model = (DataModel) target.getModel();
        for (int c = 0; c < target.getColumnCount(); c++) {
            TableColumn column = target.getColumnModel().getColumn(c);
            if (c == model.deleteColumn()) {
                column.setCellRenderer(deleteRenderer());
                column.setMinWidth(28);
                column.setMaxWidth(28);
            } else {
                column.setCellRenderer(renderer);
                // The leading rank column is just a short number; keep it narrow
                // so it doesn't claim width the data columns can use.
                if ("#".equals(data.columns.get(c))) {
                    column.setMinWidth(30);
                    column.setPreferredWidth(40);
                    column.setMaxWidth(48);
                }
            }
        }
    }

    /** Cell renderer formatting times, averages, ratings, and lair scores. */
    private static DefaultTableCellRenderer cellRenderer(DashboardModel.TableData data) {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                           boolean focus, int row, int col) {
                Object shown = value;
                String header = t.getColumnName(col);
                if (value instanceof Long millis) {
                    shown = DashboardModel.relativeTime(millis, System.currentTimeMillis());
                } else if ("Avg Perf".equals(header) && value instanceof Double d) {
                    // Aggregate rating: word for the rounded average, plus the
                    // raw average. The number implies the ladder (Performance).
                    shown = d < 0 ? "—"
                            : Performance.word((int) Math.round(d)) + " (" + Stats.round1(d) + ")";
                } else if (value instanceof Double d) {
                    shown = data.lairTotals && header.toLowerCase().contains("avg")
                            ? Stats.signedAvg(d) : Stats.formatAverage(d);
                } else if (value instanceof Integer i && data.lairTotals
                        && (header.equals("Lair") || header.equals("Total"))) {
                    shown = Stats.signed(i);
                }
                return super.getTableCellRendererComponent(t, shown, sel, focus, row, col);
            }
        };
    }

    private boolean isStationView() {
        return byStation && "maneuvers".equals(typeFilter);
    }

    private boolean isRatingView() {
        return byRating && typeFilter != null;
    }

    /** Show/refresh the bottom detail table: a pirate's scans (crew) or a station×rating breakdown. */
    private void updateDetail() {
        if (drillPirate != null && mode == DashboardModel.Mode.CREW) {
            showPirateDrill();
            return;
        }
        if (drillStation != null && isStationView()) {
            showStationDrill();
            return;
        }
        detailPanel.setVisible(false);
    }

    /** Crew drill: the selected pirate's individual scans, scoped to the active filters. */
    private void showPirateDrill() {
        List<ScoreEvent> scoped = DashboardModel.filter(history.all(), mode, range, Instant.now());
        DashboardModel.TableData data = DashboardModel.pirateScanRows(scoped, drillPirate, typeFilter);
        if (data.rows.isEmpty()) {
            detailPanel.setVisible(false);
            return;
        }
        populate(detailTable, data, false);
        detailTitle.setText("  " + drillPirate + " — scans");
        showDetail();
    }

    /** Maneuvers drill: the selected station's per-rating breakdown. */
    private void showStationDrill() {
        List<ScoreEvent> scoped = DashboardModel.filter(history.all(), mode, range, Instant.now());
        List<ScoreEvent> stationEvents = new ArrayList<>();
        for (ScoreEvent event : scoped) {
            if ("maneuvers".equals(event.scoreType) && drillStation.equalsIgnoreCase(event.rawDuty)) {
                stationEvents.add(event);
            }
        }
        populate(detailTable, DashboardModel.ratingBreakdown(stationEvents, "maneuvers"), false);
        detailTitle.setText("  " + drillStation + " — by rating");
        showDetail();
    }

    private void showDetail() {
        detailPanel.setVisible(true);
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private void onSelect() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        if (!(table.getModel() instanceof DataModel model)) return;
        String key = model.rowKey(modelRow);
        if (key == null) return;
        // In the maneuvers-by-station view, rows are stations: drill into the
        // selected station's per-rating breakdown (Overall has an empty key).
        if (isStationView()) {
            drillStation = key.isEmpty() ? null : key;
            updateDetail();
            return;
        }
        // The per-rating breakdown rows are rating buckets; selecting is a no-op.
        if (isRatingView()) return;
        // In the personal overview, category rows drill into that score type.
        if (mode == DashboardModel.Mode.MY && typeFilter == null) {
            setTypeFilter(key);
        } else if (mode == DashboardModel.Mode.MY) {
            selectedEventId = key; // per-scan row id used by copy/delete actions
            status.setText("  Scan selected — double-click the row to copy it.");
        } else if (mode == DashboardModel.Mode.CREW) {
            selectedPirate = key;
            List<ScoreEvent> events = DashboardModel.filter(history.all(), mode, range, Instant.now());
            setSummary(DashboardModel.summary(events, mode, typeFilter, selectedPirate));
        }
    }

    // Double-click: copy a personal scan (My), or drill into a pirate's scans (Crew).
    private void onDoubleClick() {
        if (mode == DashboardModel.Mode.MY && typeFilter != null && selectedEventId != null) {
            copySelectedScan();
        } else if (mode == DashboardModel.Mode.CREW && selectedPirate != null && !selectedPirate.isEmpty()) {
            drillPirate = selectedPirate;
            updateDetail();
        }
    }

    // Copy actions.

    private void copyLatestReport() {
        copyUsing(() -> {
            boolean mine = mode == DashboardModel.Mode.MY;
            // Personal mode copies the newest report that includes the local
            // pirate, then filters that batch down to the local pirate's rows.
            List<ScoreEvent> batch = mine ? latestBatchWithUser() : latestBatch();
            if (batch.isEmpty()) {
                status.setText(mine ? "  No saved report includes your scans."
                        : "  No saved report to copy.");
                return null;
            }
            if (mine) batch = onlyUser(batch);
            List<ScoreEvent> scope = DashboardModel.filter(history.all(),
                    mine ? DashboardModel.Mode.MY : DashboardModel.Mode.CREW, range, Instant.now());
            return CopyText.teamReport(batch, scope, avgToggle.isSelected(),
                    bonusCountsToggle.isSelected(), bonusNamesToggle.isSelected());
        }, true);
    }

    private void copySelectedScan() {
        copyUsing(() -> {
            ScoreEvent scan = selectedEventId == null ? null : findById(selectedEventId);
            if (scan == null) {
                status.setText("  Select a scan row first (My Scans + a score type).");
                return null;
            }
            List<ScoreEvent> sameType = new ArrayList<>();
            for (ScoreEvent event : DashboardModel.filter(history.all(), DashboardModel.Mode.MY, range, Instant.now())) {
                if (scan.scoreType.equals(event.scoreType)) sameType.add(event);
            }
            return CopyText.singleScan(scan, sameType, true,
                    avgToggle.isSelected(), bonusCountsToggle.isSelected());
        }, false);
    }

    // Run and remember a copy generator so options can re-render visible text.
    private void copyUsing(Supplier<String> source, boolean splitForChat) {
        lastCopy = source;
        lastCopySplitForChat = splitForChat;
        String text = source.get();
        if (text != null) doCopy(text, splitForChat);
    }

    // Re-render the displayed copy text after an option toggle.
    private void reRenderCopy() {
        if (lastCopy == null || !CARD_TEXT.equals(currentCard)) return;
        String text = lastCopy.get();
        if (text != null) doCopy(text, lastCopySplitForChat);
    }

    // Show copied text even when clipboard access fails, so it remains selectable.
    private void doCopy(String text, boolean splitForChat) {
        copyChunks = splitForChat
                ? CopyText.splitForChat(text, CopyText.GAME_CHAT_LIMIT)
                : List.of(text);
        copyChunkIndex = 0;
        setScanText(displayCopyText(text));
        showCenter(CARD_TEXT);
        copyCurrentChunk();
    }

    private void copyRelativeChunk(int delta) {
        if (copyChunks.isEmpty()) return;
        int next = Math.max(0, Math.min(copyChunks.size() - 1, copyChunkIndex + delta));
        if (next == copyChunkIndex) return;
        copyChunkIndex = next;
        showCenter(CARD_TEXT);
        copyCurrentChunk();
    }

    private void copyCurrentChunk() {
        if (copyChunks.isEmpty()) {
            clearCopyChunks();
            return;
        }
        String text = copyChunks.get(copyChunkIndex);
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            status.setText(copyChunks.size() > 1
                    ? "  Copied part " + (copyChunkIndex + 1) + "/" + copyChunks.size()
                    + ", " + text.length() + " chars."
                    : "  Copied " + text.length() + " chars to clipboard.");
        } catch (Exception ex) {
            status.setText(copyChunks.size() > 1
                    ? "  Clipboard unavailable — part " + (copyChunkIndex + 1)
                    + "/" + copyChunks.size() + " shown below."
                    : "  Clipboard unavailable — text shown below, select to copy.");
        }
        updateCopyChunkControls();
    }

    private String displayCopyText(String original) {
        if (copyChunks.size() <= 1) return original;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < copyChunks.size(); i++) {
            if (i > 0) sb.append("\n\n");
            String chunk = copyChunks.get(i);
            sb.append("Part ").append(i + 1).append('/').append(copyChunks.size())
                    .append(" (").append(chunk.length()).append(" chars)\n")
                    .append(chunk);
        }
        return sb.toString();
    }

    private void updateCopyChunkControls() {
        boolean multiPart = copyChunks.size() > 1;
        copyPrevious.setEnabled(multiPart && copyChunkIndex > 0);
        copyNext.setEnabled(multiPart && copyChunkIndex < copyChunks.size() - 1);
        copyPartLabel.setText(multiPart
                ? "Part " + (copyChunkIndex + 1) + "/" + copyChunks.size()
                : " ");
    }

    private void clearCopyChunks() {
        copyChunks = List.of();
        copyChunkIndex = 0;
        updateCopyChunkControls();
    }

    private List<ScoreEvent> latestBatch() {
        return reportFor(latestReportId(history.all(), false));
    }

    /** The latest report batch that contains one of my rows (empty if none). */
    private List<ScoreEvent> latestBatchWithUser() {
        return reportFor(latestReportId(history.all(), true));
    }

    /** Report id with the most recent timestamp; {@code onlyUser} restricts to reports including me. */
    private static String latestReportId(List<ScoreEvent> all, boolean onlyUser) {
        String latestId = null;
        long best = Long.MIN_VALUE;
        for (ScoreEvent event : all) {
            if (onlyUser && !event.isUser) continue;
            long when = DashboardModel.epochMillis(event.occurredAt);
            if (latestId == null || when > best) {
                best = when;
                latestId = event.reportId;
            }
        }
        return latestId;
    }

    private List<ScoreEvent> reportFor(String reportId) {
        List<ScoreEvent> out = new ArrayList<>();
        if (reportId == null) return out;
        for (ScoreEvent event : history.all()) {
            if (reportId.equals(event.reportId)) out.add(event);
        }
        return out;
    }

    private static List<ScoreEvent> onlyUser(List<ScoreEvent> events) {
        List<ScoreEvent> out = new ArrayList<>();
        for (ScoreEvent event : events) if (event.isUser) out.add(event);
        return out;
    }

    private ScoreEvent findById(String id) {
        for (ScoreEvent event : history.all()) if (id.equals(event.id)) return event;
        return null;
    }

    // History corrections.

    // Deletable rows get a trailing "✕": one saved scan in personal detail
    // views, or all saved scans for a pirate in crew views.
    private void deleteRow(int modelRow) {
        if (!(table.getModel() instanceof DataModel model)) return;
        String key = model.rowKey(modelRow);
        if (key == null) return;
        if (mode == DashboardModel.Mode.CREW) {
            // Removing a pirate clears multiple saved scans, so confirm first.
            if (!confirm("Delete all saved scans for " + key + "?")) return;
            int removed = history.deletePirate(key);
            if (key.equals(selectedPirate)) selectedPirate = null;
            status.setText("  Deleted " + removed + " scan(s) for " + key + ".");
            refresh();
        } else {
            // A single scan can be re-read from the game, so delete directly.
            boolean removed = history.deleteById(key);
            if (key.equals(selectedEventId)) selectedEventId = null;
            status.setText(removed ? "  Deleted scan." : "  Scan not found (already gone?).");
            refresh();
        }
    }

    /** Modal yes/no guard for destructive actions. */
    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Confirm delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    // Preferences.

    private void loadPrefs() {
        Properties prefs = new Properties();
        try (var in = Files.newInputStream(prefsFile)) {
            prefs.load(in);
        } catch (Exception e) {
            return; // no prefs yet — keep defaults
        }
        mode = "CREW".equals(prefs.getProperty("mode"))
                ? DashboardModel.Mode.CREW : DashboardModel.Mode.MY;
        myButton.setSelected(mode == DashboardModel.Mode.MY);
        crewButton.setSelected(mode == DashboardModel.Mode.CREW);

        range = switch (prefs.getProperty("range", "ALL")) {
            case "WEEK" -> DashboardModel.DateRange.WEEK;
            case "TODAY" -> DashboardModel.DateRange.TODAY;
            default -> DashboardModel.DateRange.ALL;
        };
        allDates.setSelected(range == DashboardModel.DateRange.ALL);
        weekDates.setSelected(range == DashboardModel.DateRange.WEEK);
        todayDates.setSelected(range == DashboardModel.DateRange.TODAY);

        String filter = prefs.getProperty("typeFilter", "");
        typeFilter = filter.isEmpty() ? null : filter;
        byStation = Boolean.parseBoolean(prefs.getProperty("byStation", "false"));
        byRating = Boolean.parseBoolean(prefs.getProperty("byRating", "false"));

        avgToggle.setSelected(Boolean.parseBoolean(prefs.getProperty("averages", "true")));
        bonusCountsToggle.setSelected(Boolean.parseBoolean(prefs.getProperty("bonusCounts", "true")));
        bonusNamesToggle.setSelected(Boolean.parseBoolean(
                prefs.getProperty("bonusNames", prefs.getProperty("bonusLines", "true"))));
        refreshCopyOptionState();
    }

    private void savePrefs() {
        Properties prefs = new Properties();
        prefs.setProperty("mode", mode.name());
        prefs.setProperty("range", range.name());
        prefs.setProperty("typeFilter", typeFilter == null ? "" : typeFilter);
        prefs.setProperty("byStation", String.valueOf(byStation));
        prefs.setProperty("byRating", String.valueOf(byRating));
        prefs.setProperty("averages", String.valueOf(avgToggle.isSelected()));
        prefs.setProperty("bonusCounts", String.valueOf(bonusCountsToggle.isSelected()));
        prefs.setProperty("bonusNames", String.valueOf(bonusNamesToggle.isSelected()));
        try (var out = Files.newOutputStream(prefsFile)) {
            prefs.store(out, "Duty dashboard preferences");
        } catch (Exception ignored) {
        }
    }

    private void refreshCopyOptionState() {
        bonusNamesToggle.setEnabled(bonusCountsToggle.isSelected());
    }

    /** Table model backed by {@link DashboardModel.TableData}; types drive sorting. */
    private static final class DataModel extends AbstractTableModel {
        private final DashboardModel.TableData data;
        private final boolean deletable;

        DataModel(DashboardModel.TableData data, boolean deletable) {
            this.data = data;
            this.deletable = deletable;
        }

        String rowKey(int row) {
            return row >= 0 && row < data.rowKeys.size() ? data.rowKeys.get(row) : null;
        }

        /** Model index of the trailing "✕" column, or -1 when rows aren't deletable. */
        int deleteColumn() {
            return deletable ? data.columns.size() : -1;
        }

        @Override
        public int getRowCount() {
            return data.rows.size();
        }

        @Override
        public int getColumnCount() {
            return data.columns.size() + (deletable ? 1 : 0);
        }

        @Override
        public String getColumnName(int column) {
            return column < data.columns.size() ? data.columns.get(column) : "";
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (column >= data.columns.size()) return "✕";
            return data.rows.get(row).get(column);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            if (column >= data.columns.size()) return String.class;
            for (List<Object> row : data.rows) {
                Object value = row.get(column);
                if (value != null) return value.getClass();
            }
            return Object.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}

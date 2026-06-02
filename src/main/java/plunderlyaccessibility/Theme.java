package plunderlyaccessibility;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.BorderFactory;
import javax.swing.border.TitledBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class Theme {
    private Theme() {
    }

    public static final Color PRIMARY = new Color(0x72C9E6);   // cyan accent
    public static final Color SECONDARY = new Color(0xF59A67); // warm orange

    public static final Color TEXT_PRIMARY = new Color(0xEEF8F6);
    public static final Color TEXT_SECONDARY = new Color(0xD7E8E4);
    public static final Color TEXT_DISABLED = new Color(0x91ADA6);

    public static final Color BACKGROUND = new Color(0x252D33); // window canvas
    public static final Color PAPER = new Color(0x29343B);      // raised surfaces

    public static final Color INFO = new Color(0x7AD8E5);
    public static final Color DIVIDER = new Color(0x536773);
    public static final Color DIVIDER_SOFT = new Color(0x3F515C);

    public static final Color SUCCESS = new Color(0x59C493);
    public static final Color WARNING = new Color(0xF4A947);
    public static final Color ERROR = new Color(0xD66A83);

    // A touch lighter than PAPER for selected table rows / hover surfaces.
    public static final Color PAPER_RAISED = new Color(0x33414A);
    // Dark ink for text sitting on a filled accent surface (primary buttons).
    public static final Color ON_ACCENT = new Color(0x10242B);

    /** Lighten a colour toward white by {@code amount} (0..1), for hover states. */
    private static Color lighten(Color c, float amount) {
        int r = Math.round(c.getRed() + (255 - c.getRed()) * amount);
        int g = Math.round(c.getGreen() + (255 - c.getGreen()) * amount);
        int b = Math.round(c.getBlue() + (255 - c.getBlue()) * amount);
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private static final String UI_FAMILY =
            pickFamily("Inter", "Helvetica Neue", "Segoe UI", "Arial", Font.SANS_SERIF);
    private static final String HEADING_FAMILY =
            pickFamily("Inter", "Helvetica Neue", "Arial Narrow", "Segoe UI", Font.SANS_SERIF);

    private static String pickFamily(String... preferred) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String family : preferred) {
            if (available.contains(family)) return family;
        }
        return Font.SANS_SERIF;
    }

    /** UI text font (Inter where available) at the given style/size. */
    public static Font ui(int style, int size) {
        return new Font(UI_FAMILY, style, size);
    }

    /** Heading font for emphasised labels/titles. */
    public static Font heading(int size) {
        return new Font(HEADING_FAMILY, Font.BOLD, size);
    }

    public static Font mono(int size) {
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    /** Paint {@code c} as part of the dark window canvas. */
    public static void asCanvas(Component c) {
        c.setBackground(BACKGROUND);
        c.setForeground(TEXT_PRIMARY);
        if (c instanceof javax.swing.JComponent jc) jc.setOpaque(true);
    }

    /** Paint {@code c} as a raised "paper" surface. */
    public static void asPaper(Component c) {
        c.setBackground(PAPER);
        c.setForeground(TEXT_PRIMARY);
        if (c instanceof javax.swing.JComponent jc) jc.setOpaque(true);
    }

    /** A titled border using theme divider/title colours. */
    public static TitledBorder titledBorder(String title) {
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(DIVIDER_SOFT), title);
        border.setTitleColor(TEXT_SECONDARY);
        return border;
    }

    public static JButton flatButton(String text, Color fill, Color ink) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = getBackground();
                boolean pressed = getModel().isArmed() && getModel().isPressed();
                boolean hover = getModel().isRollover();
                g2.setColor(pressed ? base : hover ? lighten(base, 0.12f) : base);
                int arc = Math.min(getHeight(), 12);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setRolloverEnabled(true);
        button.setBackground(fill);
        button.setForeground(ink);
        button.setFont(ui(Font.BOLD, 13));
        button.setBorder(BorderFactory.createEmptyBorder(5, 11, 5, 11));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    /** Secondary push button: a subtle paper-raised fill with light text. */
    public static JButton flatButton(String text) {
        return flatButton(text, PAPER_RAISED, TEXT_PRIMARY);
    }

    /**
     * Toggle button that draws its own pill-shaped fill so the selected state
     * reads consistently across host look-and-feel themes: a primary-accent
     * fill when selected, transparent otherwise. Shared by the companion's
     * feature switcher and the duty dashboard's filter controls.
     */
    public static final class AccentToggle extends JToggleButton {
        public AccentToggle(String text) {
            this(text, false);
        }

        public AccentToggle(String text, boolean selected) {
            super(text, null, selected);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFont(ui(Font.PLAIN, 13));
            setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            // Repaint and recolour text whenever selection or hover changes.
            getModel().addChangeListener(e -> updateForeground());
            updateForeground();
        }

        private void updateForeground() {
            setForeground(isSelected() ? ON_ACCENT : TEXT_PRIMARY);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = Math.min(getHeight(), 12);
            int w = getWidth();
            int h = getHeight();
            if (isSelected()) {
                // Solid accent pill marks the active choice.
                g2.setColor(PRIMARY);
                g2.fillRoundRect(0, 0, w, h, arc, arc);
            } else {
                // Unselected choices still read as clickable chips: a filled
                // pill with a divider outline, brightening on hover.
                boolean hover = getModel().isRollover();
                g2.setColor(hover ? PAPER_RAISED : PAPER);
                g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(hover ? PRIMARY : DIVIDER_SOFT);
                g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Where a segment sits within its connected group, driving its corners. */
    public enum Seg { ONLY, FIRST, MIDDLE, LAST }

    public static final class SegmentToggle extends JToggleButton {
        private final Seg pos;

        public SegmentToggle(String text, Seg pos, boolean selected) {
            super(text, null, selected);
            this.pos = pos;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFont(ui(Font.PLAIN, 13));
            setBorder(BorderFactory.createEmptyBorder(3, 12, 3, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getModel().addChangeListener(e -> updateForeground());
            updateForeground();
        }

        private void updateForeground() {
            setForeground(isSelected() ? ON_ACCENT : TEXT_PRIMARY);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int arc = Math.min(h, 14);
            boolean hover = getModel().isRollover();
            // Fill: selected slice gets the accent; others share the paper
            // surface (brightening on hover) so the capsule reads as one piece.
            g2.setColor(isSelected() ? PRIMARY : (hover ? PAPER_RAISED : PAPER));
            switch (pos) {
                case ONLY -> g2.fillRoundRect(0, 0, w, h, arc, arc);
                // Push the far corners off-canvas so only the outer edge rounds;
                // the inner edge stays square to butt against its neighbour.
                case FIRST -> g2.fillRoundRect(0, 0, w + arc, h, arc, arc);
                case LAST -> g2.fillRoundRect(-arc, 0, w + arc, h, arc, arc);
                case MIDDLE -> g2.fillRect(0, 0, w, h);
            }
            // Outline: each slice draws its share so the borders join into one
            // continuous capsule with thin dividers between slices.
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(DIVIDER_SOFT);
            switch (pos) {
                case ONLY -> g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                case FIRST -> g2.drawRoundRect(0, 0, w + arc, h - 1, arc, arc);
                case LAST -> {
                    g2.drawRoundRect(-arc, 0, w + arc - 1, h - 1, arc, arc);
                    g2.drawLine(0, 0, 0, h - 1);
                }
                case MIDDLE -> {
                    g2.drawLine(0, 0, w, 0);
                    g2.drawLine(0, h - 1, w, h - 1);
                    g2.drawLine(0, 0, 0, h - 1);
                }
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Lay segments edge-to-edge into one connected control on the dark canvas. */
    public static JPanel segmented(JToggleButton... segments) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        asCanvas(p);
        for (JToggleButton s : segments) p.add(s);
        return p;
    }

    /** A thin vertical rule for separating groups within a control strip. */
    public static Component vDivider(int height) {
        JPanel d = new JPanel();
        d.setOpaque(true);
        d.setBackground(DIVIDER_SOFT);
        Dimension dim = new Dimension(1, height);
        d.setPreferredSize(dim);
        d.setMaximumSize(dim);
        d.setMinimumSize(dim);
        return d;
    }
}

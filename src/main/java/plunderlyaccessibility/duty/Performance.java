package plunderlyaccessibility.duty;

/**
 * The duty-report rating scale: one integer 0–12 with two word "skins".
 *
 * A pirate's rating is the single integer field {@code DutyReport.performance}.
 * The displayed word is purely a function of that number — {@code 0–5} are the
 * classic ranks (Booched … Incredible), {@code 7–12} the expedition ranks
 * (Asleep … Frenetic). The ladder is therefore implied by the number; we never
 * pick it from the activity. {@code -1} means the rating could not be read.
 *
 * <p>{@code 6} is the special <b>Learning</b> tier: a new pirate who has not yet
 * earned a rank (the game renders it green and the pirate earns no bonus). It is
 * <em>not</em> a classic rank above Incredible and never a valid scored row, so
 * extraction drops Learning rows and {@link #displayRank(int)} excludes it.
 * Learning exists only on the classic ladder; there is no expedition equivalent.
 * (There is no "Ultimate" — {@code m.performance6} is "Learning", confirmed live
 * and in {@code DutyReportView$EntryView}.)
 *
 * This util owns all word↔number mapping so neither {@code ScoreTypes} nor
 * {@code Activities} needs to know about ratings. See
 * DUTY-REPORT-ENHANCEMENTS-PLAN.md §1c.
 */
public final class Performance {

    /** Sentinel for an unread / unrecognized rating. */
    public static final int UNKNOWN = -1;

    /** Display text for an unknown rating. */
    public static final String UNKNOWN_WORD = "(unknown rating)";

    private static final String KEY_PREFIX = "m.performance";

    // Indexed by performance 0–12. 0–5 classic ranks, 6 Learning (special new-
    // pirate tier, not a rank), 7–12 expedition ranks.
    private static final String[] WORDS = {
            "Booched", "Poor", "Fine", "Good", "Excellent", "Incredible", "Learning",
            "Asleep", "Lethargic", "Steady", "Brisk", "Swift", "Frenetic",
    };

    /** The special "Learning" new-pirate tier (classic-only, never a scored row). */
    public static final int LEARNING = 6;

    /** First expedition-ladder performance value. */
    public static final int EXPEDITION_FLOOR = 7;

    /** The rating word for a performance value, or {@link #UNKNOWN_WORD}. */
    public static String word(int performance) {
        if (performance < 0 || performance >= WORDS.length) return UNKNOWN_WORD;
        return WORDS[performance];
    }

    /**
     * The performance integer for a rating word or {@code m.performance{N}} key,
     * across both ladders (the two word sets are disjoint). Null/blank tolerant.
     * Returns {@link #UNKNOWN} when nothing recognizes the input.
     */
    public static int ordinal(String word) {
        if (word == null) return UNKNOWN;
        String clean = word.trim();
        if (clean.isEmpty()) return UNKNOWN;

        if (clean.regionMatches(true, 0, KEY_PREFIX, 0, KEY_PREFIX.length())) {
            try {
                int n = Integer.parseInt(clean.substring(KEY_PREFIX.length()).trim());
                return inRange(n) ? n : UNKNOWN;
            } catch (NumberFormatException nfe) {
                return UNKNOWN;
            }
        }
        for (int i = 0; i < WORDS.length; i++) {
            if (WORDS[i].equalsIgnoreCase(clean)) return i;
        }
        return UNKNOWN;
    }

    /** True for the expedition ladder (Asleep … Frenetic), i.e. {@code >= 7}. */
    public static boolean isExpedition(int performance) {
        return performance >= EXPEDITION_FLOOR;
    }

    /** True for the special "Learning" new-pirate tier ({@code == 6}). */
    public static boolean isLearning(int performance) {
        return performance == LEARNING;
    }

    /**
     * Map a rating onto a shared <b>0–5</b> display scale so classic and
     * expedition averages are visually comparable. Classic ranks {@code 0–5}
     * pass through unchanged; expedition ranks {@code 7–12} shift down to
     * {@code 0–5}. Learning ({@code 6}) and unread ({@code -1}) carry no rank and
     * return {@link #UNKNOWN}, so callers can exclude them from averages.
     */
    public static int displayRank(int performance) {
        if (performance < 0) return UNKNOWN;
        if (performance < LEARNING) return performance;              // classic 0–5
        if (performance == LEARNING) return UNKNOWN;                 // Learning: no rank
        if (isExpedition(performance) && performance < WORDS.length) {
            return performance - EXPEDITION_FLOOR;                    // expedition 7–12 -> 0–5
        }
        return UNKNOWN;
    }

    private static boolean inRange(int performance) {
        return performance >= 0 && performance < WORDS.length;
    }

    private Performance() {
    }
}

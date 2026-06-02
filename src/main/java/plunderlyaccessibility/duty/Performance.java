package plunderlyaccessibility.duty;

/**
 * The duty-report rating scale.
 *
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

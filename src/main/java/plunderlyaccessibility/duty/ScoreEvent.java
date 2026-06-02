package plunderlyaccessibility.duty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable per-pirate row from one captured duty report.
 *
 * The scanner fills raw context such as pirate, rating, and station, then adds
 * normalized score type and named bonus counts. The transient {@code bonuses}
 * list is used for the immediate text report; persisted history keeps the
 * normalized fields.
 */
public class ScoreEvent {
    public String id = "";
    public String occurredAt = "";   // ISO-8601 scan time
    public String reportId = "";
    public int reportIndex;
    public int reportCount;

    public String pirateName = "";
    public String rating = "";       // word ladder text, e.g. "Excellent"
    public int performance = -1;     // integer rating 0-12 (-1 = unknown); see Performance
    public boolean isUser;

    public String rawDuty = "";      // station label as shown by the game
    public final List<RawBonus> bonuses = new ArrayList<>();

    // Normalized score key and named bonus counts.
    public String scoreType = "";
    public boolean unmapped;
    public final Map<String, Integer> bonusCounts = new LinkedHashMap<>();

    // Activity category shared by every event in the same report.
    public String activityCategory = "";
}

package plunderlyaccessibility.duty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registry of duty-report activity categories.
 *
 * One scanned report belongs to one activity. Activity is inferred from the
 * score families present, in priority order: a distinctive chest/patch family
 * pins the exact activity; maneuvers (with no chest yet) mark a generic Sea
 * Monster Hunt; gunning/forage alone mark a regular pillage.
 */
public final class Activities {

    public static final String UNKNOWN = "unknown";

    /**
     * Maneuver stations in canonical (game) order, lowercase. These produce
     * maneuvers, but only in a Sea Monster Hunt. See plan §1b.
     */
    public static final List<String> MANEUVER_STATIONS = List.of(
            "navigating", "sailing", "rigging", "carpentry", "bilging", "patching");

    public static final class Activity {
        public final String key;
        public final String label;
        public final Set<String> scoreTypes;      // normalized score types this activity can produce
        public final Set<String> countedStations; // lowercase duty names; empty means every station counts

        Activity(String key, String label, Set<String> scoreTypes, Set<String> countedStations) {
            this.key = key;
            this.label = label;
            this.scoreTypes = Set.copyOf(scoreTypes);
            this.countedStations = Set.copyOf(countedStations);
        }

        public boolean countsAllStations() {
            return countedStations.isEmpty();
        }
    }

    private static final Map<String, Activity> BY_KEY = new LinkedHashMap<>();

    private static void register(String key, String label, Set<String> scoreTypes, Set<String> stations) {
        BY_KEY.put(key, new Activity(key, label, scoreTypes, stations));
    }

    static {
        // The detected bonus tells you the activity, which tells you the score
        // type interpretation and rating ladder. Stations are shared across
        // activities; never infer meaning from a station name alone. Maneuvers
        // are exclusive to Sea Monster Hunts (atlantis / haunted_seas / generic
        // smh). See DUTY-REPORT-ENHANCEMENTS-PLAN.md §1a.
        register("pillage", "Regular Pillage",
                Set.of("gunning", "forage"),
                Set.of("gunning", "foraging"));
        register("smh", "Sea Monster Hunt",
                Set.of("maneuvers", "gunning"),
                Set.of()); // maneuver stations + gunning + treasure haul
        register("atlantis", "Atlantis",
                Set.of("maneuvers", "gunning", "atlantis"),
                Set.of());
        register("haunted_seas", "Haunted Seas",
                Set.of("maneuvers", "gunning", "haunted_seas"),
                Set.of());
        register("cursed_isles", "Cursed Isles",
                Set.of("cursed_isles"),
                Set.of("foraging"));
        register("vampirate", "Vampire Expedition",
                Set.of("vampire_chests", "vampire_patches"),
                Set.of("carpentry", "treasure haul"));
    }

    public static Activity byKey(String key) {
        return BY_KEY.get(key);
    }

    public static String label(String key) {
        Activity activity = BY_KEY.get(key);
        return activity != null ? activity.label : "Unknown";
    }

    /**
     * Detect the activity from the set of normalized score types present in a
     * report, keyed off the distinctive chest/patch families. Returns
     * {@link #UNKNOWN} when nothing distinctive is present.
     */
    public static String detect(Set<String> scoreTypesPresent) {
        if (scoreTypesPresent == null) return UNKNOWN;
        // Priority order: the most distinctive chest/patch family wins, then
        // maneuvers (which mark a generic Sea Monster Hunt before any chest is
        // hauled), then a plain pillage. See plan §4 step 2.
        if (containsAny(scoreTypesPresent, "vampire_chests", "vampire_patches")) return "vampirate";
        if (scoreTypesPresent.contains("cursed_isles")) return "cursed_isles";
        if (scoreTypesPresent.contains("atlantis")) return "atlantis";
        if (scoreTypesPresent.contains("haunted_seas")) return "haunted_seas";
        if (scoreTypesPresent.contains("maneuvers")) return "smh";
        if (containsAny(scoreTypesPresent, "forage", "gunning")) return "pillage";
        return UNKNOWN;
    }

    private static boolean containsAny(Set<String> set, String... keys) {
        for (String key : keys) {
            if (set.contains(key)) return true;
        }
        return false;
    }

    /**
     * Best-effort score type for a duty station within a known activity.
     *
     * This fills in zero-bonus rows that have no bonus panel. Ambiguous stations
     * return an empty string so the row remains visibly unmapped.
     *
     * Stations are activity-dependent: Carpentry is a maneuver station in a Sea
     * Monster Hunt but vampire_patches in a Vampire Expedition; Foraging is
     * forage in a pillage but cursed_isles in Cursed Isles.
     */
    public static String stationScoreType(String activityKey, String rawDuty) {
        if (rawDuty == null) return "";
        String station = rawDuty.trim().toLowerCase();
        switch (activityKey == null ? "" : activityKey) {
            case "pillage":
                if (station.equals("gunning")) return "gunning";
                if (station.equals("foraging")) return "forage";
                return "";
            case "smh":
            case "atlantis":
            case "haunted_seas":
                if (isManeuverStation(station)) return "maneuvers";
                if (station.equals("gunning")) return "gunning";
                return ""; // treasure haul chest family is panel-only here
            case "cursed_isles":
                if (station.equals("foraging")) return "cursed_isles";
                return "";
            case "vampirate":
                // Panel data remains authoritative; this station mapping only
                // helps rows that earned no visible bonus tokens.
                if (station.equals("carpentry")) return "vampire_patches";
                if (station.equals("treasure haul")) return "vampire_chests";
                return "";
            default:
                return "";
        }
    }

    // Maneuver stations produce maneuvers, but only in a Sea Monster Hunt.
    private static boolean isManeuverStation(String station) {
        return MANEUVER_STATIONS.contains(station);
    }

    private Activities() {
    }
}

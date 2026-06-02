package plunderlyaccessibility.duty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry of duty-report score types and bonus vocabulary.
 *
 * The game exposes bonus counts as positional arrays. This registry gives each
 * position a stable name so persistence, dashboard calculations, and copy text
 * all speak the same vocabulary.
 */
public final class ScoreTypes {

    /** Sentinel score-type key for rows we could not classify. */
    public static final String UNMAPPED = "unmapped";

    public static final class ScoreType {
        public final String key;          // stable persistence key
        public final String label;        // dashboard/copy label
        public final String categoryNoun; // wording for totals, e.g. "chests"
        public final List<String> bonusOrder;
        public final boolean verified;    // true when the positional mapping has been confirmed

        ScoreType(String key, String label, String categoryNoun,
                  List<String> bonusOrder, boolean verified) {
            this.key = key;
            this.label = label;
            this.categoryNoun = categoryNoun;
            this.bonusOrder = List.copyOf(bonusOrder);
            this.verified = verified;
        }
    }

    private static final Map<String, ScoreType> BY_KEY = new LinkedHashMap<>();

    private static void register(String key, String label, String noun,
                                 boolean verified, String... bonuses) {
        BY_KEY.put(key, new ScoreType(key, label, noun, List.of(bonuses), verified));
    }

    static {
        // verified=true means the game has been observed using this positional
        // order for the named bonuses.
        register("maneuvers", "Maneuvers", "maneuvers", true,
                "circle", "diamond", "plus", "cross", "flower");
        register("gunning", "Gunning", "cannon balls", true,
                "cannonball");
        register("forage", "Foraging Chests", "chests", true,
                "strong_box", "ships_locker", "treasure_chest");
        register("atlantis", "Atlantis Chests", "chests", true,
                "sunken_box", "ancient_locker", "antediluvian_chest");
        register("cursed_isles", "Cursed Isles Chests", "chests", true,
                "bone_box", "fetish_jar", "cursed_chest");
        register("haunted_seas", "Haunted Seas Chests", "chests", true,
                "ghostly_box", "ethereal_locker", "spectral_chest");
        register("vampire_chests", "Vampire Chests", "chests", true,
                "blood_box", "nocturnal_locker", "immortal_chest");
        register("vampire_patches", "Vampire Patches", "holes filled", true,
                "slipshod", "creaky_coffin", "vampire_proof");
    }

    public static ScoreType byKey(String key) {
        return BY_KEY.get(key);
    }

    /** Registry keys in display order. */
    public static List<String> keys() {
        return new ArrayList<>(BY_KEY.keySet());
    }

    public static boolean isKnown(String key) {
        return BY_KEY.containsKey(key);
    }

    /**
     * Resolve a score type from the bonus-panel class and family discriminator.
     * Unknown families stay mapped as {@link #UNMAPPED} so they remain visible
     * in debug output and saved scans.
     */
    public static String fromBonusPanel(String panelClass, String typeKey) {
        if (panelClass == null) return UNMAPPED;
        if (panelClass.endsWith("$ManeuverBonusPanel")) return "maneuvers";
        if (panelClass.endsWith("$CannonBonusPanel")) return "gunning";
        if (panelClass.endsWith("$ChestBonusPanel")) {
            return switch (typeKey == null ? "" : typeKey) {
                case "BURIED_CHESTS" -> "forage";
                case "ATLANTEAN_CHESTS" -> "atlantis";
                case "CURSED_CHESTS" -> "cursed_isles";
                case "HAUNTED_CHESTS" -> "haunted_seas";
                case "VAMPIRATE_CHESTS" -> "vampire_chests";
                default -> UNMAPPED; // unsupported or unknown chest family
            };
        }
        if (panelClass.endsWith("$CounterBonusPanel")) {
            return "VAMPIRATE_PATCHES".equals(typeKey) ? "vampire_patches" : UNMAPPED;
        }
        return UNMAPPED;
    }

    /**
     * Map a positional count array to named bonus counts for a score type,
     * following the type's bonus order. Any counts beyond the known order are
     * surfaced as {@code extra_<i>} (only when non-zero) so a wrong positional
     * assumption is visible rather than silently dropped.
     */
    public static Map<String, Integer> namedCounts(String scoreTypeKey, int[] counts) {
        Map<String, Integer> named = new LinkedHashMap<>();
        ScoreType type = BY_KEY.get(scoreTypeKey);
        if (type == null || counts == null) return named;
        List<String> order = type.bonusOrder;
        for (int i = 0; i < order.size(); i++) {
            named.put(order.get(i), i < counts.length ? counts[i] : 0);
        }
        for (int i = order.size(); i < counts.length; i++) {
            if (counts[i] != 0) named.put("extra_" + i, counts[i]);
        }
        return named;
    }

    private ScoreTypes() {
    }
}

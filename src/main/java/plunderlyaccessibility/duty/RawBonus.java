package plunderlyaccessibility.duty;

/**
 * Raw bonus-panel data captured from the accessibility tree, pre-normalization.
 *
 * These values are kept in memory for the latest scan report. History persists
 * the normalized score type and named counts instead.
 */
public class RawBonus {
    public String panelClass = "";   // bonus panel implementation class
    public String typeKey = "";      // chest/counter family discriminator, if present
    public int[] counts = new int[0];
    public String display = "";      // human-readable text for the latest report
}

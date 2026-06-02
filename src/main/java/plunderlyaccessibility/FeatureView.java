package plunderlyaccessibility;

import java.awt.Component;

/**
 * Swing view contract for features that need more than the shared text area.
 *
 * CompanionApp hosts the returned component in its center card stack and sends
 * scan/status updates through {@link #update(String)}.
 */
public interface FeatureView {
    Component component();

    /** Accept latest text/status and refresh any visible data. */
    void update(String text);

    /** Refresh visible data without replacing the current status text. */
    void refresh();
}

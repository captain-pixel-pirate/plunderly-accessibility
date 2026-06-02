package plunderlyaccessibility;

import java.awt.Component;

/**
 * Swing view for features that need more than the shared text area.
 *
 */
public interface FeatureView {
    Component component();

    /** Accept latest text/status and refresh any visible data. */
    void update(String text);

    /** Refresh visible data without replacing the current status text. */
    void refresh();
}

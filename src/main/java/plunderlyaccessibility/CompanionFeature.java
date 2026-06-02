package plunderlyaccessibility;

import java.awt.Component;
import java.awt.Window;
import java.nio.file.Path;

/**
 * Extension point for companion capabilities.
 *
 * A feature contributes display text, optional actions, optional debug-tree
 * details, and optionally a custom Swing view hosted by {@link CompanionApp}.
 */
public interface CompanionFeature {
    String displayName();

    String slug();

    String initialText(Path outputDir, Path debugDir);

    default boolean hasPrimaryAction() {
        return false;
    }

    default String primaryActionLabel() {
        return "";
    }

    default String primaryActionWorkingText() {
        return "";
    }

    default void runPrimaryAction(CompanionApp app, Window window) {
    }

    default void appendDebugDetails(Component component, StringBuilder out) {
    }

    /** Custom center view for this feature, or {@code null} to use the shared text area. */
    default FeatureView view() {
        return null;
    }

    default String debugWorkingText() {
        return "Saving " + displayName() + " debug tree...";
    }

    @Override
    String toString();
}

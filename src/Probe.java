import plunderlyaccessibility.CompanionApp;

/**
 * Default-package entry point for Java's assistive-technology hook.
 *
 * Puzzle Pirates asks the JVM to instantiate {@code Probe} at startup. This
 * shim stays package-free because the JVM flag names it directly, then delegates
 * to the packaged companion application.
 */
public class Probe {
    public Probe() {
        new CompanionApp();
    }
}

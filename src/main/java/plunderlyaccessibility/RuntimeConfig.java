package plunderlyaccessibility;

/**
 * Runtime feature switches for behavior that should be available during local
 * development but hidden in deployed companion runs.
 */
public final class RuntimeConfig {
    private final boolean devMode;

    public RuntimeConfig() {
        this.devMode = bool("plunderly.devMode", "PLUNDERLY_DEV_MODE", false);
    }

    public boolean devMode() {
        return devMode;
    }

    public boolean debugFeaturesEnabled() {
        return devMode;
    }

    public boolean textReportsEnabled() {
        return devMode;
    }

    private static boolean bool(String property, String env, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on");
    }
}

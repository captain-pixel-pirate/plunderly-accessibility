package plunderlyaccessibility.debug;

import plunderlyaccessibility.CompanionFeature;

import javax.accessibility.AccessibleContext;
import javax.swing.Icon;
import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Window;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a readable snapshot of the live Swing/AWT component tree.
 *
 * Debug trees help contributors understand which game components are visible,
 * what accessibility metadata they expose, and which extra details each feature
 * can extract from those components.
 */
public class DebugTreeWriter {
    public void write(Window window, Path target, CompanionFeature feature) throws Exception {
        Runnable job = () -> {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(target, StandardCharsets.UTF_8))) {
                out.println("# Plunderly Accessibility Companion debug tree @ " + stamp());
                out.println("# feature = " + feature.displayName() + " (" + feature.slug() + ")");
                out.println("# window = " + describeWindow(window));
                out.println("# output = " + target);
                debugWalk(window, out, feature, 0, "root");
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        };
        if (EventQueue.isDispatchThread()) {
            job.run();
        } else {
            EventQueue.invokeAndWait(job);
        }
    }

    private void debugWalk(Component component, PrintWriter out, CompanionFeature feature, int depth, String path) {
        if (component == null) return;

        StringBuilder line = new StringBuilder();
        String indent = "  ".repeat(depth);
        line.append(indent).append(path).append(' ').append(component.getClass().getName());

        String role = accessibleRole(component);
        if (!isBlank(role)) line.append(" role=\"").append(role).append('"');
        String name = cleanText(accessibleName(component));
        if (!isBlank(name)) line.append(" name=\"").append(name).append('"');
        String desc = cleanText(accessibleDescription(component));
        if (!isBlank(desc)) line.append(" desc=\"").append(desc).append('"');
        if (component instanceof JLabel label) {
            String text = cleanText(label.getText());
            if (!isBlank(text)) line.append(" text=\"").append(text).append('"');
            Icon icon = label.getIcon();
            if (icon != null) {
                line.append(" icon=\"").append(icon.getClass().getName()).append('"');
                Map<String, Integer> fields = readNumericFields(icon);
                if (!fields.isEmpty()) line.append(" iconFields=").append(formatNumericFields(fields));
            }
        }

        feature.appendDebugDetails(component, line);
        out.println(line);

        if (component instanceof Container container) {
            Component[] children = container.getComponents();
            for (int i = 0; i < children.length; i++) {
                debugWalk(children[i], out, feature, depth + 1, path + "." + i);
            }
        }
    }

    private Map<String, Integer> readNumericFields(Object object) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (object == null) return out;

        Class<?> c = object.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            for (Field field : safeDeclaredFields(c)) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (!(type == int.class || type == short.class || type == byte.class)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value instanceof Number number) {
                        out.put(c.getSimpleName() + "." + field.getName(), number.intValue());
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private String formatNumericFields(Map<String, Integer> fields) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : fields.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return parts.toString();
    }

    private Field[] safeDeclaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private static String accessibleName(Component component) {
        try {
            AccessibleContext ac = component.getAccessibleContext();
            return ac == null ? "" : ac.getAccessibleName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String accessibleDescription(Component component) {
        try {
            AccessibleContext ac = component.getAccessibleContext();
            return ac == null ? "" : ac.getAccessibleDescription();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String accessibleRole(Component component) {
        try {
            AccessibleContext ac = component.getAccessibleContext();
            return ac == null || ac.getAccessibleRole() == null ? "" : String.valueOf(ac.getAccessibleRole());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String describeWindow(Window window) {
        if (window == null) return "none";
        String name = cleanText(accessibleName(window));
        String title = (window instanceof Frame f) ? cleanText(f.getTitle()) : "";
        if (!isBlank(title) && !title.equals(name)) {
            return window.getClass().getName() + " title=\"" + title + "\" name=\"" + name + "\"";
        }
        return window.getClass().getName() + " name=\"" + name + "\" visible=" + window.isVisible();
    }

    private static String cleanText(String text) {
        if (text == null) return "";
        return text.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}

package com.example.termmgmt.service;

import com.example.termmgmt.model.TermbaseConfig;

import java.util.List;

/**
 * The decisions behind the "termbase changed outside the plugin" check, kept free of Swing so
 * they can be tested. {@code TermManagementView} runs {@link #reloadModified} on a background
 * thread and uses {@link #shouldNotify} to decide whether to show the hint.
 */
public final class ExternalChangeCheck {

    private ExternalChangeCheck() {}

    /** What the check needs from the registry. */
    public interface Source {
        List<TermbaseConfig> enabledConfigs();
        boolean isExternallyModified(String filePath);
        void reload(String filePath);
    }

    /** Clears the hint on one tab; a Swing-free seam so the clear-on-start can be tested. */
    public interface TooltipClearer {
        void clear(int tabIndex);
    }

    /** Reloads every enabled termbase whose file changed; true if at least one was reloaded. */
    public static boolean reloadModified(Source source) {
        boolean anyReloaded = false;
        for (TermbaseConfig config : source.enabledConfigs()) {
            if (source.isExternallyModified(config.getFilePath())) {
                source.reload(config.getFilePath());
                anyReloaded = true;
            }
        }
        return anyReloaded;
    }

    /**
     * The hint is shown only if something was reloaded and the user is still on the tab the
     * check started from; on any other tab it would sit on the wrong label.
     */
    public static boolean shouldNotify(boolean reloaded, int tabAtStart, int selectedTabNow) {
        return reloaded && tabAtStart == selectedTabNow;
    }

    /**
     * Called at the start of every check and on every tab switch: clears the hint on every tab so
     * a hint left by a previous pass cannot survive into this one, even when this pass reloads
     * nothing or is skipped because another check is already running. Clears indices 0 ..
     * tabCount-1 exactly once each; a non-positive count clears nothing.
     */
    public static void clearAllTooltips(int tabCount, TooltipClearer clearer) {
        for (int i = 0; i < tabCount; i++) {
            clearer.clear(i);
        }
    }

    /**
     * The hint on a tab header must be visible without hovering: a per-tab tooltip only appears
     * when the mouse lingers on the tab header, which - after switching to the tab, when the
     * cursor is already over the panel content - the user never sees. So the hint is also written
     * into the tab title itself, by appending {@code marker}. Marking an already-marked title is
     * a no-op; a null title is treated as empty. The view restores the saved original title when
     * the user leaves the tab; this function only defines what "marked" means.
     */
    public static String addReloadMarker(String title, String marker) {
        String current = title == null ? "" : title;
        return current.endsWith(marker) ? current : current + marker;
    }
}

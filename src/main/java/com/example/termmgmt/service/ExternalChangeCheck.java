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
     * Called on every tab switch and at the start of every user-initiated check (not by the
     * periodic background probe, which must leave its own earlier hint standing): clears the
     * hint on every tab so a hint left by a previous pass cannot survive into this one, even
     * when this pass reloads nothing or is skipped because another check is already running.
     * Clears indices 0 .. tabCount-1 exactly once each; a non-positive count clears nothing.
     */
    public static void clearAllTooltips(int tabCount, TooltipClearer clearer) {
        for (int i = 0; i < tabCount; i++) {
            clearer.clear(i);
        }
    }
}

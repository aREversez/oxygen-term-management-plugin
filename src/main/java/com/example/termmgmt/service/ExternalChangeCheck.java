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
}

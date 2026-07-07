package com.example.termmgmt.util;

import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.ResourceBundle;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;

/**
 * Utility class for internationalization with safe fallback.
 * Detects language from Oxygen's UI language setting.
 */
public class I18N {

    private static ResourceBundle bundle = null;

    /**
     * Get the i18n resource bundle with safe fallback.
     * Returns a bundle that returns the key itself if the value is missing.
     */
    public static synchronized ResourceBundle getBundle() {
        if (bundle == null) {
            bundle = loadBundle("i18n.messages");
        }
        return bundle;
    }

    /**
     * Force reload of the resource bundle (call when UI language changes).
     */
    public static synchronized void reload() {
        bundle = null;
    }

    /**
     * Load a resource bundle with safe fallback.
     */
    private static ResourceBundle loadBundle(String name) {
        Locale locale = getOxygenLocale();
        try {
            return ResourceBundle.getBundle(name, locale);
        } catch (Exception e) {
            return new ListResourceBundle() {
                @Override
                protected Object[][] getContents() {
                    return new Object[0][];
                }
            };
        }
    }

    /**
     * Detect the UI language from Oxygen's plugin workspace.
     * Falls back to the system default locale if not available.
     */
    private static Locale getOxygenLocale() {
        try {
            if (PluginWorkspaceProvider.getPluginWorkspace() != null) {
                String langCode = PluginWorkspaceProvider.getPluginWorkspace()
                        .getUserInterfaceLanguage();
                if (langCode != null && !langCode.isEmpty()) {
                    String[] parts = langCode.split("_");
                    if (parts.length >= 2) {
                        return new Locale(parts[0], parts[1]);
                    }
                    return new Locale(langCode);
                }
            }
        } catch (Exception e) {
            // PluginWorkspace not available yet
        }
        return Locale.getDefault();
    }

    /**
     * Get a localized string by key.
     * @param key the resource key
     * @return the localized string, or the key itself if not found
     */
    public static String getString(String key) {
        try {
            return getBundle().getString(key);
        } catch (Exception e) {
            return "[" + key + "]";
        }
    }

    /**
     * Get a localized string by key with format args.
     * @param key the resource key
     * @param args format arguments
     * @return the formatted localized string
     */
    public static String getString(String key, Object... args) {
        String msg = getString(key);
        if (args.length > 0) {
            msg = String.format(msg, args);
        }
        return msg;
    }
}

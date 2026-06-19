package com.example.termmgmt.util;

import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Utility class for internationalization with safe fallback.
 * Prevents ExceptionInInitializerError when bundle files are missing.
 */
public class I18N {

    private static ResourceBundle bundle = null;

    /**
     * Get the i18n resource bundle with safe fallback.
     * Returns a bundle that returns the key itself if the value is missing.
     */
    public static ResourceBundle getBundle() {
        if (bundle == null) {
            bundle = loadBundle("i18n.messages");
        }
        return bundle;
    }

    /**
     * Load a resource bundle with safe fallback.
     */
    private static ResourceBundle loadBundle(String name) {
        try {
            return ResourceBundle.getBundle(name);
        } catch (Exception e) {
            try {
                return ResourceBundle.getBundle(name, Locale.getDefault());
            } catch (Exception ex) {
                // Return a minimal bundle that echoes back the key
                return new ListResourceBundle() {
                    @Override
                    protected Object[][] getContents() {
                        return new Object[0][];
                    }
                };
            }
        }
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

package com.example.termmgmt.util;

import java.util.IllegalFormatException;

public final class MessageUtils {

    private MessageUtils() {
    }

    /**
     * Format a UI message, falling back to the unformatted text instead of throwing.
     *
     * Messages come from translatable resource files, so a stray '%' or a missing or
     * mismatched argument must not turn into an uncaught exception in the UI.
     */
    public static String formatSafely(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(pattern, args);
        } catch (IllegalFormatException e) {
            return pattern;
        }
    }
}

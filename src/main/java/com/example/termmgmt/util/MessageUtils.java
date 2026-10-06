package com.example.termmgmt.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class MessageUtils {

    private MessageUtils() {
    }

    /** True for values written MessageFormat-style ("{0} issue(s) found.") rather than %s-style. */
    private static final Pattern MESSAGE_FORMAT_PLACEHOLDER = Pattern.compile("\\{\\d");

    /**
     * Format a UI message, falling back to the unformatted text instead of throwing.
     *
     * Messages come from translatable resource files, so a stray '%' or a missing or
     * mismatched argument must not turn into an uncaught exception in the UI.
     *
     * <p>Two placeholder styles are in use: the historical String.format %s, and MessageFormat
     * {0} (as in msg.check.summary). Rendering a {0}-style value with String.format silently
     * showed the braces to the user in every language, so the renderer is picked by matching
     * the pattern instead.
     */
    public static String formatSafely(String pattern, Object... args) {
        if (pattern == null || args == null || args.length == 0) {
            return pattern;
        }
        try {
            if (MESSAGE_FORMAT_PLACEHOLDER.matcher(pattern).find()) {
                return new java.text.MessageFormat(pattern, Locale.getDefault()).format(args);
            }
            return String.format(pattern, args);
        } catch (RuntimeException e) {
            // IllegalFormatException or a malformed pattern: better the raw text than a crash.
            return pattern;
        }
    }
}

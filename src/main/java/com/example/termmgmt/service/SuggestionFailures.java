package com.example.termmgmt.service;

/**
 * Reasons a suggestion request failed, as stable codes instead of finished English sentences.
 *
 * <p>The reasons end up inside the "these terms got no suggestion" notice, mixed into a sentence
 * that IS translated; a hardcoded English reason there reads like a glitch in de/fr/ja/zh. The
 * service layer therefore reports {@code "code:<i18n-key>[|<technical detail>]} instead of prose,
 * the UI layer renders it with {@link #render(String)}, and only technical details (a raw parse
 * error, {@code finish_reason=length}) stay verbatim — the same way an HTTP failure keeps its URL.
 *
 * <p>This class knows no bundle and no locale: {@link #render} only splits the code apart, and
 * looking the key up is left to the caller's I18N, so the service layer stays testable without
 * the UI stack.
 */
public final class SuggestionFailures {

    private SuggestionFailures() {}

    /** Prefix marking a failure reason as a code rather than display text. */
    public static final String PREFIX = "code:";
    private static final char DETAIL_SEPARATOR = '|';

    // Keys into the i18n bundles, one per reason a term can come back without a translation.
    public static final String KEY_DISABLED = "ai.fail.disabled";
    public static final String KEY_INCOMPLETE_CONFIG = "ai.fail.incomplete_config";
    public static final String KEY_NO_CHOICES = "ai.fail.no_choices";
    public static final String KEY_NO_MESSAGE = "ai.fail.no_message";
    public static final String KEY_PARSE = "ai.fail.parse";
    public static final String KEY_NOT_A_JSON_OBJECT = "ai.fail.not_a_json_object";
    public static final String KEY_NOT_VALID_JSON = "ai.fail.not_valid_json";
    public static final String KEY_TRUNCATED = "ai.fail.truncated";
    public static final String KEY_EMPTY = "ai.fail.empty";
    public static final String KEY_TOKEN_LIMIT_EMPTY = "ai.fail.token_limit_empty";
    public static final String KEY_CONTENT_FILTER = "ai.fail.content_filter";
    public static final String KEY_NO_ANSWER = "ai.fail.no_answer";

    /** All keys above; a test pins that every bundle carries each of them. */
    public static final String[] KEYS = {
        KEY_DISABLED, KEY_INCOMPLETE_CONFIG, KEY_NO_CHOICES, KEY_NO_MESSAGE, KEY_PARSE,
        KEY_NOT_A_JSON_OBJECT, KEY_NOT_VALID_JSON, KEY_TRUNCATED, KEY_EMPTY,
        KEY_TOKEN_LIMIT_EMPTY, KEY_CONTENT_FILTER, KEY_NO_ANSWER,
    };

    public static String code(String key) {
        return PREFIX + key;
    }

    /** A code with a technical detail that cannot be translated (exception text, API reason). */
    public static String code(String key, String detail) {
        return detail == null || detail.isEmpty() ? PREFIX + key
            : PREFIX + key + DETAIL_SEPARATOR + detail;
    }

    /** The i18n key of a code, or null when {@code reason} is plain display text. */
    public static String keyOf(String reason) {
        if (reason == null || !reason.startsWith(PREFIX)) {
            return null;
        }
        String body = reason.substring(PREFIX.length());
        int sep = body.indexOf(DETAIL_SEPARATOR);
        return sep < 0 ? body : body.substring(0, sep);
    }

    /** The technical detail of a code, or null when it carries none. */
    public static String detailOf(String reason) {
        if (reason == null || !reason.startsWith(PREFIX)) {
            return null;
        }
        int sep = reason.indexOf(DETAIL_SEPARATOR);
        return sep < 0 ? null : reason.substring(sep + 1);
    }

    /**
     * Display text for a failure reason out of the service layer: a code is rendered as its key
     * plus its detail ({@code code:ai.fail.truncated|finish_reason=length} becomes
     * {@code "ai.fail.truncated (finish_reason=length)"}), anything else is already display text
     * (an HTTP failure, a timeout) and is returned unchanged.
     *
     * <p>Splitting is done here; replacing the key by its localized label is left to the UI
     * caller, which owns the resource bundle.
     */
    public static String render(String reason) {
        String key = keyOf(reason);
        if (key == null) {
            return reason;
        }
        String detail = detailOf(reason);
        return detail == null || detail.isEmpty() ? key : key + " (" + detail + ")";
    }
}

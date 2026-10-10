package com.example.termmgmt.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * One run of AI translation suggestions over a list of source terms, with every term accounted
 * for: each ends up translated, failed (with the reason), or not requested (the run was cancelled
 * or stopped early). A term never just disappears, which is what the UI relies on to tell the
 * user which entries are still blank and why.
 *
 * <p>Pure logic, no Swing: the panel only supplies the terms, the cancel flag and a progress sink.
 */
public final class SuggestionRun {

    private SuggestionRun() {}

    /** Receives progress: {@code done} of {@code total} terms are finished; {@code current} is next. */
    public interface Progress {
        void update(int done, int total, String current);
    }

    /**
     * What a run produced. Keys are indexes into the list of terms that was passed in.
     *
     * @param translations  index to suggested translation, in input order
     * @param failures      index to the reason no translation was obtained
     * @param total         number of terms in the run
     * @param aborted       true when the run stopped early because requests kept failing
     * @param firstError    the first request failure of the run, or null when none failed
     */
    public record Outcome(Map<Integer, String> translations, Map<Integer, String> failures,
                          int total, boolean aborted, String firstError) {

        /** Terms with neither a translation nor a failure: never requested (cancelled or aborted). */
        public int unprocessed() {
            return total - translations.size() - failures.size();
        }

        /** Terms left blank: failed plus never requested. */
        public int omitted() {
            return total - translations.size();
        }
    }

    /**
     * Runs the suggestions one term at a time.
     *
     * @param terms     non-blank source terms
     * @param cancelled polled before each request; the run stops as soon as it reports true
     * @param progress  may be null
     */
    public static Outcome run(List<String> terms,
                              String sourceLang,
                              String targetLang,
                              TranslationSuggester.Config config,
                              HttpTransport transport,
                              BooleanSupplier cancelled,
                              Progress progress) {
        Map<Integer, String> translations = new LinkedHashMap<>();
        Map<Integer, String> failures = new LinkedHashMap<>();
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        boolean aborted = false;

        for (int i = 0; i < terms.size(); i++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                break;
            }
            if (progress != null) {
                progress.update(i, terms.size(), terms.get(i));
            }
            String failure = null;
            try {
                SuggestionResult r = TranslationSuggester.suggest(
                    terms.get(i), sourceLang, targetLang, config, transport);
                if (r.success() && r.translation() != null && !r.translation().isBlank()) {
                    translations.put(i, r.translation().trim());
                    guard.recordSuccess();
                } else {
                    failure = reasonOrDefault(r.errorMessage());
                }
            } catch (Exception ex) {
                failure = reasonOrDefault(ex.getMessage());
            }
            if (failure != null) {
                failures.put(i, failure);
                guard.recordFailure(failure);
                if (guard.shouldAbort()) {
                    aborted = true;
                    break;
                }
            }
        }
        if (progress != null && !cancelled.getAsBoolean()) {
            progress.update(translations.size() + failures.size(), terms.size(), "");
        }
        return new Outcome(translations, failures, terms.size(), aborted, guard.firstError());
    }

    private static String reasonOrDefault(String message) {
        return message == null || message.isBlank() ? "no translation returned" : message;
    }
}

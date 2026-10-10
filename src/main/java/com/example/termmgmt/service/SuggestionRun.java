package com.example.termmgmt.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One run of AI translation suggestions over a list of source terms, with every term accounted
 * for: each ends up translated, failed (with the reason), or not requested (the run was cancelled
 * or stopped early). A term never just disappears, which is what the UI relies on to tell the
 * user which entries are still blank and why.
 *
 * <p>Terms are sent in batches of {@link #BATCH_SIZE}. Each batch is one request that carries the
 * reference translations once (rather than once per term) and lets the model see the terms of the
 * batch together, which helps it settle on the domain. The reply is matched to terms by number, so
 * a term the model skipped is noticed and asked again on its own.
 *
 * <p>Pure logic, no Swing: the panel only supplies the terms, the cancel flag and a progress sink.
 */
public final class SuggestionRun {

    private SuggestionRun() {}

    /** Terms per request. */
    public static final int BATCH_SIZE = 20;
    /** At most this many reference pairs are sent with a request. */
    public static final int MAX_REFERENCE_PAIRS = 20;
    /** Budget for the reference text of one request, so a termbase of long entries cannot bloat it. */
    public static final int MAX_REFERENCE_CHARS = 2000;
    /** A pair with a longer side is a sentence or a definition, not a term, and is not a useful reference. */
    private static final int MAX_REFERENCE_SIDE = 80;

    /**
     * Picks the reference pairs to send from the termbase's complete pairs: skips multi-line or
     * long entries and duplicates, spreads the pick evenly over the termbase (so it is not just the
     * first letters of the alphabet) and stops at the budgets. Deterministic, so every batch of a run
     * and every run over the same termbase sends the same text, which a provider can cache.
     */
    public static List<TranslationSuggester.Reference> selectReferences(
            List<TranslationSuggester.Reference> pool, int maxPairs, int maxChars) {
        List<TranslationSuggester.Reference> usable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TranslationSuggester.Reference r : pool) {
            if (r.source() == null || r.target() == null) continue;
            String src = r.source().trim();
            String tgt = r.target().trim();
            if (src.isEmpty() || tgt.isEmpty()) continue;
            if (src.length() > MAX_REFERENCE_SIDE || tgt.length() > MAX_REFERENCE_SIDE) continue;
            if (hasLineBreak(src) || hasLineBreak(tgt)) continue;
            if (seen.add(src)) {
                usable.add(new TranslationSuggester.Reference(src, tgt));
            }
        }
        List<TranslationSuggester.Reference> picked = new ArrayList<>();
        int chars = 0;
        int n = usable.size();
        int take = Math.min(n, Math.max(0, maxPairs));
        for (int i = 0; i < take; i++) {
            TranslationSuggester.Reference r = usable.get((int) ((long) i * n / take));
            chars += r.source().length() + r.target().length() + 6;
            if (chars > maxChars) break;
            picked.add(r);
        }
        return picked;
    }

    private static boolean hasLineBreak(String s) {
        return s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
    }

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
     * Runs the suggestions, {@link #BATCH_SIZE} terms per request.
     *
     * @param terms      non-blank source terms
     * @param references termbase pairs sent as context with every request (may be empty)
     * @param cancelled  polled before each request; the run stops as soon as it reports true
     * @param progress   may be null
     */
    public static Outcome run(List<String> terms,
                              String sourceLang,
                              String targetLang,
                              List<TranslationSuggester.Reference> references,
                              TranslationSuggester.Config config,
                              HttpTransport transport,
                              BooleanSupplier cancelled,
                              Progress progress) {
        Map<Integer, String> translations = new LinkedHashMap<>();
        Map<Integer, String> failures = new LinkedHashMap<>();
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        boolean aborted = false;

        batches:
        for (int start = 0; start < terms.size(); start += BATCH_SIZE) {
            if (stop(cancelled)) break;
            int end = Math.min(start + BATCH_SIZE, terms.size());
            if (progress != null) {
                progress.update(start, terms.size(), terms.get(start));
            }
            List<String> batch = terms.subList(start, end);
            TranslationSuggester.BatchResult reply = TranslationSuggester.suggestBatch(
                batch, sourceLang, targetLang, references, config, transport);

            if (reply.requestFailed()) {
                // The request itself failed (address, key, timeout): asking again term by term
                // would only repeat it, so the whole batch is recorded as failed.
                String reason = reasonOrDefault(reply.error());
                for (int i = start; i < end; i++) {
                    failures.put(i, reason);
                }
                guard.recordFailure(reason);
                if (guard.shouldAbort()) {
                    aborted = true;
                    break;
                }
                continue;
            }
            guard.recordSuccess();
            for (Map.Entry<Integer, String> answer : reply.translations().entrySet()) {
                translations.put(start + answer.getKey() - 1, answer.getValue());
            }
            // Whatever the model skipped (or garbled) is asked again on its own.
            for (int i = start; i < end; i++) {
                if (translations.containsKey(i)) continue;
                if (stop(cancelled)) break batches;
                String failure = null;
                try {
                    SuggestionResult r = TranslationSuggester.suggest(
                        terms.get(i), sourceLang, targetLang, references, config, transport);
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
                        break batches;
                    }
                }
            }
        }
        if (progress != null && !stop(cancelled)) {
            progress.update(translations.size() + failures.size(), terms.size(), "");
        }
        return new Outcome(translations, failures, terms.size(), aborted, guard.firstError());
    }

    private static boolean stop(BooleanSupplier cancelled) {
        return cancelled.getAsBoolean() || Thread.currentThread().isInterrupted();
    }

    private static String reasonOrDefault(String message) {
        return message == null || message.isBlank() ? "no translation returned" : message;
    }
}

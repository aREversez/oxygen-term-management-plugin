package com.example.termmgmt.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.service.DocumentScanner.ScanResult;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.util.InflectionVariants;

import com.example.termmgmt.util.MarkupMasker;
import com.example.termmgmt.util.TermMatchUtils;

/**
 * Test-only oracle: the per-term regex scanner exactly as it was before the Aho-Corasick
 * rewrite. {@code DocumentScanner} must return identical results for every input; the
 * differential tests compare the two. Do not "improve" this class - its value is that it is the
 * old behaviour.
 */
public class ReferenceScanner {

    /**
     * Compiled patterns survive across scans: a panel builds a new DocumentScanner for
     * every scan, so only a static cache actually avoids recompiling every term. The key
     * carries the literal text and the case setting; a pattern depends on nothing else
     * (text-mode patterns key on the escaped term, author-mode on the raw one).
     * Capped at 50 000 entries: when exceeded, the entire cache is cleared (next scan
     * recompiles; results are unaffected).
     */
    private static final int PATTERN_CACHE_LIMIT = 50_000;
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments) {
        // Historic behaviour: matching ignores case.
        return scan(documentText, terms, isTextMode, authorSegments, false);
    }

    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments, boolean caseSensitive) {
        return scan(documentText, terms, isTextMode, authorSegments, caseSensitive, ScanDirection.SOURCE);
    }

    /**
     * Full form. The only difference between the two directions is which side of an entry is
     * searched in the document: the source term for {@link ScanDirection#SOURCE}, the target term
     * for {@link ScanDirection#TARGET}. Everything else - XML entity escaping, markup masking, the
     * word-boundary rule, the case option, longest-match filtering, entity-reference rejection,
     * Author-mode offset mapping and the source+target+position de-duplication key - is shared, so
     * the two directions can never drift apart. The entry-side text actually matched is carried on
     * each result as {@link ScanResult#matchedText}.
     */
    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments, boolean caseSensitive,
            ScanDirection direction) {
        return scan(documentText, terms, isTextMode, authorSegments, caseSensitive, direction, false);
    }

    /**
     * The same scan, optionally also matching the word forms of each term. Here every form is
     * simply another regex run of the same entry, which is the plain reading of the feature;
     * the production scanner reaches the same result through one automaton.
     */
    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments, boolean caseSensitive,
            ScanDirection direction, boolean inflect) {
        Map<String, Integer> countedPositions = new HashMap<>();
        List<RawMatch> rawMatches = new ArrayList<>();

        // In text mode the editor hands over the raw file including markup; blank the
        // markup out (offset-preserving) so tags, attributes, comments and PIs can never
        // produce term hits. Author mode receives already-extracted text and is untouched.
        List<int[]> entityRanges = null;
        if (isTextMode) {
            documentText = MarkupMasker.mask(documentText);
            // 8.1: Pre-scan entity ranges to reject matches inside &...; references.
            entityRanges = MarkupMasker.findEntityRanges(documentText);
        }

        for (TermEntry term : terms) {
            if (Thread.currentThread().isInterrupted()) break;
            if (documentText.isEmpty()) break;
            String sourceTerm = term.getSourceTerm();
            String targetTerm = term.getTargetTerm();
            // Which side of the entry is searched in the document. A blank side means there is
            // nothing to look for, so the entry is skipped; a source with no target never matches
            // in TARGET, and vice versa in SOURCE.
            String matchedText = direction == ScanDirection.TARGET ? targetTerm : sourceTerm;
            if (matchedText == null || matchedText.isEmpty()) continue;

            String baseTerm = isTextMode ? escapeXmlEntities(matchedText) : matchedText;
            List<String> candidates = new ArrayList<>();
            candidates.add(baseTerm);
            if (inflect) {
                candidates.addAll(InflectionVariants.variantsOf(baseTerm));
            }
            for (String matchTerm : candidates) {
            String cacheKey = (caseSensitive ? "s" : "i") + "\u0000" + matchTerm;
            // 9.4: Cap cache size to prevent unbounded growth across a session.
            if (PATTERN_CACHE.size() >= PATTERN_CACHE_LIMIT) {
                PATTERN_CACHE.clear();
            }
            Pattern pattern = PATTERN_CACHE.computeIfAbsent(
                cacheKey, k -> TermMatchUtils.buildMatchPattern(matchTerm, caseSensitive));
            boolean needBoundary = TermMatchUtils.boundaryNeeded(matchTerm);
            Matcher matcher = pattern.matcher(documentText);

            while (matcher.find()) {
                if (Thread.currentThread().isInterrupted()) break;
                if (documentText.isEmpty()) break;
                int strStart = matcher.start();
                int strEnd = matcher.end();
                // The pattern is a plain literal for speed; the word-boundary rule lives
                // here, checked on code points so letters outside the BMP count too.
                if (needBoundary && !TermMatchUtils.acceptAtBoundary(documentText, strStart, strEnd)) {
                    continue;
                }
                // 8.1: Reject match that falls strictly inside an entity reference.
                if (entityRanges != null && !entityRanges.isEmpty()
                        && MarkupMasker.isInsideEntity(strStart, strEnd, entityRanges)) {
                    continue;
                }

                // Key: source + target + position. The target must be part of it or a second
                // translation of the same source at the same spot silently disappears; an
                // identical triple arriving through two termbases still collapses to one hit.
                // The key is direction-independent, so the same pair matches once whichever side
                // drove the search.
                String posKey = sourceTerm + "\u0000" + targetTerm + "\u0000" + strStart;
                Integer seen = countedPositions.get(posKey);
                if (seen == null) {
                    countedPositions.put(posKey, rawMatches.size());
                    rawMatches.add(new RawMatch(sourceTerm, targetTerm, strStart, strEnd, term.getStatus(), matchedText));
                } else if (strEnd > rawMatches.get(seen).strEnd) {
                    rawMatches.set(seen, new RawMatch(sourceTerm, targetTerm, strStart, strEnd, term.getStatus(), matchedText));
                }
            }
            }
        }

        List<ScanResult> allMatches = new ArrayList<>();
        for (RawMatch m : retainLongestMatches(rawMatches)) {
            if (isTextMode) {
                allMatches.add(new ScanResult(
                    m.sourceTerm, m.targetTerm, m.strStart, m.strEnd, m.status, m.matchedText));
            } else {
                int authStart = -1, authEnd = -1;
                for (int[] seg : authorSegments) {
                    int segAuth = seg[0];
                    int segStr = seg[1];
                    int segLen = seg[2];
                    if (m.strStart >= segStr && m.strStart < segStr + segLen) {
                        authStart = segAuth + (m.strStart - segStr);
                    }
                    if (m.strEnd >= segStr && m.strEnd <= segStr + segLen) {
                        authEnd = segAuth + (m.strEnd - segStr);
                    }
                }
                if (authStart >= 0 && authEnd >= 0) {
                    allMatches.add(new ScanResult(
                        m.sourceTerm, m.targetTerm, authStart, authEnd, m.status, m.matchedText));
                }
            }
        }
        return allMatches;
    }

    /** One deduplicated hit still in document-string coordinates, before longest-match filtering. */
    private static final class RawMatch {
        final String sourceTerm;
        final String targetTerm;
        final int strStart;
        final int strEnd;
        final TermStatus status;
        final String matchedText;

        RawMatch(String sourceTerm, String targetTerm, int strStart, int strEnd, TermStatus status,
                String matchedText) {
            this.sourceTerm = sourceTerm;
            this.targetTerm = targetTerm;
            this.strStart = strStart;
            this.strEnd = strEnd;
            this.status = status;
            this.matchedText = matchedText;
        }
    }

    /**
     * Drops every match fully contained in a strictly longer one; equal spans and partial
     * overlaps are kept. 8.2: DEPRECATED matches are always retained (never swallowed by a
     * longer non-deprecated match) but do not update the running maximum, so they don't
     * affect containment checks for subsequent non-deprecated matches.
     */
    private static List<RawMatch> retainLongestMatches(List<RawMatch> matches) {
        if (matches.size() < 2) return matches;
        List<RawMatch> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparingInt((RawMatch m) -> m.strStart)
            .thenComparingInt(m -> -m.strEnd));
        List<RawMatch> kept = new ArrayList<>(sorted.size());
        int curMaxEnd = Integer.MIN_VALUE;
        // Largest start among the matches seen so far that reach curMaxEnd; if a candidate
        // starts behind it and ends no further, the longer span covers it completely.
        int maxEndStart = Integer.MIN_VALUE;
        for (RawMatch m : sorted) {
            // 8.2: Deprecated terms are always reported, even when contained in a longer match.
            if (m.status == TermStatus.DEPRECATED) {
                kept.add(m);
                continue; // do NOT update curMaxEnd
            }
            if (m.strEnd < curMaxEnd || (m.strEnd == curMaxEnd && m.strStart > maxEndStart)) {
                continue;
            }
            kept.add(m);
            if (m.strEnd >= curMaxEnd) {
                curMaxEnd = m.strEnd;
                maxEndStart = m.strStart;
            }
        }
        return kept;
    }

    private static String escapeXmlEntities(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
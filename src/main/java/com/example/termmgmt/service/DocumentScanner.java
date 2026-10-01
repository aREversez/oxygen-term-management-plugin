package com.example.termmgmt.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.termmgmt.model.TermEntry;

import com.example.termmgmt.util.MarkupMasker;
import com.example.termmgmt.util.TermMatchUtils;

public class DocumentScanner {

    /**
     * Compiled patterns survive across scans: a panel builds a new DocumentScanner for
     * every scan, so only a static cache actually avoids recompiling every term. The key
     * carries the literal text and the case setting; a pattern depends on nothing else
     * (text-mode patterns key on the escaped term, author-mode on the raw one).
     */
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();

    public static class ScanResult {
        public final String sourceTerm;
        public final String targetTerm;
        public final int startOffset;
        public final int endOffset;

        public ScanResult(String source, String target, int start, int end) {
            this.sourceTerm = source;
            this.targetTerm = target;
            this.startOffset = start;
            this.endOffset = end;
        }
    }

    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments) {
        // Historic behaviour: matching ignores case.
        return scan(documentText, terms, isTextMode, authorSegments, false);
    }

    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments, boolean caseSensitive) {
        Set<String> countedPositions = new HashSet<>();
        List<RawMatch> rawMatches = new ArrayList<>();

        // In text mode the editor hands over the raw file including markup; blank the
        // markup out (offset-preserving) so tags, attributes, comments and PIs can never
        // produce term hits. Author mode receives already-extracted text and is untouched.
        if (isTextMode) {
            documentText = MarkupMasker.mask(documentText);
        }

        for (TermEntry term : terms) {
            if (Thread.currentThread().isInterrupted()) break;
            if (documentText.isEmpty()) break;
            String sourceTerm = term.getSourceTerm();
            if (sourceTerm == null || sourceTerm.isEmpty()) continue;

            String matchTerm = isTextMode ? escapeXmlEntities(sourceTerm) : sourceTerm;
            String cacheKey = (caseSensitive ? "s" : "i") + "\u0000" + matchTerm;
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

                // Key: source + target + position. The target must be part of it or a second
                // translation of the same source at the same spot silently disappears; an
                // identical triple arriving through two termbases still collapses to one hit.
                String posKey = sourceTerm + "\u0000" + term.getTargetTerm() + "\u0000" + strStart;
                if (countedPositions.add(posKey)) {
                    rawMatches.add(new RawMatch(sourceTerm, term.getTargetTerm(), strStart, strEnd));
                }
            }
        }

        List<ScanResult> allMatches = new ArrayList<>();
        for (RawMatch m : retainLongestMatches(rawMatches)) {
            if (isTextMode) {
                allMatches.add(new ScanResult(
                    m.sourceTerm, m.targetTerm, m.strStart, m.strEnd));
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
                        m.sourceTerm, m.targetTerm, authStart, authEnd));
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

        RawMatch(String sourceTerm, String targetTerm, int strStart, int strEnd) {
            this.sourceTerm = sourceTerm;
            this.targetTerm = targetTerm;
            this.strStart = strStart;
            this.strEnd = strEnd;
        }
    }

    /**
     * Drops every match fully contained in a strictly longer one; equal spans and partial
     * overlaps are kept. Sorting by (start asc, end desc) guarantees that a match can only
     * ever be contained in one seen before it, so a single scan over the running maximum
     * end is enough - O(m log m) for the sort, O(m) for the filter.
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
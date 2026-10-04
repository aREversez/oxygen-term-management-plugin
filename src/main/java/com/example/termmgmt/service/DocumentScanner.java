package com.example.termmgmt.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;

import com.example.termmgmt.util.MarkupMasker;
import com.example.termmgmt.util.TermAutomaton;
import com.example.termmgmt.util.TermMatchUtils;

public class DocumentScanner {

    public static class ScanResult {
        public final String sourceTerm;
        public final String targetTerm;
        public final int startOffset;
        public final int endOffset;
        /** The maturity status of the matched entry; null means unset/preferred. */
        public final TermStatus status;
        /**
         * The entry-side text that was actually matched in the document: the source term for a
         * {@link ScanDirection#SOURCE} scan, the target term for a {@link ScanDirection#TARGET}
         * scan. Navigation, de-duplication display and highlighting all key off this, so the UI
         * never has to know which side was searched. Never null; older constructors default it to
         * the source term.
         */
        public final String matchedText;

        public ScanResult(String source, String target, int start, int end) {
            this(source, target, start, end, null, source);
        }

        public ScanResult(String source, String target, int start, int end, TermStatus status) {
            this(source, target, start, end, status, source);
        }

        public ScanResult(String source, String target, int start, int end, TermStatus status,
                String matchedText) {
            this.sourceTerm = source;
            this.targetTerm = target;
            this.startOffset = start;
            this.endOffset = end;
            this.status = status;
            this.matchedText = matchedText != null ? matchedText : source;
        }
    }

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
        Set<String> countedPositions = new HashSet<>();
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

        if (!documentText.isEmpty()) {
            scanInto(documentText, terms, isTextMode, caseSensitive, direction, entityRanges,
                countedPositions, rawMatches);
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

    /** One searchable entry: the termbase entry and the text of it that is looked for in the document. */
    private static final class Slot {
        final TermEntry entry;
        final String matchedText;

        Slot(TermEntry entry, String matchedText) {
            this.entry = entry;
            this.matchedText = matchedText;
        }
    }

    /**
     * One pass over the document for the whole termbase. The behaviour is that of running a
     * literal regex per entry, which the previous implementation did: the same case rule, the
     * same "a term never overlaps itself" rule, and every later check unchanged.
     *
     * <ul>
     *   <li>Entries whose searched text is equal after case folding share one pattern and are
     *       matched once; the hit is then handed to each of them in termbase order.</li>
     *   <li>For one pattern, an occurrence that starts before the end of the previous occurrence
     *       is skipped, and an occurrence rejected by the boundary or entity rule still counts as
     *       consumed, exactly as {@code Matcher.find()} resumed after the match it had returned.</li>
     * </ul>
     */
    private static void scanInto(String documentText, List<TermEntry> terms, boolean isTextMode,
            boolean caseSensitive, ScanDirection direction, List<int[]> entityRanges,
            Set<String> countedPositions, List<RawMatch> rawMatches) {
        TermAutomaton.Builder builder = new TermAutomaton.Builder();
        List<List<Slot>> slotsByPattern = new ArrayList<>();
        List<Boolean> boundaryByPattern = new ArrayList<>();

        for (TermEntry term : terms) {
            // Which side of the entry is searched in the document. A blank side means there is
            // nothing to look for, so the entry is skipped; a source with no target never matches
            // in TARGET, and vice versa in SOURCE.
            String matchedText = direction == ScanDirection.TARGET ? term.getTargetTerm() : term.getSourceTerm();
            if (matchedText == null || matchedText.isEmpty()) continue;

            String matchTerm = isTextMode ? escapeXmlEntities(matchedText) : matchedText;
            char[] pattern = caseSensitive ? matchTerm.toCharArray() : TermAutomaton.fold(matchTerm);
            int id = builder.add(pattern);
            if (id == slotsByPattern.size()) {
                slotsByPattern.add(new ArrayList<>());
                boundaryByPattern.add(TermMatchUtils.boundaryNeeded(matchTerm));
            }
            slotsByPattern.get(id).add(new Slot(term, matchedText));
        }
        if (slotsByPattern.isEmpty()) return;

        TermAutomaton automaton = builder.build();
        char[] haystack = caseSensitive ? documentText.toCharArray() : TermAutomaton.fold(documentText);
        int[] consumedUpTo = new int[slotsByPattern.size()];

        automaton.scan(haystack, (patternId, strStart, strEnd) -> {
            if (strStart < consumedUpTo[patternId]) return;
            consumedUpTo[patternId] = strEnd;
            // The pattern is a plain literal for speed; the word-boundary rule lives
            // here, checked on code points so letters outside the BMP count too.
            if (boundaryByPattern.get(patternId)
                    && !TermMatchUtils.acceptAtBoundary(documentText, strStart, strEnd)) {
                return;
            }
            // 8.1: Reject match that falls strictly inside an entity reference.
            if (entityRanges != null && !entityRanges.isEmpty()
                    && MarkupMasker.isInsideEntity(strStart, strEnd, entityRanges)) {
                return;
            }
            for (Slot slot : slotsByPattern.get(patternId)) {
                TermEntry term = slot.entry;
                // Key: source + target + position. The target must be part of it or a second
                // translation of the same source at the same spot silently disappears; an
                // identical triple arriving through two termbases still collapses to one hit.
                // The key is direction-independent, so the same pair matches once whichever side
                // drove the search.
                String posKey = term.getSourceTerm() + "\u0000" + term.getTargetTerm() + "\u0000" + strStart;
                if (countedPositions.add(posKey)) {
                    rawMatches.add(new RawMatch(term.getSourceTerm(), term.getTargetTerm(),
                        strStart, strEnd, term.getStatus(), slot.matchedText));
                }
            }
        });
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
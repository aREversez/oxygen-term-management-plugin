package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.service.DocumentScanner.ScanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Aho-Corasick scanner must return exactly what the per-term regex scanner it replaced
 * returned. {@link ReferenceScanner} is that old scanner, kept verbatim in the test tree; these
 * tests run both over the same inputs and compare every field of every result, in order.
 */
class DocumentScannerDifferentialTest {

    private final DocumentScanner scanner = new DocumentScanner();
    private final ReferenceScanner reference = new ReferenceScanner();

    /**
     * Small, collision-heavy alphabet so terms overlap, nest and repeat constantly: Latin in both
     * cases, digits, separators, markup characters, Greek (final sigma), Turkish dotted I, sharp s,
     * accented letters, CJK, and supplementary-plane letters that have case (Deseret).
     */
    private static final String[] ALPHABET = {
        "a", "A", "b", "B", "c", "C", "a", "a", "1", " ", " ", "-", ".", "&", ";", "<", ">",
        "σ", "ς", "Σ", "İ", "ı", "ß", "é", "É",
        "水", "模", "型",
        "𐐀", "𐐨", "𝐀"
    };

    private static final String[] SNIPPETS = {
        "&amp;", "&lt;", "&gt;", "&quot;", "&apos;", "<b x='aa'>", "</b>", "<!-- aa -->", "&#x41;"
    };

    private static String randomString(Random r, int minLen, int maxLen, boolean withSnippets) {
        StringBuilder sb = new StringBuilder();
        int len = minLen + r.nextInt(maxLen - minLen + 1);
        for (int i = 0; i < len; i++) {
            if (withSnippets && r.nextInt(12) == 0) {
                sb.append(SNIPPETS[r.nextInt(SNIPPETS.length)]);
            } else {
                sb.append(ALPHABET[r.nextInt(ALPHABET.length)]);
            }
        }
        return sb.toString();
    }

    private static List<TermEntry> randomTerms(Random r) {
        int n = r.nextInt(14);
        List<TermEntry> terms = new ArrayList<>();
        TermStatus[] statuses = {null, TermStatus.PREFERRED, TermStatus.ADMITTED, TermStatus.DEPRECATED};
        for (int i = 0; i < n; i++) {
            String src = r.nextInt(15) == 0 ? (r.nextBoolean() ? null : "") : randomString(r, 1, 5, r.nextInt(6) == 0);
            String tgt = r.nextInt(15) == 0 ? (r.nextBoolean() ? null : "") : randomString(r, 1, 5, r.nextInt(6) == 0);
            TermEntry e = new TermEntry(src, tgt);
            e.setStatus(statuses[r.nextInt(statuses.length)]);
            terms.add(e);
            // Exact duplicates, and the same source with another target, are what real termbases
            // (and several enabled termbases at once) contain.
            if (r.nextInt(5) == 0) {
                TermEntry dup = new TermEntry(src, r.nextBoolean() ? tgt : randomString(r, 1, 4, false));
                dup.setStatus(statuses[r.nextInt(statuses.length)]);
                terms.add(dup);
            }
            // Same text in another case: one pattern under case folding, two under case sensitivity.
            if (src != null && !src.isEmpty() && r.nextInt(6) == 0) {
                TermEntry cased = new TermEntry(src.toUpperCase(), tgt);
                cased.setStatus(statuses[r.nextInt(statuses.length)]);
                terms.add(cased);
            }
        }
        return terms;
    }

    /** Author-mode segments over {@code text}: it is cut into pieces and each piece is given a gap in the document. */
    private static List<int[]> randomSegments(Random r, String text) {
        List<int[]> segs = new ArrayList<>();
        int strPos = 0;
        int authPos = r.nextInt(5);
        while (strPos < text.length()) {
            int len = Math.min(1 + r.nextInt(40), text.length() - strPos);
            segs.add(new int[] {authPos, strPos, len});
            strPos += len;
            authPos += len + r.nextInt(4);
        }
        return segs;
    }

    private static String describe(List<ScanResult> rs) {
        StringBuilder sb = new StringBuilder();
        for (ScanResult m : rs) {
            sb.append('[').append(m.sourceTerm).append('|').append(m.targetTerm).append('|')
              .append(m.startOffset).append('-').append(m.endOffset).append('|').append(m.status)
              .append('|').append(m.matchedText).append(']');
        }
        return sb.toString();
    }

    private void assertSame(String label, String text, List<TermEntry> terms, boolean textMode,
            List<int[]> segs, boolean cs, ScanDirection dir) {
        List<ScanResult> expected = reference.scan(text, terms, textMode, segs, cs, dir);
        List<ScanResult> actual = scanner.scan(text, terms, textMode, segs, cs, dir);
        assertEquals(describe(expected), describe(actual), label);
    }

    @Test
    void randomInputs_matchTheReferenceScanner_inEveryMode() {
        Random r = new Random(20261004L);
        int rounds = 3000;
        for (int round = 0; round < rounds; round++) {
            List<TermEntry> terms = randomTerms(r);
            String text = randomString(r, 0, 120, true);
            boolean textMode = r.nextBoolean();
            boolean cs = r.nextBoolean();
            ScanDirection dir = r.nextBoolean() ? ScanDirection.SOURCE : ScanDirection.TARGET;
            List<int[]> segs = textMode ? Collections.emptyList() : randomSegments(r, text);
            assertSame("round " + round + " text=" + text + " terms=" + terms, text, terms,
                textMode, segs, cs, dir);
        }
    }

    @Test
    void everyModeCombination_onTheSameInput_matches() {
        Random r = new Random(77L);
        for (int round = 0; round < 300; round++) {
            List<TermEntry> terms = randomTerms(r);
            String text = randomString(r, 20, 150, true);
            for (boolean textMode : new boolean[] {true, false}) {
                for (boolean cs : new boolean[] {true, false}) {
                    for (ScanDirection dir : ScanDirection.values()) {
                        List<int[]> segs = textMode ? Collections.emptyList() : randomSegments(r, text);
                        assertSame("round " + round + " text=" + text, text, terms, textMode, segs, cs, dir);
                    }
                }
            }
        }
    }

    private List<TermEntry> terms(String... sources) {
        List<TermEntry> out = new ArrayList<>();
        for (String s : sources) {
            out.add(new TermEntry(s, "t-" + s));
        }
        return out;
    }

    @Test
    void selfOverlappingTerm_isMatchedWithoutOverlap_likeTheRegex() {
        // "aa" in "aaaa": the regex finds [0,2) and [2,4), never [1,3).
        assertSame("aaaa", "aaaa", terms("aa"), true, Collections.emptyList(), false, ScanDirection.SOURCE);
        // The boundary rule rejects a hit but still consumes it: "aa" in "aaa b" is rejected at 0
        // (letter follows) and the next try starts at 2, where no "aa" fits.
        assertSame("aaa b", "aaa b", terms("aa"), true, Collections.emptyList(), false, ScanDirection.SOURCE);
        assertSame("aaaaa", "aaaaa", terms("aa", "aaa"), true, Collections.emptyList(), false, ScanDirection.SOURCE);
    }

    @Test
    void termNestedInAnotherTerm_andSharedPrefixes_matchTheReference() {
        assertSame("nested", "mesh size and mesh", terms("mesh", "mesh size", "size", "e"),
            true, Collections.emptyList(), false, ScanDirection.SOURCE);
    }

    @Test
    void supplementaryPlaneCase_isFoldedLikeTheRegex() {
        // Deseret capital / small letter long i: different UTF-16 text, same letter in two cases.
        String upper = "𐐀";
        String lower = "𐐨";
        assertSame("supp", "x " + lower + " y " + upper, terms(upper), true, Collections.emptyList(), false,
            ScanDirection.SOURCE);
        assertSame("supp-cs", "x " + lower + " y " + upper, terms(upper), true, Collections.emptyList(), true,
            ScanDirection.SOURCE);
    }

    @Test
    void emptyInputs_matchTheReference() {
        assertSame("empty text", "", terms("a"), true, Collections.emptyList(), false, ScanDirection.SOURCE);
        assertSame("no terms", "abc", new ArrayList<>(), false, Collections.emptyList(), false, ScanDirection.TARGET);
    }
}

package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.service.DocumentScanner.ScanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DocumentScannerInflectionTest {

    private final DocumentScanner scanner = new DocumentScanner();
    private final ReferenceScanner reference = new ReferenceScanner();

    private List<ScanResult> scan(String text, List<TermEntry> terms, boolean inflect) {
        return scanner.scan(text, terms, true, Collections.emptyList(), false, ScanDirection.TARGET, inflect);
    }

    private static List<TermEntry> one(String target, TermStatus status) {
        TermEntry e = new TermEntry("网格", target);
        e.setStatus(status);
        List<TermEntry> l = new ArrayList<>();
        l.add(e);
        return l;
    }

    @Test
    void optionOff_doesNotMatchTheInflectedForm() {
        assertTrue(scan("two grids here", one("grid", null), false).isEmpty());
    }

    @Test
    void optionOn_matchesPlural_andReportsTheWordActuallyPresent() {
        List<ScanResult> r = scan("two grids here", one("grid", null), true);
        assertEquals(1, r.size());
        assertEquals(4, r.get(0).startOffset);
        assertEquals(9, r.get(0).endOffset);          // covers "grids", not just "grid"
        assertEquals("grid", r.get(0).matchedText);   // the entry's own text
        assertEquals("网格", r.get(0).sourceTerm);
    }

    @Test
    void optionOn_stillMatchesTheExactForm() {
        List<ScanResult> r = scan("the grid here", one("grid", null), true);
        assertEquals(1, r.size());
        assertEquals(4, r.get(0).startOffset);
        assertEquals(8, r.get(0).endOffset);
    }

    @Test
    void optionOn_matchesPastAndParticipleForms() {
        assertEquals(1, scan("the mesh was gridded finely", one("grid", null), true).size());
        assertEquals(1, scan("it keeps gridding", one("grid", null), true).size());
        assertEquals(1, scan("solved quickly", one("solve", null), true).size());
        assertEquals(1, scan("solving it", one("solve", null), true).size());
    }

    @Test
    void deprecatedStatus_isKeptOnTheInflectedHit() {
        List<ScanResult> r = scan("the grids", one("grid", TermStatus.DEPRECATED), true);
        assertEquals(1, r.size());
        assertEquals(TermStatus.DEPRECATED, r.get(0).status);
    }

    @Test
    void inflectedForm_stillHonoursTheWordBoundary() {
        assertTrue(scan("subgrids", one("grid", null), true).isEmpty());
        assertTrue(scan("gridsx", one("grid", null), true).isEmpty());
    }

    @Test
    void multiWordTerm_variesOnlyTheLastWord() {
        assertEquals(1, scan("fine mesh sizes", one("mesh size", null), true).size());
        assertTrue(scan("fine meshes size", one("mesh size", null), true).isEmpty());
    }

    @Test
    void irregularPlural_isFound() {
        assertEquals(1, scan("the stiffness matrices", one("matrix", null), true).size());
    }

    @Test
    void worksInSourceDirectionToo() {
        TermEntry e = new TermEntry("grid", "网格");
        List<TermEntry> terms = new ArrayList<>();
        terms.add(e);
        List<ScanResult> r = scanner.scan("two grids", terms, true, Collections.emptyList(), false,
            ScanDirection.SOURCE, true);
        assertEquals(1, r.size());
        assertEquals("grid", r.get(0).matchedText);
    }

    @Test
    void sharedPattern_neitherEntryIsReportedTwice() {
        // "Grid" and "grid" fold to one pattern; each entry still yields exactly one hit.
        List<TermEntry> terms = new ArrayList<>();
        terms.add(new TermEntry("a", "Grid"));
        terms.add(new TermEntry("b", "grid"));
        List<ScanResult> r = scan("grids", terms, true);
        assertEquals(2, r.size());
    }

    @Test
    void entryAndItsLongerForm_atTheSameSpot_reportTheLongerOne() {
        // CJK in the term disables the boundary rule, so "...mesh" also matches inside "...meshes";
        // the hit must be the whole word present, not the shorter prefix.
        TermEntry e = new TermEntry("x", "网格 mesh");
        List<TermEntry> terms = new ArrayList<>();
        terms.add(e);
        List<ScanResult> r = scan("网格 meshes", terms, true);
        assertEquals(1, r.size());
        assertEquals(0, r.get(0).startOffset);
        assertEquals("网格 meshes".length(), r.get(0).endOffset);
    }

    @Test
    void optionOff_equalsTheReferenceScanner_ontoRandomInput() {
        Random r = new Random(5L);
        String[] words = {"grid", "grids", "mesh", "meshes", "Mesh", "size", "sizes", "solve", "solved", "x", " ", "-"};
        for (int round = 0; round < 500; round++) {
            List<TermEntry> terms = new ArrayList<>();
            for (int i = 0; i < 1 + r.nextInt(6); i++) {
                String t = words[r.nextInt(words.length)] + (r.nextInt(3) == 0 ? " " + words[r.nextInt(words.length)] : "");
                terms.add(new TermEntry("s" + i, t));
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 25; i++) sb.append(words[r.nextInt(words.length)]).append(' ');
            String text = sb.toString();
            for (ScanDirection d : ScanDirection.values()) {
                for (boolean cs : new boolean[] {false, true}) {
                    assertEquals(describe(reference.scan(text, terms, true, Collections.emptyList(), cs, d, false)),
                        describe(scanner.scan(text, terms, true, Collections.emptyList(), cs, d, false)));
                }
            }
        }
    }

    @Test
    void optionOn_equalsTheReferenceScanner_withInflectionsExpandedAsExtraRegexRuns() {
        Random r = new Random(11L);
        String[] words = {"grid", "grids", "gridded", "mesh", "meshes", "Mesh", "MESHES", "size", "sizes", "sized",
            "solve", "solved", "solving", "study", "studies", "matrix", "matrices", "CPU", "CPUs",
            "网格", "x", "&amp;", "<b>"};
        String[] seps = {" ", " ", "-", ""};
        TermStatus[] st = {null, TermStatus.DEPRECATED, TermStatus.ADMITTED};
        for (int round = 0; round < 1500; round++) {
            List<TermEntry> terms = new ArrayList<>();
            int n = 1 + r.nextInt(8);
            for (int i = 0; i < n; i++) {
                String t = words[r.nextInt(words.length)];
                if (r.nextInt(3) == 0) t = words[r.nextInt(words.length)] + seps[r.nextInt(seps.length)] + t;
                TermEntry e = new TermEntry("s" + r.nextInt(4), t);
                e.setStatus(st[r.nextInt(st.length)]);
                terms.add(e);
            }
            StringBuilder sb = new StringBuilder();
            int len = 5 + r.nextInt(30);
            for (int i = 0; i < len; i++) sb.append(words[r.nextInt(words.length)]).append(seps[r.nextInt(seps.length)]);
            String text = sb.toString();
            boolean textMode = r.nextBoolean();
            boolean cs = r.nextBoolean();
            ScanDirection d = r.nextBoolean() ? ScanDirection.SOURCE : ScanDirection.TARGET;
            List<int[]> segs = textMode ? Collections.emptyList()
                : Collections.singletonList(new int[] {3, 0, text.length()});
            if (d == ScanDirection.SOURCE) {
                // search the source side: swap so the inflecting text is on it
                for (TermEntry e : terms) { String a = e.getSourceTerm(); e.setSourceTerm(e.getTargetTerm()); e.setTargetTerm(a); }
            }
            assertEquals(describe(reference.scan(text, terms, textMode, segs, cs, d, true)),
                describe(scanner.scan(text, terms, textMode, segs, cs, d, true)),
                "round " + round + " text=" + text + " terms=" + terms);
        }
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
}

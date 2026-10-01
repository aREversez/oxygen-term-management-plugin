package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermMatchUtilsTest {

    @Test
    void buildMatchPattern_latinTermIsNotBlockedByAdjacentCjkCharacters() {
        // "使用FEA。": the old (?<![\p{L}]) counted Han as a letter, so FEA never matched
        // in mixed CJK/Latin text, which CAE documents are full of.
        java.util.regex.Pattern p = TermMatchUtils.buildMatchPattern("FEA");
        assertTrue(p.matcher("使用FEA。").find());
        assertTrue(p.matcher("FEA分析").find());
        assertTrue(p.matcher("カタカナFEAです").find());
        // Adjacent digits keep working as before.
        assertTrue(p.matcher("FEA2024 release").find());
    }

    @Test
    void buildMatchPattern_latinTermStillRejectsAdjacentLatinLetters() {
        java.util.regex.Pattern p = TermMatchUtils.buildMatchPattern("FEA");
        assertFalse(p.matcher("CafeFEA").find());
        assertFalse(p.matcher("FEAx").find());
        assertTrue(p.matcher("the FEA model").find());
    }

    @Test
    void buildMatchPattern_caseSensitiveFlagControlsCaseMatching() {
        assertTrue(TermMatchUtils.buildMatchPattern("FEA", false).matcher("fea").find());
        assertFalse(TermMatchUtils.buildMatchPattern("FEA", true).matcher("fea").find());
        assertTrue(TermMatchUtils.buildMatchPattern("FEA", true).matcher("FEA").find());
        // The single-argument overload keeps the historic case-insensitive behaviour.
        assertTrue(TermMatchUtils.buildMatchPattern("FEA").matcher("fea").find());
        // Boundaries keep working with the flag on.
        assertFalse(TermMatchUtils.buildMatchPattern("FEA", true).matcher("FEAx").find());
    }

    private static Predicate<String> known(String... terms) {
        Set<String> set = Set.of(terms);
        return s -> set.contains(s.toLowerCase(Locale.ROOT));
    }

    @Test
    void findKnownTerms_segmentsUnspacedChineseIntoTwoTerms() {
        Set<String> found = TermMatchUtils.findKnownTerms("机器学习模型", known("机器学习", "模型"), 30, 2);
        assertEquals(List.of("机器学习", "模型"), List.copyOf(found));
    }

    @Test
    void findKnownTerms_prefersTheLongestMatch() {
        // "机器学习" is one term; it must not be split into "机器" + "学习".
        Set<String> found = TermMatchUtils.findKnownTerms("机器学习", known("机器", "学习", "机器学习"), 30, 2);
        assertEquals(List.of("机器学习"), List.copyOf(found));
    }

    @Test
    void findKnownTerms_skipsUnknownCharactersBetweenTerms() {
        Set<String> found = TermMatchUtils.findKnownTerms("使用API接口", known("api", "接口"), 30, 2);
        assertEquals(List.of("api", "接口"), List.copyOf(found));
    }

    @Test
    void findKnownTerms_stopsAtLimit() {
        Set<String> found = TermMatchUtils.findKnownTerms("甲乙丙丁", known("甲", "乙", "丙", "丁"), 30, 2);
        assertEquals(2, found.size());
    }

    @Test
    void findKnownTerms_respectsMaxTermLength() {
        Set<String> found = TermMatchUtils.findKnownTerms("机器学习", known("机器学习"), 3, 2);
        assertEquals(0, found.size());
    }

    @Test
    void findKnownTerms_spaceDelimitedTokensMatchOnlyAsWholeWords() {
        assertEquals(2, TermMatchUtils.findKnownTerms("mesh element", known("mesh", "element"), 30, 2).size());
        assertEquals(1, TermMatchUtils.findKnownTerms("mesh elements", known("mesh", "element"), 30, 2).size());
        assertEquals(1, TermMatchUtils.findKnownTerms("Mesh MESH", known("mesh"), 30, 2).size());
    }

    @Test
    void findKnownTerms_handlesSpacedChineseTokens() {
        Set<String> found = TermMatchUtils.findKnownTerms("网格 单元", known("网格", "单元"), 30, 2);
        assertEquals(List.of("网格", "单元"), List.copyOf(found));
    }

    @Test
    void findKnownTerms_handlesSupplementaryCodePoints() {
        // U+20BB7 (𠮷) is a Han character outside the BMP.
        Set<String> found = TermMatchUtils.findKnownTerms("𠮷网格", known("𠮷", "网格"), 30, 2);
        assertEquals(List.of("𠮷", "网格"), List.copyOf(found));
    }

    @Test
    void matchesSearch_matchesSourceOrTargetIgnoringCase() {
        TermEntry term = new TermEntry("网格单元", "Mesh Element");
        assertTrue(TermMatchUtils.matchesSearch(term, "网格"));
        assertTrue(TermMatchUtils.matchesSearch(term, "mesh el"));
        assertTrue(TermMatchUtils.matchesSearch(term, "ELEMENT".toLowerCase(Locale.ROOT)));
        assertFalse(TermMatchUtils.matchesSearch(term, "node"));
    }

    @Test
    void matchesSearch_toleratesNullSourceOrTarget() {
        assertTrue(TermMatchUtils.matchesSearch(new TermEntry(null, "node"), "nod"));
        assertTrue(TermMatchUtils.matchesSearch(new TermEntry("节点", null), "节"));
        assertFalse(TermMatchUtils.matchesSearch(new TermEntry(null, null), "x"));
    }

    @Test
    void matchesSearch_doesNotDependOnTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertTrue(TermMatchUtils.matchesSearch(new TermEntry("INFO", "x"), "info"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}

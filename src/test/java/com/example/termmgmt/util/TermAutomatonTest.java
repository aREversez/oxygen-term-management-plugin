package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TermAutomatonTest {

    private static List<String> run(String text, String... patterns) {
        TermAutomaton.Builder b = new TermAutomaton.Builder();
        for (String p : patterns) {
            b.add(p.toCharArray());
        }
        List<String> hits = new ArrayList<>();
        b.build().scan(text.toCharArray(), (id, s, e) -> hits.add(patterns[id] + "@" + s + "-" + e));
        return hits;
    }

    @Test
    void reportsEveryOccurrence_includingOverlappingOnes() {
        assertEquals(List.of("aa@0-2", "aa@1-3", "aa@2-4"), run("aaaa", "aa"));
    }

    @Test
    void reportsNestedAndSuffixPatternsEndingAtTheSamePlace() {
        // "she" ends at 4 with its proper suffixes "he" and "e".
        List<String> hits = run("ushers", "he", "she", "hers", "e");
        assertTrue(hits.contains("she@1-4"));
        assertTrue(hits.contains("he@2-4"));
        assertTrue(hits.contains("e@3-4"));
        assertTrue(hits.contains("hers@2-6"));
        assertEquals(4, hits.size());
    }

    @Test
    void failureLinksRecoverAfterAMismatch() {
        // After "abcab" the automaton must fall back to "ab" to see "abcd" start again.
        assertEquals(List.of("abcd@3-7"), run("abcabcd", "abcd"));
        assertEquals(List.of("abab@0-4", "abab@2-6"), run("ababab", "abab"));
    }

    @Test
    void identicalPatternsShareOneId() {
        TermAutomaton.Builder b = new TermAutomaton.Builder();
        int first = b.add("term".toCharArray());
        int again = b.add("term".toCharArray());
        int other = b.add("terms".toCharArray());
        assertEquals(first, again);
        assertNotEquals(first, other);
        assertEquals(2, b.build().patternCount());
    }

    @Test
    void emptyPatternIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TermAutomaton.Builder().add(new char[0]));
    }

    @Test
    void noPatternsOrNoText_reportNothing() {
        assertTrue(run("anything").isEmpty());
        assertTrue(run("", "a").isEmpty());
    }

    @Test
    void manyDistinctFirstCharacters_atTheRoot_areAllFound() {
        // A CJK-style termbase: thousands of different first characters.
        TermAutomaton.Builder b = new TermAutomaton.Builder();
        StringBuilder text = new StringBuilder();
        for (char c = '一'; c < '一' + 3000; c++) {
            b.add(new char[] {c, '模'});
            text.append(c).append('模');
        }
        int[] count = {0};
        b.build().scan(text.toString().toCharArray(), (id, s, e) -> count[0]++);
        assertEquals(3000, count[0]);
    }

    @Test
    void highCodeUnits_includingTheLastBmpChar_areHandled() {
        TermAutomaton.Builder b = new TermAutomaton.Builder();
        b.add(new char[] {'￿', 'a'});
        List<Integer> starts = new ArrayList<>();
        b.build().scan(new char[] {'x', '￿', 'a'}, (id, s, e) -> starts.add(s));
        assertEquals(List.of(1), starts);
    }

    @Test
    void fold_keepsEveryCharacterAtItsIndex() {
        String s = "AİbßΣς𐐀水";
        assertEquals(s.length(), TermAutomaton.fold(s).length);
    }

    @Test
    void fold_matchesTheRegexCaseRule() {
        // Pattern.CASE_INSENSITIVE | UNICODE_CASE compares lower(upper(c)); so must fold.
        String[] samples = {"Grid", "GRID", "grid", "Σ", "σ", "ς", "É", "é",
            "𐐀", "𐐨", "İ", "ı", "ß", "i", "I"};
        for (String a : samples) {
            for (String b : samples) {
                boolean viaRegex = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(a),
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                    .matcher(b).matches();
                boolean viaFold = java.util.Arrays.equals(TermAutomaton.fold(a), TermAutomaton.fold(b));
                assertEquals(viaRegex, viaFold, "a=" + a + " b=" + b);
            }
        }
    }

    @Test
    void interruptedThread_stopsTheScanEarly() {
        TermAutomaton.Builder b = new TermAutomaton.Builder();
        b.add("a".toCharArray());
        char[] text = new char[100_000];
        java.util.Arrays.fill(text, 'a');
        int[] count = {0};
        Thread.currentThread().interrupt();
        try {
            b.build().scan(text, (id, s, e) -> count[0]++);
        } finally {
            Thread.interrupted();   // clear the flag for the other tests
        }
        assertEquals(0, count[0]);
    }
}

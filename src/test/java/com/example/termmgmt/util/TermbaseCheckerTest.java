package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.util.TermbaseChecker.Issue;
import com.example.termmgmt.util.TermbaseChecker.Severity;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Step 4.1: unit tests for the termbase quality checker rules. */
class TermbaseCheckerTest {

    @Test
    void emptySource_detected() {
        List<TermEntry> terms = List.of(
            new TermEntry("", "target"),
            new TermEntry(null, "target2"),
            new TermEntry("  ", "target3"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertEquals(3, issues.size());
        for (Issue i : issues) {
            assertEquals("EMPTY_SOURCE", i.rule);
            assertEquals(Severity.ERROR, i.severity);
        }
    }

    @Test
    void emptyTarget_detected() {
        List<TermEntry> terms = List.of(
            new TermEntry("source", ""),
            new TermEntry("source2", null),
            new TermEntry("source3", "   "));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertEquals(3, issues.size());
        for (Issue i : issues) {
            assertEquals("EMPTY_TARGET", i.rule);
            assertEquals(Severity.WARNING, i.severity);
        }
    }

    @Test
    void multiTarget_sameSourceDifferentTargets() {
        List<TermEntry> terms = List.of(
            new TermEntry("mesh", "网格"),
            new TermEntry("mesh", "网格"),  // identical target - no issue
            new TermEntry("mesh", "网"));   // different target - all 3 flagged
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        long multi = issues.stream().filter(i -> "MULTI_TARGET".equals(i.rule)).count();
        assertEquals(3, multi, "all entries sharing the source should be flagged");
    }

    @Test
    void multiTarget_notFlaggedWhenAllTargetsIdentical() {
        List<TermEntry> terms = List.of(
            new TermEntry("mesh", "网格"),
            new TermEntry("mesh", "网格"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertTrue(issues.stream().noneMatch(i -> "MULTI_TARGET".equals(i.rule)));
    }

    @Test
    void caseOnlyDuplicate_detected() {
        List<TermEntry> terms = List.of(
            new TermEntry("FEA", "有限元素法"),
            new TermEntry("fea", "有限元素法"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        long caseDupes = issues.stream().filter(i -> "CASE_DUPLICATE".equals(i.rule)).count();
        assertEquals(2, caseDupes);
    }

    @Test
    void caseOnlyDuplicate_notFlaggedWhenExactMatch() {
        List<TermEntry> terms = List.of(
            new TermEntry("FEA", "a"),
            new TermEntry("FEA", "a"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertTrue(issues.stream().noneMatch(i -> "CASE_DUPLICATE".equals(i.rule)));
    }

    @Test
    void consecutiveWhitespace_inSource() {
        List<TermEntry> terms = List.of(new TermEntry("bending  stiffness", "弯曲刚度"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertEquals(1, issues.size());
        assertEquals("CONSECUTIVE_WS", issues.get(0).rule);
    }

    @Test
    void consecutiveWhitespace_inTarget() {
        List<TermEntry> terms = List.of(new TermEntry("mesh", "网格\t\t详情"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertEquals(1, issues.size());
        assertEquals("CONSECUTIVE_WS", issues.get(0).rule);
    }

    @Test
    void consecutiveWhitespace_singleSpaceOk() {
        List<TermEntry> terms = List.of(new TermEntry("bending stiffness", "弯曲刚度"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertTrue(issues.stream().noneMatch(i -> "CONSECUTIVE_WS".equals(i.rule)));
    }

    @Test
    void crossTermbase_conflict() {
        Map<String, List<TermEntry>> tbs = new LinkedHashMap<>();
        tbs.put("a.csv", List.of(new TermEntry("mesh", "网格")));
        tbs.put("b.csv", List.of(new TermEntry("mesh", "网")));
        List<Issue> issues = TermbaseChecker.checkCrossTermbase(tbs);
        assertEquals(1, issues.size());
        assertEquals("CROSS_TB_CONFLICT", issues.get(0).rule);
    }

    @Test
    void crossTermbase_identicalDuplicate() {
        Map<String, List<TermEntry>> tbs = new LinkedHashMap<>();
        tbs.put("a.csv", List.of(new TermEntry("mesh", "网格")));
        tbs.put("b.csv", List.of(new TermEntry("mesh", "网格")));
        List<Issue> issues = TermbaseChecker.checkCrossTermbase(tbs);
        assertEquals(1, issues.size());
        assertEquals("CROSS_TB_DUPLICATE", issues.get(0).rule);
    }

    @Test
    void crossTermbase_noConflictWhenSourcesDiffer() {
        Map<String, List<TermEntry>> tbs = new LinkedHashMap<>();
        tbs.put("a.csv", List.of(new TermEntry("mesh", "网格")));
        tbs.put("b.csv", List.of(new TermEntry("FEA", "有限元素法")));
        List<Issue> issues = TermbaseChecker.checkCrossTermbase(tbs);
        assertTrue(issues.isEmpty());
    }

    @Test
    void cleanEntry_noIssues() {
        List<TermEntry> terms = List.of(new TermEntry("mesh", "网格"));
        List<Issue> issues = TermbaseChecker.check(terms, "test.csv");
        assertTrue(issues.isEmpty());
    }

    /**
     * The UI renders the Message column from issue.messageKey against the resource bundle,
     * falling back to the English literal only when the key is missing. That contract breaks
     * silently if a rule ships a key no bundle defines, or splices fewer arguments than the
     * template placeholders, so pin both against the default (English) bundle.
     */
    @Test
    void everyIssue_messageKeyResolvesAndArgsMatchTemplate() throws Exception {
        Properties en = new Properties();
        try (InputStream in = TermbaseCheckerTest.class.getResourceAsStream("/i18n/messages.properties")) {
            assertNotNull(in, "default bundle must be on the classpath");
            en.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        List<Issue> all = new ArrayList<>();
        all.addAll(TermbaseChecker.check(List.of(new TermEntry("", "t")), "a.csv"));
        all.addAll(TermbaseChecker.check(List.of(new TermEntry("s", "")), "a.csv"));
        all.addAll(TermbaseChecker.check(List.of(new TermEntry("bending  stiffness", "t")), "a.csv"));
        all.addAll(TermbaseChecker.check(List.of(new TermEntry("s", "t		x")), "a.csv"));
        all.addAll(TermbaseChecker.check(List.of(new TermEntry("mesh", "网格"), new TermEntry("mesh", "网")), "a.csv"));
        all.addAll(TermbaseChecker.check(List.of(new TermEntry("FEA", "a"), new TermEntry("fea", "a")), "a.csv"));
        Map<String, List<TermEntry>> tbs = new LinkedHashMap<>();
        tbs.put("a.csv", List.of(new TermEntry("mesh", "网格")));
        tbs.put("b.csv", List.of(new TermEntry("mesh", "网")));
        all.addAll(TermbaseChecker.checkCrossTermbase(tbs));
        tbs = new LinkedHashMap<>();
        tbs.put("a.csv", List.of(new TermEntry("mesh", "网格")));
        tbs.put("b.csv", List.of(new TermEntry("mesh", "网格")));
        all.addAll(TermbaseChecker.checkCrossTermbase(tbs));

        assertFalse(all.isEmpty());
        Pattern ph = Pattern.compile("\\{(\\d+)\\}");
        for (Issue i : all) {
            assertNotNull(i.messageKey, i.rule + " carries no messageKey");
            String template = en.getProperty(i.messageKey);
            assertNotNull(template, i.rule + " -> missing key " + i.messageKey);
            assertFalse(template.isBlank(), i.rule + " -> blank value for " + i.messageKey);
            int maxIndex = -1;
            int count = 0;
            Matcher m = ph.matcher(template);
            while (m.find()) {
                maxIndex = Math.max(maxIndex, Integer.parseInt(m.group(1)));
                count++;
            }
            assertEquals(count, i.messageArgs.length, i.rule + " argument count vs template " + template);
            assertTrue(maxIndex < i.messageArgs.length, i.rule + " placeholders exceed args");
        }
    }
}

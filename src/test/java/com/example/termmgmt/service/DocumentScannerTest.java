package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.service.DocumentScanner.ScanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentScannerTest {

    private final DocumentScanner scanner = new DocumentScanner();

    @Test
    void textMode_singleMatch_returnsCorrectOffsets() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"));
        List<ScanResult> results = scanner.scan("say hello world", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals("hello", results.get(0).sourceTerm);
        assertEquals("你好", results.get(0).targetTerm);
        assertEquals(4, results.get(0).startOffset);
        assertEquals(9, results.get(0).endOffset);
    }

    @Test
    void textMode_multipleOccurrences_allReturned() {
        List<TermEntry> terms = List.of(new TermEntry("cat", "猫"));
        List<ScanResult> results = scanner.scan("cat and dog and cat", terms, true, Collections.emptyList());

        assertEquals(2, results.size());
        assertEquals(0, results.get(0).startOffset);
        assertEquals(16, results.get(1).startOffset);
    }

    @Test
    void textMode_xmlEscaping_matchesEscapedText() {
        List<TermEntry> terms = List.of(new TermEntry("AT&T", "company"));
        List<ScanResult> results = scanner.scan("use AT&amp;T services", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals("AT&T", results.get(0).sourceTerm);
        assertEquals(4, results.get(0).startOffset);
        assertEquals(12, results.get(0).endOffset);
    }

    @Test
    void textMode_cjkTerm_matchesWithoutWordBoundaries() {
        List<TermEntry> terms = List.of(new TermEntry("数据", "data"));
        List<ScanResult> results = scanner.scan("处理数据和分析", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals(2, results.get(0).startOffset);
        assertEquals(4, results.get(0).endOffset);
    }

    @Test
    void textMode_latinTerm_wordBoundaryExcludesPartial() {
        List<TermEntry> terms = List.of(new TermEntry("cat", "猫"));
        List<ScanResult> results = scanner.scan("catalog category cat", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals("cat", results.get(0).sourceTerm);
        assertEquals(17, results.get(0).startOffset);
    }

    @Test
    void emptyTermsList_returnsEmpty() {
        List<ScanResult> results = scanner.scan("some text", Collections.emptyList(), true, Collections.emptyList());
        assertTrue(results.isEmpty());
    }

    @Test
    void emptyDocumentText_returnsEmpty() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"));
        List<ScanResult> results = scanner.scan("", terms, true, Collections.emptyList());
        assertTrue(results.isEmpty());
    }

    @Test
    void noMatch_returnsEmpty() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"));
        List<ScanResult> results = scanner.scan("goodbye world", terms, true, Collections.emptyList());
        assertTrue(results.isEmpty());
    }

    @Test
    void authorMode_simpleSegment_mapsOffsetsCorrectly() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"));

        // One segment: author offset 100 maps to string offset 0, length 20
        List<int[]> segments = new ArrayList<>();
        segments.add(new int[]{100, 0, 20});

        List<ScanResult> results = scanner.scan("say hello world", terms, false, segments);

        assertEquals(1, results.size());
        assertEquals(104, results.get(0).startOffset);
        assertEquals(109, results.get(0).endOffset);
    }

    @Test
    void authorMode_multipleSegments_mapsAcrossSegments() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"), new TermEntry("world", "世界"));

        // seg 1: covers str 0..9  → "hello big " (author offset 500)
        // seg 2: covers str 10..19 → "world"       (author offset 600)
        List<int[]> segments = new ArrayList<>();
        segments.add(new int[]{500, 0, 10});
        segments.add(new int[]{600, 10, 10});

        String text = "hello big world";
        // "hello" at str 0 → seg 1 → 500 + 0 = 500
        // "world" at str 10 → seg 2 → 600 + (10 - 10) = 600

        List<ScanResult> results = scanner.scan(text, terms, false, segments);

        assertEquals(2, results.size());

        ScanResult helloResult = results.get(0).sourceTerm.equals("hello") ? results.get(0) : results.get(1);
        assertEquals(500, helloResult.startOffset);
        assertEquals(505, helloResult.endOffset);

        ScanResult worldResult = results.get(0).sourceTerm.equals("world") ? results.get(0) : results.get(1);
        assertEquals(600, worldResult.startOffset);
        assertEquals(605, worldResult.endOffset);
    }

    @Test
    void authorMode_segmentBoundary_crossSegmentMatch() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"));

        // "say hello world" = 15 chars. Split after char 7 ("say hel")
        // Segment 1: author 500..507, str 0..7   → "say hel"
        // Segment 2: author 600..607, str 7..8   → "lo world"
        // "hello" straddles boundary: str 4..9
        // start (4) in seg 1, end (9) in seg 2
        List<int[]> segments = new ArrayList<>();
        segments.add(new int[]{500, 0, 7});
        segments.add(new int[]{600, 7, 8});

        List<ScanResult> results = scanner.scan("say hello world", terms, false, segments);

        // start: seg 1 → 500 + (4 - 0) = 504
        // end: seg 2 → 600 + (9 - 7) = 602
        assertEquals(1, results.size());
        assertEquals(504, results.get(0).startOffset);
        assertEquals(602, results.get(0).endOffset);
    }

    @Test
    void authorMode_noApplicableSegment_skipsMatch() {
        List<TermEntry> terms = List.of(new TermEntry("hello", "你好"));

        // Segment covers only "say " (str 0..4), match "hello" at str 4..9 not covered
        List<int[]> segments = new ArrayList<>();
        segments.add(new int[]{100, 0, 4});

        List<ScanResult> results = scanner.scan("say hello", terms, false, segments);

        assertTrue(results.isEmpty());
    }

    @Test
    void mixedCase_stillMatches() {
        List<TermEntry> terms = List.of(new TermEntry("Hello", "你好"));
        List<ScanResult> results = scanner.scan("say hello world", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals("Hello", results.get(0).sourceTerm);
    }

    @Test
    void textMode_latinTermNextToHan_isRecognized() {
        List<TermEntry> terms = List.of(new TermEntry("FEA", "有限要素法"));
        List<ScanResult> results = scanner.scan("使用FEA。", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals(2, results.get(0).startOffset);
        assertEquals(5, results.get(0).endOffset);
    }

    @Test
    void textMode_latinTermAdjacentToNonBmpLetter_isRejected() {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A is a letter outside the BMP. A regex
        // lookbehind only inspects one UTF-16 code unit (a surrogate, category Cs),
        // so boundary checks baked into the pattern let that prefix + FEA through; the
        // code-point boundary check must not.
        List<TermEntry> terms = List.of(new TermEntry("FEA", "有限要素法"));
        List<ScanResult> results = scanner.scan("\uD835\uDC00FEA and FEA", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        // Only the standalone "FEA" survives: the 𝐀 prefix plus "FEA" plus " and " is 10 UTF-16 units.
        assertEquals(10, results.get(0).startOffset);
    }

    @Test
    void longestMatch_containedShorterMatchesAreDropped() {
        // "计算弯曲刚度时": 弯曲 and 刚度 only ever occur inside 弯曲刚度 here, so the scan
        // must report the long term alone instead of three overlapping hits.
        List<TermEntry> terms = List.of(
            new TermEntry("弯曲", "bending"),
            new TermEntry("刚度", "stiffness"),
            new TermEntry("弯曲刚度", "bending stiffness"));
        List<ScanResult> results = scanner.scan("计算弯曲刚度时", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals("弯曲刚度", results.get(0).sourceTerm);
        assertEquals(2, results.get(0).startOffset);
        assertEquals(6, results.get(0).endOffset);
    }

    @Test
    void longestMatch_occurrenceOutsideTheLongerTermIsStillReported() {
        // Second "弯曲" at 5..7 is not inside any longer match and must survive the filter.
        List<TermEntry> terms = List.of(
            new TermEntry("弯曲", "bending"),
            new TermEntry("弯曲刚度", "bending stiffness"));
        List<ScanResult> results = scanner.scan("弯曲刚度和弯曲", terms, true, Collections.emptyList());

        assertEquals(2, results.size());
        assertEquals("弯曲刚度", results.get(0).sourceTerm);
        assertEquals("弯曲", results.get(1).sourceTerm);
        assertEquals(5, results.get(1).startOffset);
    }

    @Test
    void longestMatch_partialOverlapKeepsBoth() {
        // 弯曲刚度 [0,4) and 刚度矩阵 [2,6) overlap without containing each other.
        List<TermEntry> terms = List.of(
            new TermEntry("弯曲刚度", "bending stiffness"),
            new TermEntry("刚度矩阵", "stiffness matrix"));
        List<ScanResult> results = scanner.scan("弯曲刚度矩阵", terms, true, Collections.emptyList());

        assertEquals(2, results.size());
    }

    @Test
    void longestMatch_identicalSpanFromDifferentEntriesKeepsAll() {
        // Two entries whose sources differ only in case cover the exact same span;
        // neither is longer, so both must be reported.
        List<TermEntry> terms = List.of(
            new TermEntry("Mesh", "网格"),
            new TermEntry("mesh", "网格单元"));
        List<ScanResult> results = scanner.scan("the Mesh model", terms, true, Collections.emptyList());

        assertEquals(2, results.size());
    }

    @Test
    void dedup_sameSourceDifferentTargetAtSamePosition_reportsBoth() {
        // The dedup key used to be source+position only, so the second translation of
        // "mesh" silently disappeared from the results.
        List<TermEntry> terms = List.of(
            new TermEntry("mesh", "网格"),
            new TermEntry("mesh", "网格单元"));
        List<ScanResult> results = scanner.scan("the mesh model", terms, true, Collections.emptyList());

        assertEquals(2, results.size());
        assertEquals("网格", results.get(0).targetTerm);
        assertEquals("网格单元", results.get(1).targetTerm);
        assertEquals(results.get(0).startOffset, results.get(1).startOffset);
    }

    @Test
    void dedup_identicalSourceTargetPositionFromTwoTermbases_keptOnce() {
        // The same (source, target, position) triple arriving through two loaded
        // termbases must still collapse to a single hit.
        List<TermEntry> terms = List.of(
            new TermEntry("mesh", "网格"),
            new TermEntry("mesh", "网格"));
        List<ScanResult> results = scanner.scan("the mesh model", terms, true, Collections.emptyList());

        assertEquals(1, results.size());
    }

    @Test
    void caseSensitive_enabled_ignoresDifferentCase() {
        List<TermEntry> terms = List.of(new TermEntry("FEA", "有限要素法"));
        List<ScanResult> results = scanner.scan("FEA and fea", terms, true, Collections.emptyList(), true);

        assertEquals(1, results.size());
        assertEquals(0, results.get(0).startOffset);
        assertEquals(3, results.get(0).endOffset);
    }

    @Test
    void caseSensitive_oldSignatureStaysCaseInsensitive() {
        List<TermEntry> terms = List.of(new TermEntry("FEA", "有限要素法"));
        List<ScanResult> results = scanner.scan("FEA and fea", terms, true, Collections.emptyList());

        assertEquals(2, results.size());
    }

    @Test
    void textMode_markupTagsAndAttributesAreNotScanned() {
        // "table" appears as the element name and as text; only the text occurrence counts.
        // Before masking, the raw "<table …>" tag was matched too.
        List<TermEntry> terms = List.of(new TermEntry("table", "表格"));
        String text = "<table frame=\"all\"><title>Table of mesh</title></table>";
        List<ScanResult> results = scanner.scan(text, terms, true, Collections.emptyList());

        assertEquals(1, results.size());
        assertEquals(text.indexOf("Table"), results.get(0).startOffset);
    }

    @Test
    void patternReuse_caseSensitiveAndInsensitiveScansDoNotLeakIntoEachOther() {
        // Both settings compile the same source term; a shared pattern cache must keep
        // them strictly apart, in either call order and across scanner instances.
        List<TermEntry> terms = List.of(new TermEntry("FEA", "\u6709\u9650\u8981\u7d20\u6cd5"));
        String text = "FEA and fea";

        assertEquals(1, scanner.scan(text, terms, true, Collections.emptyList(), true).size());
        assertEquals(2, scanner.scan(text, terms, true, Collections.emptyList(), false).size());
        assertEquals(1, new DocumentScanner().scan(text, terms, true, Collections.emptyList(), true).size());
        assertEquals(2, new DocumentScanner().scan(text, terms, true, Collections.emptyList(), false).size());
        // Author mode compiles the unescaped term and must not reuse the text-mode pattern.
        List<int[]> fullCover = List.of(new int[]{0, 0, text.length()});
        assertEquals(2, scanner.scan(text, terms, false, fullCover, false).size());
    }

    // ---- Step 8 patch-plan regression tests ----

    /**
     * 8.1: Terms that are entity names (amp, lt, gt, quot, apos) must NOT match inside
     * entity references in text mode.
     */
    @Test
    void textMode_entityNameTerms_doNotMatchInsideEntityReferences() {
        String text = "<p>A &amp; B, x &lt; y, 5 &gt; 2, say &quot;hi&quot;, it&apos;s</p>";
        // Masking removes the tags; entities remain.
        List<TermEntry> terms = List.of(
            new TermEntry("amp", "A"),
            new TermEntry("lt", "L"),
            new TermEntry("gt", "G"),
            new TermEntry("quot", "Q"),
            new TermEntry("apos", "P"),
            new TermEntry("x41", "H")
        );
        List<ScanResult> results = scanner.scan(text, terms, true, Collections.emptyList());
        assertEquals(0, results.size(),
            "entity-name terms must not match inside &...; references");
    }

    /**
     * 8.1: Terms containing special characters that span across an entity reference
     * (e.g. R&D matches R&amp;D) must still match.
     */
    @Test
    void textMode_termCoveringEntity_fullyMatches() {
        String text = "company R&amp;D dept";
        List<TermEntry> terms = List.of(new TermEntry("R&D", "\u7814\u53d1"));
        List<ScanResult> results = scanner.scan(text, terms, true, Collections.emptyList());
        assertEquals(1, results.size());
        assertEquals("R&D", results.get(0).sourceTerm);
    }

    /**
     * 8.2: A deprecated term (load) contained within a longer match (load case) is
     * still reported (warning is not swallowed).
     */
    @Test
    void longestMatch_deprecatedTerm_isAlwaysRetained() {
        TermEntry loadTerm = new TermEntry("load", "\u52a0\u8f7d");
        loadTerm.setStatus(TermStatus.DEPRECATED);
        TermEntry loadCase = new TermEntry("load case", "\u5de5\u51b5");
        List<TermEntry> terms = List.of(loadTerm, loadCase);
        String text = "the load case is fixed";

        List<ScanResult> results = scanner.scan(text, terms, true, Collections.emptyList());
        // Both "load" (deprecated) and "load case" should appear
        boolean hasDeprecated = results.stream().anyMatch(
            r -> "load".equals(r.sourceTerm) && r.status == TermStatus.DEPRECATED);
        boolean hasLonger = results.stream().anyMatch(
            r -> "load case".equals(r.sourceTerm));
        assertTrue(hasDeprecated, "deprecated 'load' must be reported even inside 'load case'");
        assertTrue(hasLonger, "'load case' must also be reported");
    }

    /**
     * 8.2: A non-deprecated term swallowed by a longer one is still dropped (existing behavior).
     */
    @Test
    void longestMatch_nonDeprecated_stillDroppedWhenContained() {
        TermEntry loadTerm = new TermEntry("load", "\u52a0\u8f7d");
        // no status set → not deprecated
        TermEntry loadCase = new TermEntry("load case", "\u5de5\u51b5");
        List<TermEntry> terms = List.of(loadTerm, loadCase);
        String text = "the load case is fixed";

        List<ScanResult> results = scanner.scan(text, terms, true, Collections.emptyList());
        assertEquals(1, results.size(), "non-deprecated 'load' inside 'load case' should be dropped");
        assertEquals("load case", results.get(0).sourceTerm);
    }

    // ---- Patch-plan 5 (step 3.2): scan by target term (QA) ----

    /** T1: a TARGET scan matches the entry's translation, not its source. */
    @Test
    void targetDirection_matchesTheTranslation() {
        List<TermEntry> terms = List.of(new TermEntry("\u5e94\u529b", "stress"));
        List<ScanResult> results = scanner.scan("the stress level", terms, true,
            Collections.emptyList(), false, ScanDirection.TARGET);

        assertEquals(1, results.size());
        assertEquals("stress", results.get(0).matchedText);
        assertEquals("stress", results.get(0).targetTerm);
        assertEquals("\u5e94\u529b", results.get(0).sourceTerm);
    }

    /** T2: the same document under SOURCE is unchanged - the English word is not a source term. */
    @Test
    void sourceDirection_stillMatchesOnlyTheSource_regressionGuard() {
        List<TermEntry> terms = List.of(new TermEntry("\u5e94\u529b", "stress"));
        List<ScanResult> results = scanner.scan("the stress level", terms, true, Collections.emptyList());

        assertTrue(results.isEmpty(), "SOURCE direction must not match the translation");
    }

    /** T6: entries with a blank or null target are skipped in TARGET, never throwing. */
    @Test
    void targetDirection_skipsBlankAndNullTargets() {
        TermEntry nullTarget = new TermEntry("\u7f51\u683c", null);
        TermEntry emptyTarget = new TermEntry("\u8282\u70b9", "");
        List<ScanResult> results = scanner.scan("grid node", List.of(nullTarget, emptyTarget),
            true, Collections.emptyList(), false, ScanDirection.TARGET);

        assertTrue(results.isEmpty());
    }

    /** T7: the word-boundary rule applies to the matched target term too. */
    @Test
    void targetDirection_wordBoundaryExcludesPartial() {
        List<TermEntry> terms = List.of(new TermEntry("\u732b", "cat"));
        List<ScanResult> results = scanner.scan("category cat", terms, true,
            Collections.emptyList(), false, ScanDirection.TARGET);

        assertEquals(1, results.size());
        assertEquals("cat", results.get(0).matchedText);
        assertEquals(9, results.get(0).startOffset);
    }

    /** T8: one target shared by two sources yields both entries at the same position. */
    @Test
    void targetDirection_sameTargetTwoSources_keepsBothAtOnePosition() {
        List<TermEntry> terms = List.of(
            new TermEntry("\u8f7d\u8377", "load"),
            new TermEntry("\u8d1f\u8f7d", "load"));
        List<ScanResult> results = scanner.scan("the load here", terms, true,
            Collections.emptyList(), false, ScanDirection.TARGET);

        assertEquals(2, results.size());
        assertEquals(results.get(0).startOffset, results.get(1).startOffset);
    }

    /** T9: a deprecated target contained in a longer target is still reported (8.2 across directions). */
    @Test
    void targetDirection_deprecatedContainedInLonger_isRetained() {
        TermEntry grid = new TermEntry("\u7f51\u683c", "mesh");
        grid.setStatus(TermStatus.DEPRECATED);
        TermEntry meshSize = new TermEntry("\u7f51\u683c\u5c3a\u5bf8", "mesh size");
        List<ScanResult> results = scanner.scan("pick a mesh size now",
            List.of(grid, meshSize), true, Collections.emptyList(), false, ScanDirection.TARGET);

        boolean hasDeprecatedMesh = results.stream().anyMatch(
            r -> "mesh".equals(r.matchedText) && r.status == TermStatus.DEPRECATED);
        boolean hasLonger = results.stream().anyMatch(r -> "mesh size".equals(r.matchedText));
        assertTrue(hasDeprecatedMesh, "deprecated 'mesh' must survive inside 'mesh size'");
        assertTrue(hasLonger, "'mesh size' must also be reported");
    }

    /** T10: the case option is honoured for the matched target term. */
    @Test
    void targetDirection_caseOptionAppliesToTheTarget() {
        List<TermEntry> terms = List.of(new TermEntry("\u5e94\u529b", "Stress"));
        assertEquals(0, scanner.scan("the stress level", terms, true,
            Collections.emptyList(), true, ScanDirection.TARGET).size(),
            "case-sensitive scan must not match differently-cased text");
        assertEquals(1, scanner.scan("the stress level", terms, true,
            Collections.emptyList(), false, ScanDirection.TARGET).size(),
            "case-insensitive scan matches regardless of case");
    }

    /** T11: text mode escapes entities for the target term too, and markup attributes never match. */
    @Test
    void targetDirection_textMode_escapesEntitiesAndIgnoresMarkup() {
        List<TermEntry> terms = List.of(
            new TermEntry("company", "AT&T"),
            new TermEntry("x", "stress"));
        String text = "use AT&amp;T in <a stress=\"stress\"/>";
        List<ScanResult> results = scanner.scan(text, terms, true,
            Collections.emptyList(), false, ScanDirection.TARGET);

        assertEquals(1, results.size());
        assertEquals("AT&T", results.get(0).matchedText);
    }

    /** T12: Author-mode offset mapping is identical to SOURCE for the same segments. */
    @Test
    void targetDirection_authorMode_mapsOffsetsLikeSource() {
        List<TermEntry> terms = List.of(new TermEntry("\u4f60\u597d", "hello"));
        List<int[]> segments = new ArrayList<>();
        segments.add(new int[]{100, 0, 20});

        List<ScanResult> target = scanner.scan("say hello world", terms, true,
            Collections.emptyList(), false, ScanDirection.TARGET);
        // Recompute in author mode to exercise the mapping path.
        List<ScanResult> results = scanner.scan("say hello world", terms, false,
            segments, false, ScanDirection.TARGET);

        assertEquals(1, target.size());
        assertEquals(1, results.size());
        assertEquals(104, results.get(0).startOffset);
        assertEquals(109, results.get(0).endOffset);
        assertEquals("hello", results.get(0).matchedText);
    }

    /** T14: the old overloads are byte-for-byte the new ones with SOURCE, including matchedText. */
    @Test
    void oldOverloads_equalSourceDirection_includingMatchedText() {
        List<TermEntry> terms = List.of(
            new TermEntry("mesh", "\u7f51\u683c"),
            new TermEntry("node", "\u8282\u70b9"));
        String text = "the mesh and the node";
        List<int[]> segments = List.of(new int[]{0, 0, text.length()});

        List<ScanResult> viaOld = scanner.scan(text, terms, false, segments);
        List<ScanResult> viaNew = scanner.scan(text, terms, false, segments,
            false, ScanDirection.SOURCE);

        assertEquals(viaOld.size(), viaNew.size());
        for (int i = 0; i < viaOld.size(); i++) {
            ScanResult a = viaOld.get(i);
            ScanResult b = viaNew.get(i);
            assertEquals(a.sourceTerm, b.sourceTerm);
            assertEquals(a.targetTerm, b.targetTerm);
            assertEquals(a.startOffset, b.startOffset);
            assertEquals(a.endOffset, b.endOffset);
            assertEquals(a.status, b.status);
            assertEquals(a.sourceTerm, a.matchedText, "SOURCE results default matchedText to the source");
            assertEquals(a.matchedText, b.matchedText);
        }
    }
}
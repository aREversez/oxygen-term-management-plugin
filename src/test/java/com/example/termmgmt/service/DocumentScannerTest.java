package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
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
}
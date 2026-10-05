package com.example.termmgmt.service;

import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ColumnLayoutTest {

    @Test
    void defaultLayout_extrasStartAtColumnTwo() {
        ColumnLayout l = new ColumnLayout(0, 1, 3);
        assertEquals(5, l.width());
        assertEquals(2, l.extraPosition(0));
        assertEquals(4, l.extraPosition(2));
    }

    @Test
    void movedPair_extrasFillTheRemainingColumnsInOrder() {
        ColumnLayout l = new ColumnLayout(3, 0, 2); // columns: tgt, extra0, extra1, src
        assertEquals(1, l.extraPosition(0));
        assertEquals(2, l.extraPosition(1));
    }

    @Test
    void extrasOf_skipsSourceAndTarget() {
        assertEquals(Arrays.asList("b", "d"), ColumnLayout.extrasOf(Arrays.asList("a", "b", "c", "d"), 2, 0));
    }

    @Test
    void resolve_defaultWithoutSelection() {
        TermbaseConfig c = new TermbaseConfig("x.csv", Format.CSV, true);
        ColumnLayout.Resolution r = ColumnLayout.resolve(Arrays.asList("a", "b", "c"), c);
        assertEquals(0, r.sourceColumn);
        assertEquals(1, r.targetColumn);
        assertFalse(r.fellBack);
        assertEquals(Arrays.asList("a", "b", "c"), r.available);
    }

    @Test
    void resolve_duplicateHeadersTakeTheFirstColumn() {
        TermbaseConfig c = new TermbaseConfig("x.csv", Format.CSV, true);
        c.setSelectedLangs("b", "c");
        List<String> headers = Arrays.asList("a", "b", "B", "c");
        ColumnLayout.Resolution r = ColumnLayout.resolve(headers, c);
        assertEquals(1, r.sourceColumn);
        assertEquals(3, r.targetColumn);
        assertEquals(Arrays.asList("a", "b", "c"), r.available);
    }

    @Test
    void langPairKey_changesWithTheSelection() {
        TermbaseConfig c = new TermbaseConfig("x.csv", Format.CSV, true);
        String none = c.langPairKey();
        c.setSelectedLangs("EN", "de");
        String chosen = c.langPairKey();
        assertNotEquals(none, chosen);
        c.setSelectedLangs("en", "DE");
        assertEquals(chosen, c.langPairKey());
        c.setSelectedLangs(null, null);
        assertEquals(none, c.langPairKey());
    }
}

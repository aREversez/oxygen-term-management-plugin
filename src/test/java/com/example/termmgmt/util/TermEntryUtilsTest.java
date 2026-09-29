package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TermEntryUtilsTest {

    @Test
    void indexOfEntry_skipsEntriesWithNullSourceTerm() {
        List<TermEntry> terms = new ArrayList<>(List.of(
            new TermEntry(null, "orphan translation"),
            new TermEntry("a", "A")));

        assertEquals(1, TermEntryUtils.indexOfEntry(terms, new TermEntry("a", "A")));
    }

    @Test
    void indexOfEntry_matchesEntryWithNullTargetTerm() {
        List<TermEntry> terms = new ArrayList<>(List.of(
            new TermEntry("a", "A"),
            new TermEntry("b", null)));

        assertEquals(1, TermEntryUtils.indexOfEntry(terms, new TermEntry("b", null)));
    }

    @Test
    void indexOfEntry_distinguishesSameSourceWithDifferentTargets() {
        List<TermEntry> terms = new ArrayList<>(List.of(
            new TermEntry("a", "A1"),
            new TermEntry("a", "A2")));

        assertEquals(1, TermEntryUtils.indexOfEntry(terms, new TermEntry("a", "A2")));
    }

    @Test
    void indexOfEntry_returnsMinusOneWhenNotFoundOrTargetIsNull() {
        List<TermEntry> terms = new ArrayList<>(List.of(new TermEntry("a", "A")));

        assertEquals(-1, TermEntryUtils.indexOfEntry(terms, new TermEntry("a", "other")));
        assertEquals(-1, TermEntryUtils.indexOfEntry(terms, null));
        assertEquals(-1, TermEntryUtils.indexOfEntry(Collections.emptyList(), new TermEntry("a", "A")));
    }
}

package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void indexOfSame_prefersTheSameInstanceOverAnEarlierEqualEntry() {
        TermEntry first = new TermEntry("a", "A");
        TermEntry second = new TermEntry("a", "A");
        List<TermEntry> terms = new ArrayList<>(List.of(first, second));

        assertEquals(1, TermEntryUtils.indexOfSame(terms, second));
        assertEquals(0, TermEntryUtils.indexOfSame(terms, first));
    }

    @Test
    void indexOfSame_fallsBackToValueEqualityAndReturnsMinusOneWhenAbsent() {
        List<TermEntry> terms = new ArrayList<>(List.of(new TermEntry("x", "X"), new TermEntry("a", "A")));

        assertEquals(1, TermEntryUtils.indexOfSame(terms, new TermEntry("a", "A")));
        assertEquals(-1, TermEntryUtils.indexOfSame(terms, new TermEntry("a", "other")));
        assertEquals(-1, TermEntryUtils.indexOfSame(terms, null));
    }

    @Test
    void replaceEntry_replacesTheTargetWhereverItIsNow() {
        TermEntry a = new TermEntry("a", "A");
        TermEntry b = new TermEntry("b", "B");
        List<TermEntry> terms = new ArrayList<>(List.of(new TermEntry("new", "N"), a, b)); // a moved since it was shown
        TermEntry edited = new TermEntry("a", "A2");

        assertTrue(TermEntryUtils.replaceEntry(terms, a, edited));

        assertSame(edited, terms.get(1));
        assertSame(b, terms.get(2));
        assertFalse(TermEntryUtils.replaceEntry(terms, a, edited)); // already replaced -> gone
    }

    @Test
    void removeEntries_removesExactlyTheGivenEntriesIncludingDuplicates() {
        TermEntry dup1 = new TermEntry("a", "A");
        TermEntry dup2 = new TermEntry("a", "A");
        TermEntry keep = new TermEntry("k", "K");
        TermEntry added = new TermEntry("late", "L"); // appended by a concurrent add
        List<TermEntry> terms = new ArrayList<>(List.of(dup1, keep, dup2, added));

        int removed = TermEntryUtils.removeEntries(terms, List.of(dup2, dup1));

        assertEquals(2, removed);
        assertEquals(List.of(keep, added), terms);
    }

    // ---- Step 7.4 regression tests ----

    /**
     * 7.4: Identity match → replacement used directly (no merge).
     */
    @Test
    void replaceEntryMerging_identityMatch_replacesDirectly() {
        TermEntry original = new TermEntry("a", "A");
        original.getExtraFields().put("note", "old note");
        List<TermEntry> terms = new ArrayList<>(List.of(original));

        TermEntry edited = original.copy();
        edited.setSourceTerm("b");

        assertTrue(TermEntryUtils.replaceEntryMerging(terms, original, edited));
        assertSame(edited, terms.get(0), "identity match must use the replacement as-is");
    }

    /**
     * 7.4: Value match → merged from fresh disk entry, preserving external extraFields.
     */
    @Test
    void replaceEntryMerging_valueMatch_preservesExternalExtraFields() {
        // "original" is the stale copy the UI held.
        TermEntry original = new TermEntry("a", "A");
        original.getExtraFields().put("note", "stale");
        original.setEntryId("id1");

        // "fresh" is what was reloaded from disk after an external change modified extra fields.
        TermEntry fresh = new TermEntry("a", "A");
        fresh.getExtraFields().put("note", "externally updated");
        fresh.getExtraFields().put("category", "new-field");
        fresh.setEntryId("id1");
        List<TermEntry> terms = new ArrayList<>(List.of(fresh));

        // User only edited source term to "b".
        TermEntry userEdited = original.copy();
        userEdited.setSourceTerm("b");

        assertTrue(TermEntryUtils.replaceEntryMerging(terms, original, userEdited));
        TermEntry result = terms.get(0);
        assertEquals("b", result.getSourceTerm());
        assertEquals("A", result.getTargetTerm());
        assertEquals("externally updated", result.getExtraFields().get("note"),
            "external note must survive the merge");
        assertEquals("new-field", result.getExtraFields().get("category"),
            "new external field must survive");
        assertEquals("id1", result.getEntryId());
    }
}

package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- 13: undo = put back only what was deleted -------------------------------------

    private static List<TermEntry> five() {
        return new ArrayList<>(List.of(
            new TermEntry("a", "A"), new TermEntry("b", "B"), new TermEntry("c", "C"),
            new TermEntry("d", "D"), new TermEntry("e", "E")));
    }

    private static List<String> sources(List<TermEntry> terms) {
        List<String> out = new ArrayList<>();
        for (TermEntry t : terms) out.add(t.getSourceTerm());
        return out;
    }

    @Test
    void removeEntriesRecording_recordsOriginalPositionsLowestFirst_andRemoves() {
        List<TermEntry> terms = five();
        List<TermEntry> pick = List.of(terms.get(3), terms.get(1));      // d, b (picked in any order)

        List<TermEntryUtils.RemovedEntry> removed = TermEntryUtils.removeEntriesRecording(terms, pick);

        assertEquals(List.of("a", "c", "e"), sources(terms));
        assertEquals(2, removed.size());
        assertEquals(1, removed.get(0).getIndex());
        assertEquals("b", removed.get(0).getEntry().getSourceTerm());
        assertEquals(3, removed.get(1).getIndex());
        assertEquals("d", removed.get(1).getEntry().getSourceTerm());
    }

    @Test
    void removeEntriesRecording_keepsExtraFieldsAndStatus_inAPrivateCopy() {
        List<TermEntry> terms = five();
        TermEntry target = terms.get(2);
        target.getExtraFields().put("note", "keep me");
        target.setStatus(com.example.termmgmt.model.TermStatus.DEPRECATED);

        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(target));

        TermEntry kept = removed.get(0).getEntry();
        assertNotSame(target, kept);
        assertEquals("keep me", kept.getExtraFields().get("note"));
        assertEquals(com.example.termmgmt.model.TermStatus.DEPRECATED, kept.getStatus());
    }

    @Test
    void removeEntriesRecording_twoIdenticalEntries_removesBothSlots() {
        List<TermEntry> terms = new ArrayList<>(List.of(
            new TermEntry("x", "X"), new TermEntry("x", "X"), new TermEntry("y", "Y")));
        List<TermEntry> pick = List.of(terms.get(0), terms.get(1));

        List<TermEntryUtils.RemovedEntry> removed = TermEntryUtils.removeEntriesRecording(terms, pick);

        assertEquals(List.of("y"), sources(terms));
        assertEquals(2, removed.size());
        assertEquals(0, removed.get(0).getIndex());
        assertEquals(1, removed.get(1).getIndex());
    }

    @Test
    void reinsertRemoved_putsEntriesBackAtTheirOldPositions() {
        List<TermEntry> terms = five();
        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(1), terms.get(3)));

        int restored = TermEntryUtils.reinsertRemoved(terms, removed);

        assertEquals(2, restored);
        assertEquals(List.of("a", "b", "c", "d", "e"), sources(terms));
    }

    @Test
    void reinsertRemoved_keepsEntriesAddedAndEditedAfterTheDelete() {
        List<TermEntry> terms = five();
        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(1)));   // delete b
        terms.add(new TermEntry("new1", "N1"));                                    // added after
        terms.add(new TermEntry("new2", "N2"));
        terms.get(0).setTargetTerm("A-edited");                                    // edited after

        TermEntryUtils.reinsertRemoved(terms, removed);

        assertEquals(List.of("a", "b", "c", "d", "e", "new1", "new2"), sources(terms));
        assertEquals("A-edited", terms.get(0).getTargetTerm(), "later edit survives the undo");
    }

    @Test
    void reinsertRemoved_listShorterThanBefore_appendsAtTheEnd() {
        List<TermEntry> terms = five();
        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(4)));   // delete e (index 4)
        terms.remove(0);                                                           // list now a shorter [b, c, d]
        terms.remove(0);

        TermEntryUtils.reinsertRemoved(terms, removed);

        assertEquals(List.of("c", "d", "e"), sources(terms));
    }

    @Test
    void reinsertRemoved_skipsAnEntryTheUserAlreadyReAdded() {
        List<TermEntry> terms = five();
        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(1)));
        terms.add(new TermEntry("b", "B"));                                        // typed in again

        int restored = TermEntryUtils.reinsertRemoved(terms, removed);

        assertEquals(0, restored);
        assertEquals(List.of("a", "c", "d", "e", "b"), sources(terms));
    }

    /**
     * 13 (建议3): a skipped (already-re-added) entry must not push the following restoration one
     * slot to the right. Deleting b@1 and c@2, re-adding b by hand, then undoing should land c
     * right after a (its original neighbour), not after d.
     */
    @Test
    void reinsertRemoved_shiftsLaterRestorationsLeftForEverySkip() {
        List<TermEntry> terms = five();
        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(1), terms.get(2))); // b@1, c@2
        assertEquals(List.of("a", "d", "e"), sources(terms));
        terms.add(new TermEntry("b", "B"));                                                    // re-added by hand -> [a,d,e,b]

        int restored = TermEntryUtils.reinsertRemoved(terms, removed);

        assertEquals(1, restored, "only c is put back; b is already present");
        assertEquals(List.of("a", "c", "d", "e", "b"), sources(terms),
            "c keeps its place next to a instead of drifting right past the skipped b");
    }

    @Test
    void reinsertRemoved_restoredEntryCarriesNoClaimOnAFileNode() {
        List<TermEntry> terms = five();
        TermEntry victim = terms.get(1);
        victim.setEntryId("tid7");
        victim.setEntryOrdinal(1);
        victim.setPersistedFingerprint("b\u0000B");
        List<TermEntryUtils.RemovedEntry> removed =
            TermEntryUtils.removeEntriesRecording(terms, List.of(victim));

        TermEntryUtils.reinsertRemoved(terms, removed);

        TermEntry back = terms.get(1);
        assertNull(back.getEntryId());
        assertEquals(-1, back.getEntryOrdinal());
        assertNull(back.getPersistedFingerprint());
        assertEquals("tid7", removed.get(0).getEntry().getEntryId(), "the record itself is not modified");
    }
}

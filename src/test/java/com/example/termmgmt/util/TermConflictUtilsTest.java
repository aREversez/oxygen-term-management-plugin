package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.util.TermConflictUtils.Conflict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermConflictUtilsTest {

    /** The nested loops findConflicts() replaces, kept verbatim as the reference behavior. */
    private static List<Object[]> reference(List<TermEntry> newTerms, List<TermEntry> existingTerms) {
        List<Object[]> out = new ArrayList<>();
        for (TermEntry newTerm : newTerms) {
            if (newTerm.getSourceTerm() == null || newTerm.getSourceTerm().isEmpty()) continue;
            String newSource = newTerm.getSourceTerm().trim();
            for (TermEntry existingTerm : existingTerms) {
                if (existingTerm.getSourceTerm() == null) continue;
                if (newSource.equals(existingTerm.getSourceTerm().trim())) {
                    out.add(new Object[]{newTerm, existingTerm,
                        Objects.equals(newTerm.getTargetTerm(), existingTerm.getTargetTerm())});
                }
            }
        }
        return out;
    }

    private static void assertSameAsReference(List<TermEntry> newTerms, List<TermEntry> existing) {
        List<Object[]> expected = reference(newTerms, existing);
        List<Conflict> actual = TermConflictUtils.findConflicts(newTerms, existing);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i)[0], actual.get(i).newTerm, "new term #" + i);
            assertSame(expected.get(i)[1], actual.get(i).existingTerm, "existing term #" + i);
            assertEquals(expected.get(i)[2], actual.get(i).isIdenticalTarget(), "identical #" + i);
        }
    }

    @Test
    void findConflicts_reportsDuplicateAndConflictingTargets() {
        TermEntry same = new TermEntry("mesh", "网格");
        TermEntry different = new TermEntry("node", "节点");
        List<Conflict> conflicts = TermConflictUtils.findConflicts(
            List.of(new TermEntry("mesh", "网格"), new TermEntry("node", "结点"), new TermEntry("free", "x")),
            List.of(same, different));

        assertEquals(2, conflicts.size());
        assertTrue(conflicts.get(0).isIdenticalTarget());
        assertSame(same, conflicts.get(0).existingTerm);
        assertFalse(conflicts.get(1).isIdenticalTarget());
        assertSame(different, conflicts.get(1).existingTerm);
    }

    @Test
    void findConflicts_edgeCasesMatchTheOriginalLoops() {
        List<TermEntry> existing = List.of(
            new TermEntry(" mesh ", "A"), new TermEntry(null, "B"), new TermEntry("mesh", null),
            new TermEntry("   ", "C"), new TermEntry("", "D"), new TermEntry("mesh", "A"));
        List<TermEntry> added = List.of(
            new TermEntry("mesh", "A"), new TermEntry(null, "X"), new TermEntry("", "Y"),
            new TermEntry("  ", "Z"), new TermEntry("mesh ", null), new TermEntry("Mesh", "A"));

        assertSameAsReference(added, existing);
    }

    @Test
    void findConflicts_matchesTheOriginalLoopsOnRandomData() {
        String[] sources = {"a", "b", " a", "a ", "B", "c", "", " ", null, "网格", "网格 "};
        String[] targets = {"1", "2", null, "3"};
        Random random = new Random(42);
        for (int round = 0; round < 200; round++) {
            List<TermEntry> existing = new ArrayList<>();
            List<TermEntry> added = new ArrayList<>();
            for (int i = random.nextInt(25); i > 0; i--) {
                existing.add(new TermEntry(sources[random.nextInt(sources.length)], targets[random.nextInt(targets.length)]));
            }
            for (int i = random.nextInt(25); i > 0; i--) {
                added.add(new TermEntry(sources[random.nextInt(sources.length)], targets[random.nextInt(targets.length)]));
            }
            assertSameAsReference(added, existing);
        }
    }

    @Test
    void findConflicts_scalesLinearly() {
        List<TermEntry> existing = new ArrayList<>();
        List<TermEntry> added = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            existing.add(new TermEntry("old" + i, "t"));
            added.add(new TermEntry("new" + i, "t"));
        }
        long start = System.nanoTime();
        assertEquals(0, TermConflictUtils.findConflicts(added, existing).size());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 5_000, "200k x 200k took " + millis + " ms; the nested loops would need ~4*10^10 comparisons");
    }
}

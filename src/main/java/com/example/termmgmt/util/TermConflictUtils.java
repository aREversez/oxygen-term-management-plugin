package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Finds terms of a new termbase whose source term already exists in another termbase. */
public final class TermConflictUtils {

    private TermConflictUtils() {
    }

    /** A new term and an existing term with the same (trimmed) source term. */
    public static final class Conflict {
        public final TermEntry newTerm;
        public final TermEntry existingTerm;

        Conflict(TermEntry newTerm, TermEntry existingTerm) {
            this.newTerm = newTerm;
            this.existingTerm = existingTerm;
        }

        /** True if both terms also have the same target, i.e. the new term is a plain duplicate. */
        public boolean isIdenticalTarget() {
            return Objects.equals(newTerm.getTargetTerm(), existingTerm.getTargetTerm());
        }
    }

    /**
     * Compare {@code newTerms} with {@code existingTerms} in time linear in their sizes.
     *
     * The result is ordered by new term, then by position in {@code existingTerms}. New
     * terms with a null or empty source are ignored, as are existing terms with a null source.
     */
    public static List<Conflict> findConflicts(List<TermEntry> newTerms, List<TermEntry> existingTerms) {
        Map<String, List<TermEntry>> existingBySource = new HashMap<>();
        for (TermEntry existing : existingTerms) {
            if (existing.getSourceTerm() == null) {
                continue;
            }
            existingBySource.computeIfAbsent(existing.getSourceTerm().trim(), k -> new ArrayList<>()).add(existing);
        }

        List<Conflict> conflicts = new ArrayList<>();
        for (TermEntry newTerm : newTerms) {
            if (newTerm.getSourceTerm() == null || newTerm.getSourceTerm().isEmpty()) {
                continue;
            }
            List<TermEntry> sameSource = existingBySource.get(newTerm.getSourceTerm().trim());
            if (sameSource == null) {
                continue;
            }
            for (TermEntry existing : sameSource) {
                conflicts.add(new Conflict(newTerm, existing));
            }
        }
        return conflicts;
    }
}

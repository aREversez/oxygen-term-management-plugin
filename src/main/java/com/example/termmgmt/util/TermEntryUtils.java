package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class TermEntryUtils {

    private TermEntryUtils() {
    }

    /**
     * Find the position of the entry whose source and target terms equal those
     * of {@code target}.
     *
     * Termbase files can yield entries with a null source or target term (an
     * empty XLSX cell, a TBX entry with only one language), so both fields are
     * compared null-safely.
     *
     * @return the index of the first match, or -1 if there is none
     */
    public static int indexOfEntry(List<TermEntry> terms, TermEntry target) {
        if (target == null) {
            return -1;
        }
        for (int i = 0; i < terms.size(); i++) {
            TermEntry e = terms.get(i);
            if (e != null
                    && Objects.equals(e.getSourceTerm(), target.getSourceTerm())
                    && Objects.equals(e.getTargetTerm(), target.getTargetTerm())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find {@code target} in {@code terms}: the same instance first, then the first entry
     * with equal source and target terms.
     *
     * Edits and deletions must be applied to the list as it is when the write happens, which
     * may differ from the list the user was looking at. Positions are therefore not stable;
     * the entry itself is. Preferring the instance keeps duplicates (same terms twice)
     * distinct, and the value match covers a cache that was reloaded in between.
     *
     * @return the index, or -1 if the entry is no longer present
     */
    public static int indexOfSame(List<TermEntry> terms, TermEntry target) {
        if (target == null) {
            return -1;
        }
        for (int i = 0; i < terms.size(); i++) {
            if (terms.get(i) == target) {
                return i;
            }
        }
        return indexOfEntry(terms, target);
    }

    /** Replace {@code target} by {@code replacement}; returns false if it is not present. */
    public static boolean replaceEntry(List<TermEntry> terms, TermEntry target, TermEntry replacement) {
        int idx = indexOfSame(terms, target);
        if (idx < 0) {
            return false;
        }
        terms.set(idx, replacement);
        return true;
    }

    /** Remove each of {@code targets} (see {@link #indexOfSame}); returns how many were removed. */
    public static int removeEntries(List<TermEntry> terms, Collection<TermEntry> targets) {
        int removed = 0;
        for (TermEntry target : targets) {
            int idx = indexOfSame(terms, target);
            if (idx >= 0) {
                terms.remove(idx);
                removed++;
            }
        }
        return removed;
    }
}

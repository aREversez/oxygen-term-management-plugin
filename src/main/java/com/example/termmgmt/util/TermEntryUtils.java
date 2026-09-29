package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;

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
}

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

    /**
     * 7.4: Merge-style replace. Finds {@code original} in {@code terms}; if matched by identity,
     * replaces with {@code userEdited} directly. If matched only by value (the list was reloaded
     * due to an external change), starts from the FRESH entry on disk and overlays only the
     * user-edited fields (source, target, status, note).
     *
     * @return true if the entry was found and replaced
     */
    public static boolean replaceEntryMerging(List<TermEntry> terms, TermEntry original,
                                              TermEntry userEdited) {
        if (original == null || userEdited == null) return false;
        int idx = -1;
        boolean byIdentity = false;
        // Try identity first.
        for (int i = 0; i < terms.size(); i++) {
            if (terms.get(i) == original) {
                idx = i;
                byIdentity = true;
                break;
            }
        }
        // Fall back to value match.
        if (idx < 0) {
            idx = indexOfEntry(terms, original);
        }
        if (idx < 0) return false;

        if (byIdentity) {
            terms.set(idx, userEdited);
        } else {
            // Merge: start from the fresh disk entry, overlay only user-changed fields.
            TermEntry fresh = terms.get(idx);
            TermEntry merged = fresh.copy();
            // Source and target are always the user's intent (inline editor and dialog both
            // produce a new value here).
            merged.setSourceTerm(userEdited.getSourceTerm());
            merged.setTargetTerm(userEdited.getTargetTerm());
            // Status: only overlay if user changed it compared to original.
            String userStatus = userEdited.getStoredStatusValue();
            String origStatus = original.getStoredStatusValue();
            if (!Objects.equals(userStatus, origStatus)) {
                if (userStatus == null || userStatus.isEmpty()) {
                    merged.setStatus(null); // cleared
                } else {
                    merged.setStatusRaw(userStatus);
                }
            }
            // Note: only overlay if user actually changed it (avoids stale overwrite).
            String userNote = userEdited.getExtraFields().get("note");
            String origNote = original.getExtraFields().get("note");
            if (!Objects.equals(userNote, origNote)) {
                if (userNote != null) {
                    merged.getExtraFields().put("note", userNote);
                } else {
                    merged.getExtraFields().remove("note");
                }
            }
            terms.set(idx, merged);
        }
        return true;
    }
}

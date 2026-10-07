package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;

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

    /** One entry taken out by a delete, with the position it had in the list just before it. */
    public static final class RemovedEntry {
        private final int index;
        private final TermEntry entry;

        RemovedEntry(int index, TermEntry entry) {
            this.index = index;
            this.entry = entry;
        }

        /** Position in the list as it was before the delete started. */
        public int getIndex() { return index; }

        /** A private copy of the removed entry, extra fields and status included. */
        public TermEntry getEntry() { return entry; }
    }

    /**
     * Like {@link #removeEntries}, but also records what was removed and where it sat, so the
     * delete can be undone by putting just those entries back (see {@link #reinsertRemoved}).
     * Positions refer to the list as it is when this method is called, before anything is
     * removed; the result is ordered by position, lowest first. Entries not found are skipped.
     */
    public static List<RemovedEntry> removeEntriesRecording(List<TermEntry> terms,
                                                            Collection<TermEntry> targets) {
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (TermEntry target : targets) {
            // Same lookup as removeEntries, but never resolve two targets to the same slot.
            int idx = -1;
            for (int i = 0; i < terms.size(); i++) {
                if (terms.get(i) == target && !taken.contains(i)) { idx = i; break; }
            }
            if (idx < 0) {
                for (int i = 0; i < terms.size(); i++) {
                    TermEntry e = terms.get(i);
                    if (e != null && !taken.contains(i)
                            && Objects.equals(e.getSourceTerm(), target.getSourceTerm())
                            && Objects.equals(e.getTargetTerm(), target.getTargetTerm())) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx >= 0) {
                taken.add(idx);
            }
        }
        List<RemovedEntry> recorded = new java.util.ArrayList<>();
        for (int idx : new java.util.TreeSet<>(taken)) {
            recorded.add(new RemovedEntry(idx, terms.get(idx).copy()));
        }
        // Remove from the back so earlier positions stay valid.
        List<Integer> descending = new java.util.ArrayList<>(new java.util.TreeSet<>(taken));
        java.util.Collections.reverse(descending);
        for (int idx : descending) {
            terms.remove(idx);
        }
        return recorded;
    }

    /**
     * Undo of {@link #removeEntriesRecording}: puts the recorded entries back into the list as
     * it is NOW, each at its old position (or at the end if the list has become shorter),
     * leaving every other entry - added, edited or changed on disk since - as it is.
     *
     * An entry whose source and target already occur in the list is not inserted again (the
     * user may have re-added it by hand). The restored copies carry no claim on any file
     * node (no TBX id, ordinal or fingerprint), so a save writes them as new nodes instead of
     * matching them to whatever now occupies their old position. They do carry a restore claim
     * (the old id/fingerprint/ordinal), which lets the TBX writer put back the whole deleted node
     * when it still has it.
     *
     * Positions are best-effort: the list may have gained, lost or reordered entries since the
     * delete, so an old index cannot always be reproduced exactly. Entries are recorded in
     * ascending index order; when one is skipped because it is already present, every later
     * restoration shifts left by the number skipped so far, so the restored entries keep their
     * original relative order instead of drifting right by one per skip.
     *
     * @return how many entries were put back
     */
    public static int reinsertRemoved(List<TermEntry> terms, List<RemovedEntry> removed) {
        int restored = 0;
        int skipped = 0;
        for (RemovedEntry r : removed) {
            if (indexOfEntry(terms, r.getEntry()) >= 0) {
                skipped++;
                continue;
            }
            TermEntry back = r.getEntry().copy();
            // What the deleted TBX node was known by, so the writer can fetch its full XML from
            // the restore stash. Entries that were never on disk have nothing to look up.
            back.setRestoreClaim(r.getEntry().getEntryId(), r.getEntry().getPersistedFingerprint(),
                    r.getEntry().getEntryOrdinal());
            back.setEntryId(null);
            back.setEntryOrdinal(-1);
            back.setPersistedFingerprint(null);
            int target = Math.max(r.getIndex() - skipped, 0);
            terms.add(Math.min(target, terms.size()), back);
            restored++;
        }
        return restored;
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

    /**
     * 6.2: The status the Add/Edit dialog should apply, given the dropdown index and the
     * entry's stored status at the moment the dialog opened. Index 0 is Preferred, 1 Admitted,
     * 2 Deprecated. Returns {@code null} when nothing must be written: choosing Preferred
     * (index 0) on an entry that never had a status must not introduce one, otherwise a plain
     * confirm would add a {@code status} column to a CSV/XLSX or a {@code termNote} to a TBX
     * file that had none. Callers apply the result only when non-null.
     */
    public static TermStatus resolveDialogStatus(int comboIndex, String originalStoredStatus) {
        if (comboIndex == 0 && originalStoredStatus == null) {
            return null;
        }
        switch (comboIndex) {
            case 1:
                return TermStatus.ADMITTED;
            case 2:
                return TermStatus.DEPRECATED;
            default:
                return TermStatus.PREFERRED;
        }
    }
}

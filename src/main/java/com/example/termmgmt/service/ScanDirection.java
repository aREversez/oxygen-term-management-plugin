package com.example.termmgmt.service;

/**
 * Which side of a termbase entry the document is matched against (patch-plan 5, step 3.2).
 *
 * <p>{@link #SOURCE} is the long-standing behaviour: a document is scanned for each entry's
 * source term. {@link #TARGET} matches the entry's target term instead, which lets a QA pass
 * sweep a document for translations that are marked {@code deprecated} and report the preferred
 * equivalents to switch to.
 */
public enum ScanDirection {

    /** Match each entry's source term (the default, unchanged behaviour). */
    SOURCE,

    /** Match each entry's target term; entries with a blank target are skipped. */
    TARGET;

    /** The stored/persisted name of the direction; never null. */
    public String key() {
        return name();
    }

    /**
     * Parses a persisted direction. Anything null, blank or unrecognised falls back to
     * {@link #SOURCE}, so a corrupt or absent stored value restores the default behaviour.
     */
    public static ScanDirection fromKey(String raw) {
        if (raw == null) {
            return SOURCE;
        }
        String v = raw.trim();
        for (ScanDirection d : values()) {
            if (d.name().equalsIgnoreCase(v)) {
                return d;
            }
        }
        return SOURCE;
    }
}

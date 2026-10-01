package com.example.termmgmt.model;

/**
 * Maturity status of a term (step 3.1). Stored in {@link TermEntry}'s extraFields under
 * the "status" key - CSV/XLSX column "status", TBX termNote type="administrativeStatus" -
 * never in a separate field, so saves that do not know about statuses still carry the value.
 *
 * <p>CSV/XLSX values are the plain lower-case names; empty means preferred. TBX uses the
 * TBX-Basic administrativeStatus domain ("preferredTerm-admn-sts", "admittedTerm-admn-sts",
 * "deprecatedTerm-admn-sts"); both spellings parse case-insensitively. Unknown values yield
 * null from {@link #parse} and stay verbatim in the file.
 */
public enum TermStatus {

    PREFERRED,
    ADMITTED,
    DEPRECATED;

    /** Value written to CSV/XLSX. */
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Value written to a TBX termNote type="administrativeStatus". */
    public String tbxValue() {
        return value() + "Term-admn-sts";
    }

    /**
     * The status a raw stored value denotes, or null when it is empty or not one of the
     * three known statuses (an unknown value is kept as-is by the handlers, never rewritten).
     */
    public static TermStatus parse(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        for (TermStatus s : values()) {
            if (s.value().equalsIgnoreCase(v) || s.tbxValue().equalsIgnoreCase(v)) {
                return s;
            }
        }
        return null;
    }
}

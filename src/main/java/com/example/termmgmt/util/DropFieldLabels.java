package com.example.termmgmt.util;

import java.util.Map;

/**
 * Maps the field names an export reports as dropped (plain English in the service layer, which
 * stays free of the resource bundles) to the i18n keys the UI renders them with. Unknown names
 * map to {@code null} so the UI shows them as they are rather than losing them.
 */
public final class DropFieldLabels {

    private static final String TERM_NOTES = "term notes";

    private static final Map<String, String> KEYS = Map.of(
        "definition", "export.field.definition",
        "note", "export.field.note",
        "status", "export.field.status",
        "extra language terms", "export.field.extra.lang",
        "target language terms", "export.field.target.lang",
        "extra columns", "export.field.extra.columns",
        "additional notes", "export.field.additional.notes",
        "descriptions other than definition", "export.field.other.descrips",
        "variant term forms", "export.field.variants");

    /** Key of the label for the unmodelled-termNote entry, whose type list follows as detail. */
    public static final String KEY_TERM_NOTES = "export.field.termnotes";

    /** Every key this class can return, for the bundle-completeness test. */
    public static final java.util.List<String> ALL_KEYS;

    static {
        java.util.List<String> all = new java.util.ArrayList<>(KEYS.values());
        all.add(KEY_TERM_NOTES);
        ALL_KEYS = java.util.Collections.unmodifiableList(all);
    }

    private DropFieldLabels() {}

    /** The bundle key for a reported field name, or {@code null} when it is not a known one. */
    public static String keyOf(String fieldName) {
        if (fieldName == null) return null;
        if (fieldName.equals(TERM_NOTES) || fieldName.startsWith(TERM_NOTES + " (")) {
            return KEY_TERM_NOTES;
        }
        return KEYS.get(fieldName);
    }

    /**
     * The technical detail that rides with a name and is shown verbatim after the label: the
     * termNote types of {@code "term notes (a, b)"}. {@code null} when there is none.
     */
    public static String detailOf(String fieldName) {
        if (fieldName == null || !fieldName.startsWith(TERM_NOTES + " (") || !fieldName.endsWith(")")) {
            return null;
        }
        return fieldName.substring(TERM_NOTES.length() + 2, fieldName.length() - 1);
    }

    /**
     * A dropped-field name rendered in the user's language: the bundle label resolved through
     * {@code messageResolver}, with a dynamic {@code "term notes (types)"} detail preserved in
     * parentheses after it. Unknown names are returned verbatim so they reach the dialog as they
     * are rather than being lost. The resolver turns a key into text, which keeps this class free
     * of the resource bundles and its combining logic testable without the UI.
     */
    public static String localizedLabel(String fieldName,
                                        java.util.function.Function<String, String> messageResolver) {
        String key = keyOf(fieldName);
        if (key == null) {
            return fieldName;
        }
        String detail = detailOf(fieldName);
        String label = messageResolver.apply(key);
        return detail == null || detail.isEmpty() ? label : label + " (" + detail + ")";
    }
}

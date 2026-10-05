package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the source term, the target term and the remaining ("extra") columns sit in a CSV or XLSX
 * file. The default is the one every termbase had before the language pair became selectable:
 * source in column 0, target in column 1, extras from column 2 on. A chosen pair moves the
 * source/target columns; the extras keep their file order around them, so a save writes every
 * other column back where it was.
 *
 * <p>Pure logic, shared by both spreadsheet-like handlers.
 */
final class ColumnLayout {

    private final int sourceIndex;
    private final int targetIndex;
    private final int extraCount;

    ColumnLayout(int sourceIndex, int targetIndex, int extraCount) {
        this.sourceIndex = sourceIndex;
        this.targetIndex = targetIndex;
        this.extraCount = extraCount;
    }

    /** The layout a loaded or about-to-be-saved config describes. */
    static ColumnLayout of(TermbaseConfig config) {
        return new ColumnLayout(config.getSourceColumn(), config.getTargetColumn(),
                config.getExtraColumns().size());
    }

    int sourceIndex() {
        return sourceIndex;
    }

    int targetIndex() {
        return targetIndex;
    }

    /** Number of columns the layout covers (source + target + extras). */
    int width() {
        return extraCount + 2;
    }

    /** File column of the i-th extra column. */
    int extraPosition(int i) {
        int pos = 0;
        int seen = 0;
        while (true) {
            if (pos != sourceIndex && pos != targetIndex) {
                if (seen == i) {
                    return pos;
                }
                seen++;
            }
            pos++;
        }
    }

    /** Result of matching a header row against the chosen pair. */
    static final class Resolution {
        final int sourceColumn;
        final int targetColumn;
        final List<String> available;
        /** A pair was chosen but the headers do not offer it, so the default columns were used. */
        final boolean fellBack;

        Resolution(int sourceColumn, int targetColumn, List<String> available, boolean fellBack) {
            this.sourceColumn = sourceColumn;
            this.targetColumn = targetColumn;
            this.available = available;
            this.fellBack = fellBack;
        }
    }

    /**
     * Picks the source and target columns from the (trimmed) header names. A selection is honoured
     * only when both names are present, are different columns, and neither is the status column;
     * otherwise the default columns 0 and 1 are used, and {@code fellBack} says so when a
     * selection existed. Names compare case-insensitively; the first column wins on duplicates.
     */
    static Resolution resolve(List<String> headers, TermbaseConfig config) {
        List<String> all = new ArrayList<>();
        for (String h : headers) {
            if (h != null && !h.isEmpty() && !TermEntry.STATUS_FIELD.equalsIgnoreCase(h)
                    && !containsIgnoreCase(all, h)) {
                all.add(h);
            }
        }
        // Only real BCP-47 tags are selectable languages, so metadata columns like "note" or
        // "domain" stay in the file (as extras written back on save) but never appear in the
        // picker. A legacy termbase whose headers are plain words ("English", "\u4e2d\u6587") would
        // otherwise offer nothing: when filtering leaves fewer than two choices the full, deduped
        // column list is offered exactly as before.
        List<String> langs = new ArrayList<>();
        for (String h : all) {
            if (isLanguageTag(h)) {
                langs.add(h);
            }
        }
        List<String> available = langs.size() >= 2 ? langs : all;
        if (!config.hasSelectedLangs()) {
            return new Resolution(0, 1, available, false);
        }
        int src = indexOf(headers, config.getSelectedSourceLang());
        int tgt = indexOf(headers, config.getSelectedTargetLang());
        if (src >= 0 && tgt >= 0 && src != tgt
                && !TermEntry.STATUS_FIELD.equalsIgnoreCase(headers.get(src))
                && !TermEntry.STATUS_FIELD.equalsIgnoreCase(headers.get(tgt))) {
            return new Resolution(src, tgt, available, false);
        }
        return new Resolution(0, 1, available, true);
    }

    /** Loose BCP-47 shape test: a 2-3 letter primary subtag plus optional alphanumeric suffixes. */
    private static final java.util.regex.Pattern LANG_TAG =
            java.util.regex.Pattern.compile("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*");

    private static boolean isLanguageTag(String header) {
        return LANG_TAG.matcher(header).matches();
    }

    private static int indexOf(List<String> headers, String name) {
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h != null && h.equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) {
            if (x.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    /** The extras (everything but the source and target columns), in file order. */
    static List<String> extrasOf(List<String> headers, int sourceColumn, int targetColumn) {
        List<String> extras = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            if (i != sourceColumn && i != targetColumn) {
                extras.add(headers.get(i));
            }
        }
        return extras;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "ColumnLayout{src=%d, tgt=%d, extras=%d}",
                sourceIndex, targetIndex, extraCount);
    }
}

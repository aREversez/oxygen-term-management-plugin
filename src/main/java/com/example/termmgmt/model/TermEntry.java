package com.example.termmgmt.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class TermEntry {

    private String sourceTerm;
    private String targetTerm;
    private String sourceFilePath; // The termbase file this entry belongs to
    /**
     * Columns beyond source/target (CSV/XLSX) and TBX fields the plugin does not model
     * (descrip, note, termNote, extra languages...). Keyed by column header or field name,
     * in file order; never null. Entries must survive a save with these intact.
     */
    private Map<String, String> extraFields = new LinkedHashMap<>();
    /** The TBX termEntry id this entry was loaded from; null for entries not on disk yet. */
    private String entryId;
    /**
     * The 0-based document-order index of the termEntry node this entry was loaded from.
     * Used as a fallback claim key when entryId is null; -1 means not set (new entry).
     */
    private int entryOrdinal = -1;
    /**
     * The raw status value as read from the file (column text or TBX administrativeStatus
     * termNote). The handlers use it to tell "cleared since load" from "never had one",
     * and to write an unknown TBX value back verbatim on a no-op save.
     */
    private String loadedStatusRaw;
    /**
     * The status chosen through {@link #setStatus}, kept beside extraFields rather than
     * inside them: the storage mapping (column / termNote, casing, TBX domain values)
     * belongs to the handlers, and null means "not edited since load".
     */
    private String statusValue;

    public TermEntry() {}

    public TermEntry(String sourceTerm, String targetTerm) {
        this.sourceTerm = sourceTerm;
        this.targetTerm = targetTerm;
    }

    public TermEntry(String sourceTerm, String targetTerm, String sourceFilePath) {
        this.sourceTerm = sourceTerm;
        this.targetTerm = targetTerm;
        this.sourceFilePath = sourceFilePath;
    }

    public String getSourceTerm() { return sourceTerm; }
    public void setSourceTerm(String sourceTerm) { this.sourceTerm = sourceTerm; }
    public String getTargetTerm() { return targetTerm; }
    public void setTargetTerm(String targetTerm) { this.targetTerm = targetTerm; }
    public String getSourceFilePath() { return sourceFilePath; }
    public void setSourceFilePath(String sourceFilePath) { this.sourceFilePath = sourceFilePath; }
    public Map<String, String> getExtraFields() { return extraFields; }
    public void setExtraFields(Map<String, String> extraFields) {
        this.extraFields = extraFields != null ? extraFields : new LinkedHashMap<>();
    }
    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public int getEntryOrdinal() { return entryOrdinal; }
    public void setEntryOrdinal(int entryOrdinal) { this.entryOrdinal = entryOrdinal; }
    public String getLoadedStatusRaw() { return loadedStatusRaw; }
    public void setLoadedStatusRaw(String loadedStatusRaw) { this.loadedStatusRaw = loadedStatusRaw; }

    /** The status key the CSV/XLSX handlers look for among the column headers. */
    public static final String STATUS_FIELD = "status";

    /**
     * The maturity status parsed from the stored value, or null when unset, empty or not
     * one of the known values (an unknown value stays in the file untouched). Treat null
     * as preferred; {@link #getRawStatus()} reveals the unknown text for the UI to show.
     */
    public TermStatus getStatus() {
        return TermStatus.parse(getRawStatus());
    }

    /** The effective raw status value: the edited choice if any, else what the file held. */
    public String getRawStatus() {
        return statusValue != null ? statusValue : loadedStatusRaw;
    }

    /**
     * The status value the handlers should write: the edited choice, an unknown loaded
     * value kept as it was, or "" when a loaded status was cleared. Null means the entry
     * never had a status - the handler must then write nothing.
     */
    public String getStoredStatusValue() {
        return statusValue != null ? statusValue : (loadedStatusRaw != null ? loadedStatusRaw : null);
    }

    /**
     * Chooses the status; the canonical lower-case value is stored, null clears it back to
     * "unset" (which handlers write as an empty value for files that already carry one).
     */
    public void setStatus(TermStatus status) {
        this.statusValue = status == null ? null : status.value();
    }

    /** Replaces the effective raw value verbatim (used by handlers when loading files). */
    public void setStatusRaw(String raw) {
        this.loadedStatusRaw = raw;
        this.statusValue = null;
    }

    /**
     * Deep copy: extraFields is duplicated (not shared) and entryId carried over, so an
     * edit-and-replace cycle never drops data the table model does not display.
     */
    public TermEntry copy() {
        TermEntry c = new TermEntry(sourceTerm, targetTerm, sourceFilePath);
        c.extraFields = new LinkedHashMap<>(extraFields);
        c.entryId = entryId;
        c.entryOrdinal = entryOrdinal;
        c.loadedStatusRaw = loadedStatusRaw;
        c.statusValue = statusValue;
        return c;
    }

    @Override
    public String toString() {
        return "TermEntry{source='" + sourceTerm + "', target='" + targetTerm + "'}";
    }
}

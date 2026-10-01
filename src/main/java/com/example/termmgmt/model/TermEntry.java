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

    /**
     * Deep copy: extraFields is duplicated (not shared) and entryId carried over, so an
     * edit-and-replace cycle never drops data the table model does not display.
     */
    public TermEntry copy() {
        TermEntry c = new TermEntry(sourceTerm, targetTerm, sourceFilePath);
        c.extraFields = new LinkedHashMap<>(extraFields);
        c.entryId = entryId;
        return c;
    }

    @Override
    public String toString() {
        return "TermEntry{source='" + sourceTerm + "', target='" + targetTerm + "'}";
    }
}

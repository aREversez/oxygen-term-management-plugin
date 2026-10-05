package com.example.termmgmt.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a termbase configuration: its file path, detected format,
 * source/target language tags, and enabled/disabled status.
 */
public class TermbaseConfig {

    public enum Format {
        CSV, XLSX, TBX
    }

    private String filePath;
    private Format format;
    private boolean enabled;
    private String sourceLang;
    private String targetLang;
    /**
     * Column headers other than the source and target columns, in file order (for the default
     * pair: from the third column on), as read from a CSV/XLSX header row.
     * Runtime state only: re-detected on every load, never persisted.
     */
    private List<String> extraColumns = new ArrayList<>();

    /**
     * The language pair the user picked for this termbase, as it is spelled in the file: a header
     * name for CSV/XLSX, an xml:lang value for TBX. Both null means "the default pair" (the first
     * two columns, or the first two langSets of each entry), which is what every termbase used
     * before the choice existed. Persisted.
     */
    private String selectedSourceLang;
    private String selectedTargetLang;
    /**
     * Every language the file offers, in file order. Runtime state only: re-detected on every load.
     */
    private List<String> availableLangs = new ArrayList<>();
    /**
     * Set by a load when a pair was selected but the file no longer offers it, so the default pair
     * was used instead. Runtime state only.
     */
    private boolean selectionFallback;
    /** File columns the CSV/XLSX source and target terms were read from; runtime state only. */
    private int sourceColumn = 0;
    private int targetColumn = 1;

    public TermbaseConfig(String filePath, Format format, boolean enabled) {
        this.filePath = filePath;
        this.format = format;
        this.enabled = enabled;
        this.sourceLang = null;
        this.targetLang = null;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSourceLang() {
        return sourceLang;
    }

    public void setSourceLang(String sourceLang) {
        this.sourceLang = sourceLang;
    }

    public String getTargetLang() {
        return targetLang;
    }

    public void setTargetLang(String targetLang) {
        this.targetLang = targetLang;
    }

    public String getSelectedSourceLang() {
        return selectedSourceLang;
    }

    public String getSelectedTargetLang() {
        return selectedTargetLang;
    }

    /** Whether a language pair other than the default has been chosen for this termbase. */
    public boolean hasSelectedLangs() {
        return selectedSourceLang != null && selectedTargetLang != null;
    }

    /** Choose the pair to use; passing null for either clears the choice (the default pair). */
    public void setSelectedLangs(String source, String target) {
        if (source == null || target == null || source.trim().isEmpty() || target.trim().isEmpty()) {
            this.selectedSourceLang = null;
            this.selectedTargetLang = null;
        } else {
            this.selectedSourceLang = source.trim();
            this.selectedTargetLang = target.trim();
        }
    }

    /**
     * Identifies the language pair in use ("" for the default). Anything held from before a change
     * of pair, such as undo data, is stale when this differs.
     */
    public String langPairKey() {
        return hasSelectedLangs()
            ? selectedSourceLang.toLowerCase(java.util.Locale.ROOT) + "\u0000"
                + selectedTargetLang.toLowerCase(java.util.Locale.ROOT)
            : "";
    }

    public List<String> getAvailableLangs() {
        return availableLangs;
    }

    public void setAvailableLangs(List<String> availableLangs) {
        this.availableLangs = availableLangs != null ? availableLangs : new ArrayList<>();
    }

    public boolean isSelectionFallback() {
        return selectionFallback;
    }

    public void setSelectionFallback(boolean selectionFallback) {
        this.selectionFallback = selectionFallback;
    }

    public int getSourceColumn() {
        return sourceColumn;
    }

    public int getTargetColumn() {
        return targetColumn;
    }

    public void setLangColumns(int sourceColumn, int targetColumn) {
        this.sourceColumn = sourceColumn;
        this.targetColumn = targetColumn;
    }

    public List<String> getExtraColumns() {
        return extraColumns;
    }

    public void setExtraColumns(List<String> extraColumns) {
        this.extraColumns = extraColumns != null ? extraColumns : new ArrayList<>();
    }

    /**
     * Get the file name (without path).
     *
     * @return the file name
     */
    public String getFileName() {
        File file = new File(filePath);
        return file.getName();
    }

    @Override
    public String toString() {
        return "TermbaseConfig{filePath='" + filePath + "', format=" + format +
               ", sourceLang='" + sourceLang + "', targetLang='" + targetLang +
               "', enabled=" + enabled + "}";
    }
}

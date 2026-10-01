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
     * Column headers from the third column on, as read from a CSV/XLSX header row.
     * Runtime state only: re-detected on every load, never persisted.
     */
    private List<String> extraColumns = new ArrayList<>();

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

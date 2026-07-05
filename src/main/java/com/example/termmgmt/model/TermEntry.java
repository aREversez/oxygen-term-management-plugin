package com.example.termmgmt.model;

public class TermEntry {

    private String sourceTerm;
    private String targetTerm;
    private String sourceFilePath; // The termbase file this entry belongs to

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

    @Override
    public String toString() {
        return "TermEntry{source='" + sourceTerm + "', target='" + targetTerm + "'}";
    }
}

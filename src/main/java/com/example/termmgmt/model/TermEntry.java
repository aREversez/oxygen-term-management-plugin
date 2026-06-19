package com.example.termmgmt.model;

public class TermEntry {

    private String sourceTerm;
    private String targetTerm;

    public TermEntry() {}

    public TermEntry(String sourceTerm, String targetTerm) {
        this.sourceTerm = sourceTerm;
        this.targetTerm = targetTerm;
    }

    public String getSourceTerm() { return sourceTerm; }
    public void setSourceTerm(String sourceTerm) { this.sourceTerm = sourceTerm; }
    public String getTargetTerm() { return targetTerm; }
    public void setTargetTerm(String targetTerm) { this.targetTerm = targetTerm; }

    @Override
    public String toString() {
        return "TermEntry{source='" + sourceTerm + "', target='" + targetTerm + "'}";
    }
}

package com.example.termmgmt.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.termmgmt.model.TermEntry;

import com.example.termmgmt.util.TermMatchUtils;

public class DocumentScanner {

    public static class ScanResult {
        public final String sourceTerm;
        public final String targetTerm;
        public final int startOffset;
        public final int endOffset;

        public ScanResult(String source, String target, int start, int end) {
            this.sourceTerm = source;
            this.targetTerm = target;
            this.startOffset = start;
            this.endOffset = end;
        }
    }

    public List<ScanResult> scan(String documentText, List<TermEntry> terms,
            boolean isTextMode, List<int[]> authorSegments) {
        Set<String> countedPositions = new HashSet<>();
        List<ScanResult> allMatches = new ArrayList<>();

        for (TermEntry term : terms) {
            if (Thread.currentThread().isInterrupted()) break;
            if (documentText.isEmpty()) break;
            String sourceTerm = term.getSourceTerm();
            if (sourceTerm == null || sourceTerm.isEmpty()) continue;

            String matchTerm = isTextMode ? escapeXmlEntities(sourceTerm) : sourceTerm;
            Pattern pattern = TermMatchUtils.buildMatchPattern(matchTerm);
            Matcher matcher = pattern.matcher(documentText);

            while (matcher.find()) {
                if (Thread.currentThread().isInterrupted()) break;
                if (documentText.isEmpty()) break;
                int strStart = matcher.start();
                int strEnd = strStart + matchTerm.length();

                String posKey = sourceTerm + "@" + strStart;
                if (!countedPositions.contains(posKey)) {
                    countedPositions.add(posKey);
                    if (isTextMode) {
                        allMatches.add(new ScanResult(
                            sourceTerm, term.getTargetTerm(), strStart, strEnd));
                    } else {
                        int authStart = -1, authEnd = -1;
                        for (int[] seg : authorSegments) {
                            int segAuth = seg[0];
                            int segStr = seg[1];
                            int segLen = seg[2];
                            if (strStart >= segStr && strStart < segStr + segLen) {
                                authStart = segAuth + (strStart - segStr);
                            }
                            if (strEnd >= segStr && strEnd <= segStr + segLen) {
                                authEnd = segAuth + (strEnd - segStr);
                            }
                        }
                        if (authStart >= 0 && authEnd >= 0) {
                            allMatches.add(new ScanResult(
                                sourceTerm, term.getTargetTerm(), authStart, authEnd));
                        }
                    }
                }
            }
        }
        return allMatches;
    }

    private static String escapeXmlEntities(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
}
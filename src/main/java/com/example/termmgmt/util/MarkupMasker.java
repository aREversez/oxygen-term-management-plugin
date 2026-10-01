package com.example.termmgmt.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Blanks XML markup out of a document string so a text scan only sees character data.
 * Every markup construct is replaced by spaces of exactly the same length, so all
 * offsets in the masked string address the same position as in the original text.
 *
 * Handled: start/end tags (quote-aware, a ">" inside an attribute value does not end
 * the tag), comments, processing instructions, the DOCTYPE declaration including its
 * internal subset, and the {@code <![CDATA[} / {@code ]]>} markers of CDATA sections -
 * CDATA content itself stays visible. An unterminated construct masks to end of input.
 */
public final class MarkupMasker {

    private MarkupMasker() {
    }

    public static String mask(String text) {
        if (text.indexOf('<') < 0) return text; // fast path: nothing to mask
        char[] out = text.toCharArray();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.charAt(i) != '<') {
                i++;
                continue;
            }
            if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                blank(out, i, end);
                i = end;
            } else if (text.startsWith("<![CDATA[", i)) {
                int contentStart = i + 9;
                int close = text.indexOf("]]>", contentStart);
                blank(out, i, contentStart); // opening marker only, content survives
                if (close < 0) {
                    i = n;
                } else {
                    blank(out, close, close + 3);
                    i = close + 3;
                }
            } else if (text.startsWith("<?", i)) {
                int end = text.indexOf("?>", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if (text.startsWith("<!", i)) {
                // Declaration (DOCTYPE and friends): '>' ends it, but not inside quotes
                // or inside the '[' ... ']' internal subset.
                int j = i + 2;
                char quote = 0;
                boolean subset = false;
                while (j < n) {
                    char c = text.charAt(j);
                    if (quote != 0) {
                        if (c == quote) quote = 0;
                    } else if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (!subset && c == '[') {
                        subset = true;
                    } else if (subset && c == ']') {
                        subset = false;
                    } else if (!subset && c == '>') {
                        j++;
                        break;
                    }
                    j++;
                }
                blank(out, i, j);
                i = j;
            } else {
                // Ordinary tag: runs to the first '>' that is not inside a quoted attribute.
                int j = i + 1;
                char quote = 0;
                while (j < n) {
                    char c = text.charAt(j);
                    if (quote != 0) {
                        if (c == quote) quote = 0;
                    } else if (c == '"' || c == '\'') {
                        quote = c;
                    } else if (c == '>') {
                        j++;
                        break;
                    }
                    j++;
                }
                blank(out, i, j);
                i = j;
            }
        }
        return new String(out);
    }

    private static void blank(char[] chars, int from, int to) {
        for (int i = from; i < to; i++) {
            chars[i] = ' ';
        }
    }

    /**
     * 8.1: Find all XML entity reference ranges in the text.
     * Each range is an int[]{start, end} where the entity (including & and ;) spans.
     * Recognized forms: &ampname;, &#123;, &#x1F;
     */
    public static List<int[]> findEntityRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (text.charAt(i) != '&') { i++; continue; }
            int j = i + 1;
            if (j < n && text.charAt(j) == '#') {
                j++;
                if (j < n && (text.charAt(j) == 'x' || text.charAt(j) == 'X')) j++;
                while (j < n && Character.isLetterOrDigit(text.charAt(j))) j++;
            } else {
                while (j < n && Character.isLetterOrDigit(text.charAt(j))) j++;
            }
            if (j < n && text.charAt(j) == ';') {
                ranges.add(new int[]{i, j + 1});
                i = j + 1;
            } else {
                i++;
            }
        }
        return ranges;
    }

    /**
     * 8.1: Whether the span [start, end) falls strictly inside an entity reference
     * (entity starts before match AND entity ends after match). A match that fully
     * covers an entity (e.g., term "R&D" matching "R&amp;D") is NOT rejected.
     */
    public static boolean isInsideEntity(int start, int end, List<int[]> entities) {
        for (int[] ent : entities) {
            if (ent[0] < start && end < ent[1]) return true;
        }
        return false;
    }
}

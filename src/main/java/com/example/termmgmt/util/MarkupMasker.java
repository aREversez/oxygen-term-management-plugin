package com.example.termmgmt.util;

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
}

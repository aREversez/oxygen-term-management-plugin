package com.example.termmgmt.util;

import java.util.regex.Pattern;

public class TermMatchUtils {

    private TermMatchUtils() {
    }

    public static boolean isNonDelimitedScript(String text) {
        return text.codePoints().anyMatch(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
        });
    }

    public static Pattern buildMatchPattern(String term) {
        String escaped = Pattern.quote(term);
        String regex = isNonDelimitedScript(term)
            ? escaped
            : "(?<![\\p{L}])" + escaped + "(?![\\p{L}])";
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}

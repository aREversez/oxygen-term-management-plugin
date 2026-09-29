package com.example.termmgmt.util;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
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

    /**
     * Find the distinct known terms contained in {@code text}, stopping once {@code limit}
     * have been found.
     *
     * The text is split on whitespace. A token in a space-delimited script counts as a term
     * only as a whole. A token in a non-delimited script (Han, kana, Hangul) usually has no
     * whitespace at all, so it is segmented by greedy longest match: at each position the
     * longest substring (up to {@code maxTermLength} code points) that {@code isKnownTerm}
     * accepts is taken, and scanning resumes after it.
     *
     * @return the matched terms, lower-cased with {@link Locale#ROOT}, in order of appearance
     */
    public static Set<String> findKnownTerms(String text, Predicate<String> isKnownTerm,
                                             int maxTermLength, int limit) {
        Set<String> found = new LinkedHashSet<>();
        for (String token : text.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (!isNonDelimitedScript(token)) {
                if (isKnownTerm.test(token)) {
                    found.add(token.toLowerCase(Locale.ROOT));
                }
            } else {
                int[] cps = token.codePoints().toArray();
                int i = 0;
                while (i < cps.length && found.size() < limit) {
                    int matchedEnd = -1;
                    for (int end = Math.min(cps.length, i + maxTermLength); end > i; end--) {
                        if (isKnownTerm.test(new String(cps, i, end - i))) {
                            matchedEnd = end;
                            break;
                        }
                    }
                    if (matchedEnd > 0) {
                        found.add(new String(cps, i, matchedEnd - i).toLowerCase(Locale.ROOT));
                        i = matchedEnd;
                    } else {
                        i++;
                    }
                }
            }
            if (found.size() >= limit) {
                break;
            }
        }
        return found;
    }
}

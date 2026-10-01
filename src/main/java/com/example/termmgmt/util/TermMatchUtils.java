package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;

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
        return buildMatchPattern(term, false);
    }

    public static Pattern buildMatchPattern(String term, boolean caseSensitive) {
        // Plain literal: word-boundary enforcement lives in acceptAtBoundary, not in the
        // regex. A boundary class that excludes CJK scripts cannot be written as a fast
        // bitmap class, and evaluated per position it made full scans ~3.5x slower than
        // before; a literal under CASE_INSENSITIVE keeps the engine's fast literal search.
        return Pattern.compile(Pattern.quote(term),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Whether {@code term} needs the word-boundary check at all. Terms in scripts written
     * without spaces (Han, kana, Hangul) match by position alone, as they always did.
     */
    public static boolean boundaryNeeded(String term) {
        return !isNonDelimitedScript(term);
    }

    /**
     * Accept-or-reject for one literal hit: a non-CJK term must not sit inside another
     * word. Only Han, kana and Hangul may touch it - counting any Unicode letter, as
     * {@code (?<![\p{L}])} did, blocked every Latin term glued to CJK text ("使用FEA。"),
     * exactly the mixed-script pattern CAE documents are made of. Digits stay allowed on
     * either side, as they always were. Checked per code point in Java rather than in the
     * pattern: a regex look-around sees only one UTF-16 code unit, so a surrogate pair
     * that is a letter outside the BMP (math bold "\uD835\uDC00", for instance) slips
     * through a regex boundary but not through this method.
     */
    public static boolean acceptAtBoundary(String text, int start, int end) {
        if (isLetterAdjacent(codePointBefore(text, start))) {
            return false;
        }
        return !isLetterAdjacent(codePointAfter(text, end));
    }

    private static int codePointBefore(String s, int index) {
        // index > 0 whenever there is something to check; 0 is not a letter, so a match
        // at the very start is accepted.
        if (index <= 0 || index > s.length()) {
            return 0;
        }
        char cu = s.charAt(index - 1);
        if (Character.isLowSurrogate(cu) && index >= 2) {
            char lead = s.charAt(index - 2);
            if (Character.isHighSurrogate(lead)) {
                return Character.toCodePoint(lead, cu);
            }
        }
        return cu;
    }

    private static int codePointAfter(String s, int index) {
        if (index < 0 || index >= s.length()) {
            return 0;
        }
        char cu = s.charAt(index);
        if (Character.isHighSurrogate(cu) && index + 1 < s.length()) {
            char trail = s.charAt(index + 1);
            if (Character.isLowSurrogate(trail)) {
                return Character.toCodePoint(cu, trail);
            }
        }
        return cu;
    }

    private static boolean isLetterAdjacent(int cp) {
        if (cp == 0 || !Character.isLetter(cp)) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script != Character.UnicodeScript.HAN
            && script != Character.UnicodeScript.HIRAGANA
            && script != Character.UnicodeScript.KATAKANA
            && script != Character.UnicodeScript.HANGUL;
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

    /**
     * Whether a term's source or target contains the search text, ignoring case.
     *
     * @param lowerSearch the search text, already lower-cased with {@link Locale#ROOT}
     */
    public static boolean matchesSearch(TermEntry term, String lowerSearch) {
        String source = term.getSourceTerm();
        String target = term.getTargetTerm();
        return (source != null && source.toLowerCase(Locale.ROOT).contains(lowerSearch))
            || (target != null && target.toLowerCase(Locale.ROOT).contains(lowerSearch));
    }
}

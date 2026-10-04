package com.example.termmgmt.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Regular English word forms of a term, for the optional "match inflected forms" scan (a QA pass
 * should still find "grids" when the termbase says "grid").
 *
 * <p>Rule based and deliberately small: no stemmer library, no dictionary. Only the <em>last word</em>
 * of a term is varied ({@code mesh size} gives {@code mesh sizes}), and only when that word is plain
 * ASCII letters, at least three long, and starts the term or follows a space or hyphen. A term that
 * ends in a digit, a symbol or a CJK character yields nothing. The rules over-generate on purpose
 * (a doubled and an undoubled form, an {@code -s} and a {@code -ves} form): an unused variant costs
 * nothing, while a missing one is a missed hit. Comparatives ({@code -er}, {@code -est}) and adverbs
 * ({@code -ly}) are not covered.
 *
 * <p>Swing-free and dependency-free, so it is unit-testable under the CI source whitelist.
 */
public final class InflectionVariants {

    private InflectionVariants() {
    }

    /** Irregular singular to plural forms that matter in technical text; key is lower case. */
    private static final Map<String, String[]> IRREGULAR_PLURALS = new HashMap<>();

    static {
        put("matrix", "matrices");
        put("vertex", "vertices");
        put("index", "indices", "indexes");
        put("axis", "axes");
        put("analysis", "analyses");
        put("basis", "bases");
        put("hypothesis", "hypotheses");
        put("child", "children");
        put("datum", "data");
        put("criterion", "criteria");
        put("phenomenon", "phenomena");
        put("appendix", "appendices", "appendixes");
        put("formula", "formulae", "formulas");
        put("man", "men");
        put("woman", "women");
        put("foot", "feet");
        put("tooth", "teeth");
        put("mouse", "mice");
        put("series", "series");
        put("species", "species");
    }

    private static void put(String singular, String... plurals) {
        IRREGULAR_PLURALS.put(singular, plurals);
    }

    /**
     * Extra spellings of {@code term} to look for alongside the term itself: the same term with its
     * last word in plural, past/participle and -ing forms. The term itself is never part of the
     * result, there are no duplicates, and the order is stable.
     */
    public static List<String> variantsOf(String term) {
        List<String> out = new ArrayList<>();
        if (term == null) {
            return out;
        }
        int end = term.length();
        int start = end;
        while (start > 0 && isAsciiLetter(term.charAt(start - 1))) {
            start--;
        }
        if (end - start < 3) {
            return out;
        }
        if (start > 0) {
            char before = term.charAt(start - 1);
            if (before != ' ' && before != '-' && before != '\t' && before != ' ') {
                return out;
            }
        }
        String prefix = term.substring(0, start);
        String word = term.substring(start);

        Set<String> forms = new LinkedHashSet<>();
        if (isAllUpper(word) && !IRREGULAR_PLURALS.containsKey(word.toLowerCase(Locale.ROOT))) {
            // An acronym: CPU -> CPUs, nothing else. (An upper-case irregular word such as
            // MATRIX is not one: it takes its table plural, MATRICES.)
            forms.add(word + "s");
        } else {
            addForms(word, forms);
        }
        forms.remove(word);
        for (String f : forms) {
            out.add(prefix + f);
        }
        return out;
    }

    private static void addForms(String word, Set<String> forms) {
        String lw = word.toLowerCase(Locale.ROOT);
        String[] irregular = IRREGULAR_PLURALS.get(lw);
        if (irregular != null) {
            for (String p : irregular) {
                forms.add(matchCase(word, p));
            }
            return;
        }
        pluralForms(word, lw, forms);
        pastForms(word, lw, forms);
        ingForms(word, lw, forms);
    }

    private static void pluralForms(String word, String lw, Set<String> forms) {
        if (endsWithAny(lw, "s", "x", "z", "ch", "sh")) {
            forms.add(word + "es");
        } else if (endsWithConsonantY(lw)) {
            forms.add(word.substring(0, word.length() - 1) + "ies");
        } else {
            forms.add(word + "s");
            if (lw.endsWith("o") && lw.length() > 3 && !isVowel(lw.charAt(lw.length() - 2))) {
                forms.add(word + "es");
            }
            if (lw.endsWith("fe")) {
                forms.add(word.substring(0, word.length() - 2) + "ves");
            } else if (lw.endsWith("f") && lw.length() > 3) {
                forms.add(word.substring(0, word.length() - 1) + "ves");
            }
        }
    }

    private static void pastForms(String word, String lw, Set<String> forms) {
        if (lw.endsWith("e")) {
            forms.add(word + "d");
        } else if (endsWithConsonantY(lw)) {
            forms.add(word.substring(0, word.length() - 1) + "ied");
        } else {
            forms.add(word + "ed");
            if (endsWithConsonantVowelConsonant(lw)) {
                forms.add(word + word.charAt(word.length() - 1) + "ed");
            }
        }
    }

    private static void ingForms(String word, String lw, Set<String> forms) {
        if (lw.endsWith("ie")) {
            forms.add(word.substring(0, word.length() - 2) + "ying");
        } else if (lw.endsWith("e") && !endsWithAny(lw, "ee", "oe", "ye")) {
            forms.add(word.substring(0, word.length() - 1) + "ing");
        } else {
            forms.add(word + "ing");
            if (endsWithConsonantVowelConsonant(lw)) {
                forms.add(word + word.charAt(word.length() - 1) + "ing");
            }
        }
    }

    /** "grid": consonant, vowel, consonant (not w, x, y) at the end - the final consonant doubles. */
    private static boolean endsWithConsonantVowelConsonant(String lw) {
        int n = lw.length();
        if (n < 3) {
            return false;
        }
        char c3 = lw.charAt(n - 1);
        char c2 = lw.charAt(n - 2);
        char c1 = lw.charAt(n - 3);
        return !isVowel(c1) && isVowel(c2) && !isVowel(c3) && c3 != 'w' && c3 != 'x' && c3 != 'y';
    }

    private static boolean endsWithConsonantY(String lw) {
        int n = lw.length();
        return n >= 2 && lw.charAt(n - 1) == 'y' && !isVowel(lw.charAt(n - 2));
    }

    private static boolean endsWithAny(String s, String... suffixes) {
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isAllUpper(String w) {
        for (int i = 0; i < w.length(); i++) {
            if (!(w.charAt(i) >= 'A' && w.charAt(i) <= 'Z')) {
                return false;
            }
        }
        return true;
    }

    /** Gives {@code replacement} the capitalisation pattern of {@code original} (Matrix, MATRIX, matrix). */
    private static String matchCase(String original, String replacement) {
        if (isAllUpper(original)) {
            return replacement.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }
}

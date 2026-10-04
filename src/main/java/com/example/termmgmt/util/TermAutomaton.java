package com.example.termmgmt.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Aho-Corasick multi-pattern matcher over UTF-16 code units, built for the document scan: one
 * pass over the text reports every occurrence of every pattern, instead of one regex pass per
 * term.
 *
 * <p>Patterns are plain literals. The matcher itself is case-exact; callers that want case-
 * insensitive matching fold both the patterns and the text with {@link #fold(String)} first. The
 * fold keeps every character at its original index, so an occurrence found in the folded text
 * maps straight back to the original text.
 *
 * <p>The trie is stored in flat int arrays (first-child / next-sibling lists), not one map per
 * node, so a termbase of tens of thousands of terms costs a few megabytes. Only the root has a
 * direct 64K table: it is the state the scan sits in for almost all of a CJK document, and a
 * sibling walk over thousands of distinct first characters would dominate the run time.
 *
 * <p>Swing-free and dependency-free, so it is unit-testable under the CI source whitelist.
 */
public final class TermAutomaton {

    /** Receives each occurrence of a pattern: its id and the [start, end) range in the scanned text. */
    @FunctionalInterface
    public interface MatchListener {
        void onMatch(int patternId, int start, int end);
    }

    /** How often (in characters) {@link #scan} looks at the thread's interrupt flag. */
    private static final int INTERRUPT_CHECK_MASK = 4095;

    // ---- trie, indexed by node id; node 0 is the root ----
    private final int[] firstChild;
    private final int[] nextSibling;
    private final char[] nodeChar;
    private final int[] fail;
    /** Pattern that ends exactly at this node, or -1. */
    private final int[] outPattern;
    /** Nearest proper-suffix node that ends a pattern, or 0 when there is none. */
    private final int[] dictLink;
    private final int[] rootNext = new int[Character.MAX_VALUE + 1];
    private final int[] patternLength;

    private TermAutomaton(Builder b) {
        int n = b.nodeCount;
        firstChild = Arrays.copyOf(b.firstChild, n);
        nextSibling = Arrays.copyOf(b.nextSibling, n);
        nodeChar = Arrays.copyOf(b.nodeChar, n);
        outPattern = Arrays.copyOf(b.outPattern, n);
        fail = new int[n];
        dictLink = new int[n];
        patternLength = new int[b.lengths.size()];
        for (int i = 0; i < patternLength.length; i++) {
            patternLength[i] = b.lengths.get(i);
        }
        for (int c = firstChild[0]; c != 0; c = nextSibling[c]) {
            rootNext[nodeChar[c]] = c;
        }
        buildFailureLinks();
    }

    /** Breadth-first: a node's failure target is always shallower, hence already computed. */
    private void buildFailureLinks() {
        int n = firstChild.length;
        int[] queue = new int[n];
        int head = 0;
        int tail = 0;
        for (int c = firstChild[0]; c != 0; c = nextSibling[c]) {
            fail[c] = 0;
            queue[tail++] = c;
        }
        while (head < tail) {
            int u = queue[head++];
            for (int v = firstChild[u]; v != 0; v = nextSibling[v]) {
                char ch = nodeChar[v];
                int f = fail[u];
                int w;
                while (true) {
                    w = f == 0 ? rootNext[ch] : child(f, ch);
                    if (w != 0 || f == 0) {
                        break;
                    }
                    f = fail[f];
                }
                fail[v] = w;
                dictLink[v] = outPattern[w] >= 0 ? w : dictLink[w];
                queue[tail++] = v;
            }
        }
    }

    private int child(int node, char ch) {
        for (int c = firstChild[node]; c != 0; c = nextSibling[c]) {
            if (nodeChar[c] == ch) {
                return c;
            }
        }
        return 0;
    }

    /**
     * Reports every occurrence of every pattern in {@code text}, ordered by end position and, for
     * occurrences ending at the same place, longest pattern first. Overlapping occurrences are all
     * reported; callers that want regex-style "non-overlapping per pattern" semantics filter them.
     * Returns early, with the occurrences found so far already delivered, when the calling thread is
     * interrupted.
     */
    public void scan(char[] text, MatchListener listener) {
        int state = 0;
        for (int i = 0; i < text.length; i++) {
            if ((i & INTERRUPT_CHECK_MASK) == 0 && Thread.currentThread().isInterrupted()) {
                return;
            }
            char ch = text[i];
            int next;
            while (true) {
                next = state == 0 ? rootNext[ch] : child(state, ch);
                if (next != 0 || state == 0) {
                    break;
                }
                state = fail[state];
            }
            state = next;
            int node = outPattern[state] >= 0 ? state : dictLink[state];
            while (node != 0) {
                int id = outPattern[node];
                listener.onMatch(id, i + 1 - patternLength[id], i + 1);
                node = dictLink[node];
            }
        }
    }

    /** Number of distinct patterns the automaton was built from. */
    public int patternCount() {
        return patternLength.length;
    }

    /**
     * Case fold that keeps every character at its original UTF-16 index: each code point maps to
     * {@code lower(upper(cp))}, the comparison {@code Pattern.CASE_INSENSITIVE | UNICODE_CASE}
     * uses, and a code point whose fold would change its length is left as it is.
     */
    public static char[] fold(String text) {
        char[] out = text.toCharArray();
        int i = 0;
        while (i < out.length) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);
            int folded = Character.toLowerCase(Character.toUpperCase(cp));
            if (folded != cp && Character.charCount(folded) == len) {
                Character.toChars(folded, out, i);
            }
            i += len;
        }
        return out;
    }

    /** Collects patterns, then freezes them into an automaton. Identical patterns share an id. */
    public static final class Builder {
        private int[] firstChild = new int[64];
        private int[] nextSibling = new int[64];
        private char[] nodeChar = new char[64];
        private int[] outPattern = new int[64];
        private int nodeCount = 1;
        private final List<Integer> lengths = new ArrayList<>();

        public Builder() {
            outPattern[0] = -1;
        }

        /**
         * Adds a non-empty pattern and returns its id; adding the same pattern again returns the
         * id it already has.
         */
        public int add(char[] pattern) {
            if (pattern.length == 0) {
                throw new IllegalArgumentException("empty pattern");
            }
            int node = 0;
            for (char ch : pattern) {
                int c = firstChild[node];
                while (c != 0 && nodeChar[c] != ch) {
                    c = nextSibling[c];
                }
                if (c == 0) {
                    c = newNode(ch);
                    nextSibling[c] = firstChild[node];
                    firstChild[node] = c;
                }
                node = c;
            }
            if (outPattern[node] < 0) {
                outPattern[node] = lengths.size();
                lengths.add(pattern.length);
            }
            return outPattern[node];
        }

        private int newNode(char ch) {
            if (nodeCount == firstChild.length) {
                int cap = nodeCount * 2;
                firstChild = Arrays.copyOf(firstChild, cap);
                nextSibling = Arrays.copyOf(nextSibling, cap);
                nodeChar = Arrays.copyOf(nodeChar, cap);
                outPattern = Arrays.copyOf(outPattern, cap);
            }
            int id = nodeCount++;
            nodeChar[id] = ch;
            outPattern[id] = -1;
            return id;
        }

        public TermAutomaton build() {
            return new TermAutomaton(this);
        }
    }
}

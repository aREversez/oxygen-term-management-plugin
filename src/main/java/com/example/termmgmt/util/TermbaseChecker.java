package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Step 4.1: termbase quality checker. Pure logic, no UI or I/O dependencies, so it is
 * fully unit-testable under the ci profile.
 *
 * <p>Rules checked:
 * <ol>
 *   <li>Source term is empty (null or blank)</li>
 *   <li>Target term is empty (null or blank)</li>
 *   <li>Same source term maps to different target terms within one termbase</li>
 *   <li>Cross-termbase conflicts (same source, different target across termbases);
 *       delegates to {@link TermConflictUtils}</li>
 *   <li>Case-only duplicates: entries whose source differs only in letter case</li>
 *   <li>Consecutive whitespace inside a term (internal double-space, tabs, etc.)</li>
 * </ol>
 *
 * <p>Leading/trailing whitespace is NOT checked because all handlers trim on load.
 */
public final class TermbaseChecker {

    private TermbaseChecker() {}

    /** Severity levels for a finding. */
    public enum Severity {
        WARNING,
        ERROR
    }

    /** A single issue found during the check. */
    public static final class Issue {
        public final Severity severity;
        public final String rule;
        public final String message;
        /** Resource-bundle key of the localized template for the Message column. */
        public final String messageKey;
        /** Values spliced into the localized template; empty when it takes none. */
        public final Object[] messageArgs;
        public final TermEntry entry;
        /** Index of the entry in the list that was checked; -1 for cross-termbase issues. */
        public final int entryIndex;
        /** The termbase file path the entry belongs to; may be null. */
        public final String termbasePath;

        Issue(Severity severity, String rule, String message, TermEntry entry, int entryIndex, String termbasePath) {
            this(severity, rule, message, ruleToKey(rule), NO_ARGS, entry, entryIndex, termbasePath);
        }

        Issue(Severity severity, String rule, String message, String messageKey, Object[] messageArgs,
              TermEntry entry, int entryIndex, String termbasePath) {
            this.severity = severity;
            this.rule = rule;
            this.message = message;
            this.messageKey = messageKey;
            this.messageArgs = messageArgs;
            this.entry = entry;
            this.entryIndex = entryIndex;
            this.termbasePath = termbasePath;
        }

        private static final Object[] NO_ARGS = {};

        /** Convention: rule UPPER_SNAKE maps to bundle key check.msg.lower.snake (underscores become dots). */
        private static String ruleToKey(String rule) {
            return "check.msg." + rule.toLowerCase(Locale.ROOT).replace('_', '.');
        }
    }

    private static final Pattern CONSECUTIVE_WS = Pattern.compile("[\\x20\\t\\x0B\\x0C\\x1F]{2,}");

    /**
     * Run all single-termbase checks on the given term list.
     *
     * @param terms       the entries loaded from one termbase
     * @param termbasePath the file path (for reporting context; may be null)
     * @return findings in entry order; never null
     */
    public static List<Issue> checkSingleTermbase(List<TermEntry> terms, String termbasePath) {
        List<Issue> issues = new ArrayList<>();

        // Index entries by source for duplicate detection (rule 3 and 5)
        Map<String, List<Integer>> byExactSource = new LinkedHashMap<>();
        Map<String, List<Integer>> byLowerSource = new LinkedHashMap<>();

        for (int i = 0; i < terms.size(); i++) {
            TermEntry entry = terms.get(i);
            String src = entry.getSourceTerm();
            String tgt = entry.getTargetTerm();

            // Rule 1: empty source
            if (src == null || src.trim().isEmpty()) {
                issues.add(new Issue(Severity.ERROR, "EMPTY_SOURCE",
                    "Source term is empty", entry, i, termbasePath));
            }

            // Rule 2: empty target
            if (tgt == null || tgt.trim().isEmpty()) {
                issues.add(new Issue(Severity.WARNING, "EMPTY_TARGET",
                    "Target term is empty", entry, i, termbasePath));
            }

            // Rule 6: consecutive whitespace within source or target (only when the field
            // is non-blank; a blank field is already caught by rules 1/2).
            if (src != null && !src.trim().isEmpty() && CONSECUTIVE_WS.matcher(src).find()) {
                issues.add(new Issue(Severity.WARNING, "CONSECUTIVE_WS",
                    "Source term contains consecutive whitespace: \"" + src + "\"",
                    "check.msg.consecutive.ws.source", new Object[] { src }, entry, i, termbasePath));
            }
            if (tgt != null && !tgt.trim().isEmpty() && CONSECUTIVE_WS.matcher(tgt).find()) {
                issues.add(new Issue(Severity.WARNING, "CONSECUTIVE_WS",
                    "Target term contains consecutive whitespace: \"" + tgt + "\"",
                    "check.msg.consecutive.ws.target", new Object[] { tgt }, entry, i, termbasePath));
            }

            // Build indexes for rules 3 and 5
            if (src != null && !src.trim().isEmpty()) {
                String trimmed = src.trim();
                byExactSource.computeIfAbsent(trimmed, k -> new ArrayList<>()).add(i);
                String lower = trimmed.toLowerCase(Locale.ROOT);
                byLowerSource.computeIfAbsent(lower, k -> new ArrayList<>()).add(i);
            }
        }

        // Rule 3: same exact source with different targets
        for (Map.Entry<String, List<Integer>> e : byExactSource.entrySet()) {
            List<Integer> indices = e.getValue();
            if (indices.size() < 2) continue;
            // Collect distinct targets
            Set<String> targets = new HashSet<>();
            for (int idx : indices) {
                String t = terms.get(idx).getTargetTerm();
                if (t != null) targets.add(t.trim());
            }
            if (targets.size() > 1) {
                for (int idx : indices) {
                    issues.add(new Issue(Severity.WARNING, "MULTI_TARGET",
                        "Source \"" + e.getKey() + "\" has multiple different targets",
                        Issue.ruleToKey("MULTI_TARGET"), new Object[] { e.getKey() },
                        terms.get(idx), idx, termbasePath));
                }
            }
        }

        // Rule 5: case-only duplicates (same lowercase source, different exact source)
        for (Map.Entry<String, List<Integer>> e : byLowerSource.entrySet()) {
            List<Integer> indices = e.getValue();
            if (indices.size() < 2) continue;
            // Check whether there are actually different exact forms
            Set<String> exactForms = new HashSet<>();
            for (int idx : indices) {
                String s = terms.get(idx).getSourceTerm();
                if (s != null) exactForms.add(s.trim());
            }
            if (exactForms.size() > 1) {
                for (int idx : indices) {
                    issues.add(new Issue(Severity.WARNING, "CASE_DUPLICATE",
                        "Case-only duplicate of source \"" + e.getKey() + "\": " + exactForms,
                        Issue.ruleToKey("CASE_DUPLICATE"), new Object[] { e.getKey(), exactForms },
                        terms.get(idx), idx, termbasePath));
                }
            }
        }

        return issues;
    }

    /**
     * Check for cross-termbase conflicts: the same source term appearing in multiple
     * termbases with different targets. Delegates detection to {@link TermConflictUtils}.
     *
     * @param termbases map of file path → term entries (ordered for deterministic output)
     * @return cross-termbase issues; never null
     */
    public static List<Issue> checkCrossTermbase(Map<String, List<TermEntry>> termbases) {
        List<Issue> issues = new ArrayList<>();
        List<String> paths = new ArrayList<>(termbases.keySet());

        for (int a = 0; a < paths.size(); a++) {
            for (int b = a + 1; b < paths.size(); b++) {
                List<TermEntry> termsA = termbases.get(paths.get(a));
                List<TermEntry> termsB = termbases.get(paths.get(b));
                List<TermConflictUtils.Conflict> conflicts = TermConflictUtils.findConflicts(termsA, termsB);
                for (TermConflictUtils.Conflict c : conflicts) {
                    if (!c.isIdenticalTarget()) {
                        issues.add(new Issue(Severity.WARNING, "CROSS_TB_CONFLICT",
                            "Source \"" + c.newTerm.getSourceTerm() + "\" conflicts across termbases: \""
                                + paths.get(a) + "\" → \"" + c.newTerm.getTargetTerm() + "\" vs \""
                                + paths.get(b) + "\" → \"" + c.existingTerm.getTargetTerm() + "\"",
                            Issue.ruleToKey("CROSS_TB_CONFLICT"),
                            new Object[] { c.newTerm.getSourceTerm(), paths.get(a), c.newTerm.getTargetTerm(),
                                paths.get(b), c.existingTerm.getTargetTerm() },
                            c.newTerm, -1, paths.get(a)));
                    } else {
                        issues.add(new Issue(Severity.WARNING, "CROSS_TB_DUPLICATE",
                            "Source \"" + c.newTerm.getSourceTerm() + "\" is duplicated across termbases: \""
                                + paths.get(a) + "\" and \"" + paths.get(b) + "\"",
                            Issue.ruleToKey("CROSS_TB_DUPLICATE"),
                            new Object[] { c.newTerm.getSourceTerm(), paths.get(a), paths.get(b) },
                            c.newTerm, -1, paths.get(a)));
                    }
                }
            }
        }
        return issues;
    }

    /**
     * Convenience: check a single termbase and return an empty list if no issues.
     */
    public static List<Issue> check(List<TermEntry> terms, String termbasePath) {
        if (terms == null || terms.isEmpty()) return Collections.emptyList();
        return checkSingleTermbase(terms, termbasePath);
    }
}

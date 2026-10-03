package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "应改用" (suggested replacement) lookups for the QA-by-target pass (patch-plan 5, step 3.2).
 *
 * <p>When a document hit turns out to be a <em>deprecated</em> translation, the user wants to know
 * which other translation of the same source to switch to. This indexes one termbase once - by
 * source term - so answering a hit never rescans the whole list. Swing-free so it compiles under
 * the CI whitelist and is unit-testable.
 *
 * <p>Scope is deliberately narrow (see the plan's "not doing" list): suggestions are looked up
 * only inside the single termbase this was built from, never across termbases, and no document
 * content is rewritten - the plugin only reports the suggestions.
 */
public final class ReplacementSuggestions {

    /** Grouping of the termbase entries by their trimmed source term; built once in the ctor. */
    private final Map<String, List<TermEntry>> bySource = new HashMap<>();

    public ReplacementSuggestions(List<TermEntry> termbase) {
        if (termbase != null) {
            for (TermEntry entry : termbase) {
                String key = groupKey(entry.getSourceTerm());
                if (key == null) {
                    continue;
                }
                bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
        }
    }

    /** Suggestions for a hit entry; empty unless its status is {@code DEPRECATED}. */
    public List<String> suggestionsFor(TermEntry hit) {
        return hit == null ? new ArrayList<>()
            : suggestionsFor(hit.getSourceTerm(), hit.getTargetTerm(), hit.getStatus());
    }

    /**
     * Alternative target terms for a hit.
     *
     * <p>A non-deprecated hit yields an empty list. For a deprecated hit the suggestions are the
     * target terms of the entries in the same termbase that share the hit's source term (compared
     * with surrounding whitespace trimmed and case-sensitively, because sources are usually CJK),
     * are not themselves deprecated, carry a non-empty target, and are not the hit's own deprecated
     * target. Results follow termbase order and are deduplicated by exact target text.
     */
    public List<String> suggestionsFor(String sourceTerm, String deprecatedTargetTerm, TermStatus status) {
        List<String> out = new ArrayList<>();
        if (status != TermStatus.DEPRECATED) {
            return out;
        }
        List<TermEntry> group = bySource.get(groupKey(sourceTerm));
        if (group == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (TermEntry candidate : group) {
            if (candidate.getStatus() == TermStatus.DEPRECATED) {
                continue;
            }
            String target = candidate.getTargetTerm();
            if (target == null || target.isEmpty()) {
                continue;
            }
            if (target.equals(deprecatedTargetTerm)) {
                continue;
            }
            if (seen.add(target)) {
                out.add(target);
            }
        }
        return out;
    }

    /** The grouping key: trimmed source term, or null when there is nothing to group by. */
    private static String groupKey(String source) {
        if (source == null) {
            return null;
        }
        String trimmed = source.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

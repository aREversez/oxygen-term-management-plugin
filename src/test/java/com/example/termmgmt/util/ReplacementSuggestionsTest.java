package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Patch-plan 5 (step 3.2): "应改用" suggestions for a deprecated target hit. */
class ReplacementSuggestionsTest {

    private static TermEntry entry(String source, String target, TermStatus status) {
        TermEntry e = new TermEntry(source, target);
        if (status != null) {
            e.setStatus(status);
        }
        return e;
    }

    /** T3: a deprecated translation is answered by the preferred one for the same source. */
    @Test
    void deprecatedHit_suggestsTheNonDeprecatedSibling() {
        List<TermEntry> tb = Arrays.asList(
            entry("\u7f51\u683c", "grid", TermStatus.DEPRECATED),
            entry("\u7f51\u683c", "mesh", TermStatus.PREFERRED));
        ReplacementSuggestions s = new ReplacementSuggestions(tb);

        assertEquals(List.of("mesh"),
            s.suggestionsFor("\u7f51\u683c", "grid", TermStatus.DEPRECATED));
        // The entry-based overload agrees.
        assertEquals(List.of("mesh"), s.suggestionsFor(tb.get(0)));
    }

    /** T4: several valid siblings are listed in termbase order; the deprecated self and other
     *  deprecated entries are excluded, an admitted term is included. */
    @Test
    void deprecatedHit_ordersSiblingsAndExcludesDeprecatedAndSelf() {
        List<TermEntry> tb = Arrays.asList(
            entry("\u7f51\u683c", "mesh", TermStatus.PREFERRED),
            entry("\u7f51\u683c", "meshing", TermStatus.ADMITTED),
            entry("\u7f51\u683c", "grid", TermStatus.DEPRECATED),
            entry("\u7f51\u683c", "gratings", TermStatus.DEPRECATED),
            entry("\u8282\u70b9", "node", TermStatus.PREFERRED));
        ReplacementSuggestions s = new ReplacementSuggestions(tb);

        assertEquals(Arrays.asList("mesh", "meshing"),
            s.suggestionsFor("\u7f51\u683c", "grid", TermStatus.DEPRECATED));
    }

    /** T5: a deprecated entry with no valid sibling yields an empty list, never null. */
    @Test
    void deprecatedHit_withoutAnyValidSibling_returnsEmptyList() {
        List<TermEntry> tb = Arrays.asList(
            entry("\u7f51\u683c", "grid", TermStatus.DEPRECATED),
            entry("\u8282\u70b9", "node", TermStatus.PREFERRED));
        ReplacementSuggestions s = new ReplacementSuggestions(tb);

        List<String> out = s.suggestionsFor("\u7f51\u683c", "grid", TermStatus.DEPRECATED);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    /** A non-deprecated hit never gets suggestions, whatever the termbase holds. */
    @Test
    void nonDeprecatedHit_returnsEmpty() {
        List<TermEntry> tb = Arrays.asList(
            entry("\u7f51\u683c", "mesh", TermStatus.PREFERRED),
            entry("\u7f51\u683c", "grid", TermStatus.DEPRECATED));
        ReplacementSuggestions s = new ReplacementSuggestions(tb);

        assertTrue(s.suggestionsFor("\u7f51\u683c", "mesh", TermStatus.PREFERRED).isEmpty());
        assertTrue(s.suggestionsFor("\u7f51\u683c", "mesh", null).isEmpty(),
            "unset/preferred status is not deprecated and gets no suggestions");
    }

    /** T13: grouping trims surrounding whitespace off the source; duplicate targets collapse. */
    @Test
    void groupsByTrimmedSource_andDeduplicatesIdenticalTargets() {
        List<TermEntry> tb = Arrays.asList(
            entry("  \u7f51\u683c  ", "a", null),
            entry("\u7f51\u683c", "mesh", null),
            entry("\u7f51\u683c ", "mesh", null),
            entry("\u7f51\u683c", "b", null));
        ReplacementSuggestions s = new ReplacementSuggestions(tb);

        // The hit's own deprecated target "grid" is dropped; the whitespace-padded sources join the
        // same group; the repeated "mesh" appears once; order follows the termbase.
        assertEquals(Arrays.asList("a", "mesh", "b"),
            s.suggestionsFor("\u7f51\u683c", "grid", TermStatus.DEPRECATED));
    }

    /** Entries with a blank or null source cannot be grouped and simply never offer suggestions. */
    @Test
    void blankSourceEntry_isNotIndexed() {
        List<TermEntry> tb = new ArrayList<>();
        tb.add(entry("", "orphan", null));
        tb.add(entry(null, "orphan2", null));
        ReplacementSuggestions s = new ReplacementSuggestions(tb);

        assertTrue(s.suggestionsFor("", "grid", TermStatus.DEPRECATED).isEmpty());
    }
}

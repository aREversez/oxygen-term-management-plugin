package com.example.termmgmt.service;

import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 15: the decisions behind the external-change check, without Swing. */
class ExternalChangeCheckTest {

    /** Records what the check asked for, so a test can see exactly which files were reloaded. */
    private static final class FakeSource implements ExternalChangeCheck.Source {
        final List<TermbaseConfig> enabled = new ArrayList<>();
        final Set<String> modified;
        final List<String> reloaded = new ArrayList<>();

        FakeSource(Set<String> modified, String... paths) {
            this.modified = modified;
            for (String p : paths) enabled.add(new TermbaseConfig(p, Format.CSV, true));
        }

        @Override public List<TermbaseConfig> enabledConfigs() { return enabled; }
        @Override public boolean isExternallyModified(String filePath) { return modified.contains(filePath); }
        @Override public void reload(String filePath) { reloaded.add(filePath); }
    }

    @Test
    void reloadModified_reloadsOnlyTheFilesThatChanged() {
        FakeSource src = new FakeSource(Set.of("b.csv"), "a.csv", "b.csv", "c.csv");

        boolean any = ExternalChangeCheck.reloadModified(src);

        assertTrue(any);
        assertEquals(List.of("b.csv"), src.reloaded);
    }

    @Test
    void reloadModified_nothingChanged_reloadsNothingAndReportsFalse() {
        FakeSource src = new FakeSource(Set.of(), "a.csv", "b.csv");

        assertFalse(ExternalChangeCheck.reloadModified(src));
        assertTrue(src.reloaded.isEmpty());
    }

    @Test
    void reloadModified_noEnabledTermbases_reportsFalse() {
        assertFalse(ExternalChangeCheck.reloadModified(new FakeSource(Set.of("x.csv"))));
    }

    @Test
    void shouldNotify_onlyWhenReloadedAndStillOnTheSameTab() {
        assertTrue(ExternalChangeCheck.shouldNotify(true, 2, 2));
        assertFalse(ExternalChangeCheck.shouldNotify(true, 2, 0), "user switched tabs meanwhile");
        assertFalse(ExternalChangeCheck.shouldNotify(false, 2, 2), "nothing was reloaded");
        assertFalse(ExternalChangeCheck.shouldNotify(false, 2, 0));
    }

    @Test
    void clearAllTooltips_clearsEveryTabOnce_atTheStartOfEachCheck() {
        List<Integer> cleared = new ArrayList<>();
        ExternalChangeCheck.clearAllTooltips(3, cleared::add);
        assertEquals(List.of(0, 1, 2), cleared, "each tab hint is cleared exactly once");

        List<Integer> none = new ArrayList<>();
        ExternalChangeCheck.clearAllTooltips(0, none::add);
        assertTrue(none.isEmpty(), "a non-positive tab count clears nothing");
    }

    @Test
    void addReloadMarker_appendsOnce_andIsIdempotentOnAlreadyMarkedTitles() {
        assertEquals("术语 （已重载）",
            ExternalChangeCheck.addReloadMarker("术语 ", "（已重载）"));
        String marked = ExternalChangeCheck.addReloadMarker("Terminology", " (reloaded)");
        assertEquals("Terminology (reloaded)", marked);
        assertEquals(marked, ExternalChangeCheck.addReloadMarker(marked, " (reloaded)"),
            "marking twice does not stack the hint");
        assertEquals("(reloaded)", ExternalChangeCheck.addReloadMarker(null, "(reloaded)"),
            "a null title is treated as empty");
    }
}

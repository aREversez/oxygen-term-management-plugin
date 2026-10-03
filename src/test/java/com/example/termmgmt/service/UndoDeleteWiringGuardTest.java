package com.example.termmgmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 13 replaced the Undo-delete path's whole-list overwrite (registry.saveTerms with a
 * pre-delete snapshot, which bypassed the staleness check and clobbered later edits and external
 * changes) with an incremental re-insert through registry.updateTermsAsync.
 *
 * The re-insert logic itself is unit-tested in TermEntryUtilsTest / TermbaseRegistryUpdateTermsTest,
 * but those live in the CI-compiled service/util packages; the Swing panel is not compiled there,
 * so nothing would catch a regression that rewires the button back to registry.saveTerms. This
 * guard reads the panel source directly to keep that wiring honest.
 */
class UndoDeleteWiringGuardTest {

    @Test
    void terminologyPanel_neverWritesBackAWholeListOnUndo() throws IOException {
        Path panel = findPanelSource();
        assertTrue(Files.exists(panel), "could not locate TerminologyPanel.java from "
            + System.getProperty("user.dir"));
        String src = Files.readString(panel);

        assertFalse(src.contains("registry.saveTerms("),
            "Undo must not call registry.saveTerms (whole-file overwrite, no staleness check); "
            + "route the change through registry.updateTermsAsync instead");
        assertTrue(src.contains("TermEntryUtils.reinsertRemoved("),
            "the Undo path should re-insert only the removed entries via TermEntryUtils.reinsertRemoved");
    }

    /** Locate the panel source whether Surefire runs from the module dir or the reactor root. */
    private static Path findPanelSource() {
        String rel = "src/main/java/com/example/termmgmt/ui/TerminologyPanel.java";
        Path[] bases = {
            Path.of(System.getProperty("user.dir")),
            Path.of(System.getProperty("user.dir"), ".."),
            Path.of(System.getProperty("basedir", System.getProperty("user.dir"))),
        };
        for (Path base : bases) {
            Path candidate = base.resolve(rel);
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        return Path.of(rel);
    }
}

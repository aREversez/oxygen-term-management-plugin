package com.example.termmgmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AI suggestion run used to swallow every failed request: a wrong address or key meant up
 * to 200 serial failures and then "no suggestions were returned" with no reason. The decision
 * logic lives in {@link TranslationSuggester.RunGuard} and is unit-tested in
 * TranslationSuggesterTest; the Swing panel is not compiled in CI, so this guard reads its
 * source to keep the run wired to that logic (same approach as UndoDeleteWiringGuardTest).
 */
class SuggestionRunWiringGuardTest {

    @Test
    void terminologyPanel_suggestionRunReportsFailuresAndStopsEarly() throws IOException {
        Path panel = findPanelSource();
        assertTrue(Files.exists(panel), "could not locate TerminologyPanel.java from "
            + System.getProperty("user.dir"));
        String src = Files.readString(panel);

        assertTrue(src.contains("new TranslationSuggester.RunGuard()"), "the run needs a RunGuard");
        assertTrue(src.contains("guard.recordFailure("), "failed requests must be recorded, not skipped");
        assertTrue(src.contains("guard.shouldAbort()"), "the run must stop once the endpoint keeps failing");
        assertTrue(src.contains("guard.firstError()"), "an all-failed run must show the reason");
    }

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

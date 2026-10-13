package com.example.termmgmt.prefs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The termbase table in Preferences shows values derived from each TermbaseConfig and from the
 * file on disk (file name, path, format, status, term count, languages). A plain
 * DefaultTableModel makes every cell editable, so a double-click could overwrite the file name,
 * path or format with arbitrary text, and the Edit / Reload actions read the path back from the
 * table, so the edit would also break them.
 *
 * The Swing preferences page is not compiled by the CI profile, so nothing would catch the table
 * going editable again. This guard reads the page source directly, like UndoDeleteWiringGuardTest.
 */
class PreferenceTableReadOnlyGuardTest {

    @Test
    void termbaseTableModel_overridesIsCellEditableToFalse() throws IOException {
        Path page = findPageSource();
        assertTrue(Files.exists(page), "could not locate TermManagementPreferencePage.java from "
            + System.getProperty("user.dir"));
        String src = Files.readString(page);

        assertFalse(src.contains("tableModel = new DefaultTableModel(columns, 0);"),
            "the termbase table model must not be a plain DefaultTableModel: every cell would be editable");

        int modelStart = src.indexOf("tableModel = new DefaultTableModel(columns, 0) {");
        assertTrue(modelStart >= 0,
            "the termbase table model should be an anonymous DefaultTableModel overriding isCellEditable");

        int overrideStart = src.indexOf("public boolean isCellEditable(int row, int column)", modelStart);
        assertTrue(overrideStart >= 0, "the termbase table model must override isCellEditable");

        int returnStart = src.indexOf("return", overrideStart);
        int returnEnd = src.indexOf(';', returnStart);
        assertEquals("return false", src.substring(returnStart, returnEnd).trim(),
            "isCellEditable must return false for every cell");
    }

    /** Locate the page source whether Surefire runs from the module dir or the reactor root. */
    private static Path findPageSource() {
        String rel = "src/main/java/com/example/termmgmt/prefs/TermManagementPreferencePage.java";
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

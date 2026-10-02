package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.TermEntryUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 7.4 (format-level end-to-end): when the file on disk was edited by an external application in
 * a way the plugin reloads into a fresh list, an edit made through the dialog matches the entry
 * by VALUE (its identity is gone) and merges only the user-changed fields onto the fresh entry.
 * For each of the three formats this proves that an external change to an extra field survives
 * such a source-term edit, exercising the real load/merge/save round-trip rather than the merge
 * helper in isolation (the helper itself is covered by TermEntryUtilsTest).
 */
class TermEntryMergeRoundTripTest {

    @TempDir
    Path tempDir;

    /** The stale snapshot the UI held, and the fresh reloaded list, are matched by value. */
    private void editSourceByValueMerge(List<TermEntry> fresh, TermEntry staleOriginal, String newSource) {
        TermEntry userEdited = staleOriginal.copy();
        userEdited.setSourceTerm(newSource);
        assertTrue(TermEntryUtils.replaceEntryMerging(fresh, staleOriginal, userEdited),
            "the entry must be located by value in the reloaded list");
        // Identity match must be impossible here: the fresh list holds different instances.
        assertNotSame(staleOriginal, fresh.get(0));
    }

    @Test
    void csv_externalExtraColumnSurvivesSourceEdit() throws Exception {
        Path file = tempDir.resolve("merge.csv");
        Files.writeString(file, "zh-cn,en-us,note\n网格,mesh,old\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        TermEntry stale = new ArrayList<>(CsvTermbaseHandler.loadTerms(config)).get(0);

        // An external application changes only the note column on disk.
        Files.writeString(file, "zh-cn,en-us,note\n网格,mesh,external\n");

        List<TermEntry> fresh = new ArrayList<>(CsvTermbaseHandler.loadTerms(config));
        editSourceByValueMerge(fresh, stale, "网格单元");
        CsvTermbaseHandler.saveTerms(config, fresh);

        List<TermEntry> reloaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals("网格单元", reloaded.get(0).getSourceTerm(), "the plugin's source edit must land");
        assertEquals("external", reloaded.get(0).getExtraFields().get("note"),
            "the externally edited note column must survive");
    }

    @Test
    void xlsx_externalExtraColumnSurvivesSourceEdit() throws Exception {
        Path file = tempDir.resolve("merge.xlsx");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        config.setExtraColumns(List.of("note"));

        TermEntry seed = new TermEntry("网格", "mesh");
        seed.getExtraFields().put("note", "old");
        XlsxTermbaseHandler.saveTerms(config, List.of(seed));

        TermEntry stale = new ArrayList<>(XlsxTermbaseHandler.loadTerms(config)).get(0);

        // External application rewrites the note cell.
        TermEntry external = new TermEntry("网格", "mesh");
        external.getExtraFields().put("note", "external");
        XlsxTermbaseHandler.saveTerms(config, List.of(external));

        List<TermEntry> fresh = new ArrayList<>(XlsxTermbaseHandler.loadTerms(config));
        editSourceByValueMerge(fresh, stale, "网格单元");
        XlsxTermbaseHandler.saveTerms(config, fresh);

        List<TermEntry> reloaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals("网格单元", reloaded.get(0).getSourceTerm(), "the plugin's source edit must land");
        assertEquals("external", reloaded.get(0).getExtraFields().get("note"),
            "the externally edited note cell must survive");
    }

    @Test
    void tbx_externalDescripsSurviveSourceEdit() throws Exception {
        Path file = tempDir.resolve("merge.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"t1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><ntig><termGrp><term>网格</term></termGrp></ntig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><ntig><termGrp><term>mesh</term></termGrp></ntig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        TermEntry stale = new ArrayList<>(TbxTermbaseHandler.loadTerms(config)).get(0);

        // External application adds a <descrip> the plugin does not model.
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"t1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><ntig><termGrp><term>网格</term></termGrp></ntig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><ntig><termGrp><term>mesh</term></termGrp></ntig></langSet>\n"
            + "      <descrip type=\"definition\">external definition</descrip>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");

        List<TermEntry> fresh = new ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        editSourceByValueMerge(fresh, stale, "网格单元");
        TbxTermbaseHandler.saveTerms(config, fresh);

        String saved = Files.readString(file);
        assertTrue(saved.contains("external definition"),
            "the externally added descrip must survive the source-term edit");
        assertTrue(saved.contains("网格单元"), "the plugin's source edit must land");
    }
}

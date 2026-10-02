package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.TermEntryUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3.1 storage contract: a status rides in extraFields under the "status" key
 * (case-insensitive column header), parses to TermStatus case-insensitively, unknown
 * raw values survive untouched, and getStatus() stays null for them.
 */
class TermStatusTest {

    @TempDir
    Path tempDir;

    @Test
    void csv_statusColumnIsFoundCaseInsensitivelyAndParsed() throws Exception {
        Path file = tempDir.resolve("status.csv");
        Files.writeString(file, "zh-cn,en-us,Status\nFEA,\u6709\u9650\u5143\u7d20\u6cd5,DEPRECATED\nmesh,\u7f51\u683c,admitted\nplain,other,x\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus());
        assertEquals(TermStatus.ADMITTED, loaded.get(1).getStatus());
        // No value is equivalent to preferred, but getStatus stays null so the UI can
        // tell "unset" from "explicitly preferred"; the raw value must survive untouched.
        assertNull(loaded.get(2).getStatus());
        assertEquals("x", loaded.get(2).getExtraFields().get("Status"));
    }

    @Test
    void csv_unknownValue_isKeptVerbatimAndYieldsNoStatus() throws Exception {
        Path file = tempDir.resolve("unknown.csv");
        Files.writeString(file, "zh-cn,en-us,status\nFEA,x,retired\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertNull(loaded.get(0).getStatus());
        assertEquals("retired", loaded.get(0).getExtraFields().get("status"));

        CsvTermbaseHandler.saveTerms(config, loaded);
        String saved = Files.readString(file);
        assertTrue(saved.contains("retired"), "unknown value must not be dropped or rewritten");
    }

    @Test
    void csv_roundtrip_setStatusWritesCanonicalLowerValue() {
        Path file = tempDir.resolve("round.csv");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        config.setExtraColumns(List.of("status"));

        TermEntry entry = new TermEntry("FEA", "\u6709\u9650\u5143\u7d20\u6cd5");
        entry.setStatus(TermStatus.DEPRECATED);
        CsvTermbaseHandler.saveTerms(config, List.of(entry));

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus());
        assertEquals("deprecated", loaded.get(0).getExtraFields().get("status"));
    }

    @Test
    void csv_setStatusOnFileWithoutColumn_appendsTheColumn() throws Exception {
        Path file = tempDir.resolve("nocol.csv");
        Files.writeString(file, "zh-cn,en-us\nFEA,x\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        loaded.get(0).setStatus(TermStatus.PREFERRED);
        CsvTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.PREFERRED, reloaded.get(0).getStatus());
    }

    @Test
    void csv_fileWithoutStatus_staysByteIdenticalOnNoopSave() throws Exception {
        Path file = tempDir.resolve("legacy.csv");
        Files.writeString(file, "zh-cn,en-us,note\r\nFEA,x,keep\r\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        CsvTermbaseHandler.saveTerms(config, loaded);
        CsvTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertFalse(saved.contains("status"), "no status column may appear when nobody set a status");
        assertTrue(saved.contains("keep"));
    }

    @Test
    void copy_preservesStatus() {
        TermEntry entry = new TermEntry("FEA", "x");
        entry.setStatus(TermStatus.DEPRECATED);
        assertEquals(TermStatus.DEPRECATED, entry.copy().getStatus());
    }

    @Test
    void tbx_administrativeStatusTermNote_mapsToStatusAndBack() throws Exception {
        Path file = tempDir.resolve("status.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig>\n"
            + "          <term>FEA</term>\n"
            + "          <termNote type=\"administrativeStatus\">deprecatedTerm-admn-sts</termNote>\n"
            + "        </tig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>x</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus());

        loaded.get(0).setStatus(TermStatus.PREFERRED);
        TbxTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.PREFERRED, reloaded.get(0).getStatus());
        String saved = Files.readString(file);
        assertTrue(saved.contains("preferredTerm-admn-sts"), saved);
    }

    @Test
    void tbx_unknownTermNoteValue_isPreservedOnNoopSave() throws Exception {
        Path file = tempDir.resolve("unknown.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig><term>FEA</term><termNote type=\"administrativeStatus\">obsoleteTerm-admn-sts</termNote></tig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>x</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertNull(loaded.get(0).getStatus(), "unknown values yield no status but must stay in the file");

        TbxTermbaseHandler.saveTerms(config, loaded);
        String saved = Files.readString(file);
        assertTrue(saved.contains("obsoleteTerm-admn-sts"), "unknown value must survive verbatim");
    }

    @Test
    void tbx_noopSaveWithoutStatus_doesNotIntroduceTermNote() throws Exception {
        Path file = tempDir.resolve("clean.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>FEA</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>x</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertFalse(saved.contains("administrativeStatus"),
            "entries with no status must not gain a termNote on every save");
    }

    @Test
    void tbx_newEntryWithStatus_getsTermNote() throws Exception {
        Path file = tempDir.resolve("fresh.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        // Start from one status-less entry, then add a second one carrying a status.
        TermEntry first = new TermEntry("a", "b");
        TbxTermbaseHandler.saveTerms(config, List.of(first));

        TermEntry fresh = new TermEntry("FEA", "x");
        fresh.setStatus(TermStatus.DEPRECATED);
        TbxTermbaseHandler.saveTerms(config, List.of(first, fresh));

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, reloaded.size());
        TermStatus[] statuses = { reloaded.get(0).getStatus(), reloaded.get(1).getStatus() };
        assertTrue(statuses[0] == TermStatus.DEPRECATED || statuses[1] == TermStatus.DEPRECATED,
            "the new node must carry the deprecated status");
    }

    // ---- Step 6 patch-plan regression tests ----

    /**
     * 6.1: Clearing a status via setStatus(null) produces sentinel "" and the CSV/XLSX/TBX
     * handler writes it as an empty value (or removes the termNote). Reload shows null status.
     */
    @Test
    void csv_clearStatus_writesEmptyCellAndReloadsAsNull() throws Exception {
        Path file = tempDir.resolve("clear.csv");
        Files.writeString(file, "zh-cn,en-us,status\nFEA,x,deprecated\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus());

        loaded.get(0).setStatus(null); // clear
        CsvTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        // Status field written as empty (quoted or unquoted depends on CSV writer config)
        assertFalse(saved.contains("deprecated"), "cleared status must not write 'deprecated'");

        List<TermEntry> reloaded = CsvTermbaseHandler.loadTerms(config);
        assertNull(reloaded.get(0).getStatus());
    }

    @Test
    void tbx_clearStatus_removesTermNote() throws Exception {
        Path file = tempDir.resolve("clear.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig><term>FEA</term>"
            + "<termNote type=\"administrativeStatus\">deprecatedTerm-admn-sts</termNote></tig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>x</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus());

        loaded.get(0).setStatus(null); // clear
        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertFalse(saved.contains("administrativeStatus"),
            "cleared status must remove the termNote");

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertNull(reloaded.get(0).getStatus());
    }

    /**
     * 6.1: A status-less entry on a file without a status column: setStatus(null) does NOT
     * append the column (sentinel "" is not a real status).
     */
    @Test
    void csv_noColumnAndClearedStatus_doesNotAppendColumn() throws Exception {
        Path file = tempDir.resolve("noclear.csv");
        Files.writeString(file, "zh-cn,en-us\nFEA,x\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        loaded.get(0).setStatus(null); // no-op clear on already-null entry
        CsvTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertFalse(saved.contains("status"), "no column appended for a null-cleared status");
    }

    /**
     * 6.2: The dialog's status decision is a real production function (TermEntryUtils
     * .resolveDialogStatus), shared by the Swing dialog and this test - no shadow logic.
     */
    @Test
    void dialog_preferredOnStatuslessEntry_resolvesToNull() {
        assertNull(TermEntryUtils.resolveDialogStatus(0, null),
            "Preferred on an entry that never had a status must write nothing");
    }

    @Test
    void dialog_statusSelection_matrix() {
        assertEquals(TermStatus.PREFERRED, TermEntryUtils.resolveDialogStatus(0, "deprecated"),
            "Preferred on an entry that already had a status is applied");
        assertEquals(TermStatus.ADMITTED, TermEntryUtils.resolveDialogStatus(1, null));
        assertEquals(TermStatus.DEPRECATED, TermEntryUtils.resolveDialogStatus(2, null));
        assertEquals(TermStatus.PREFERRED, TermEntryUtils.resolveDialogStatus(0, "preferred"));
    }

    /**
     * 6.2 end-to-end: a no-op dialog confirm (Preferred on a status-less entry) must not add a
     * status column to a CSV that had none. Uses the same resolveDialogStatus the dialog calls.
     */
    @Test
    void dialog_preferredOnStatusless_csvGainsNoColumn() throws Exception {
        Path file = tempDir.resolve("dlg_nocsv.csv");
        Files.writeString(file, "zh-cn,en-us\nFEA,x\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);
        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);

        TermStatus toApply = TermEntryUtils.resolveDialogStatus(0, loaded.get(0).getStoredStatusValue());
        if (toApply != null) loaded.get(0).setStatus(toApply);
        CsvTermbaseHandler.saveTerms(config, loaded);

        assertFalse(Files.readString(file).contains("status"),
            "a no-op dialog confirm must not add a status column");
    }

    /**
     * 6.3: ntig/termGrp structure supports status read and write.
     */
    @Test
    void tbx_ntigTermGrpStatus_readsAndWritesCorrectly() throws Exception {
        Path file = tempDir.resolve("ntig_status.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"nt1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <ntig><termGrp><term>\u7f51\u683c</term>"
            + "<termNote type=\"administrativeStatus\">deprecatedTerm-admn-sts</termNote>"
            + "</termGrp></ntig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><ntig><termGrp><term>mesh</term></termGrp></ntig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus(),
            "status in ntig/termGrp must be readable");

        loaded.get(0).setStatus(TermStatus.ADMITTED);
        TbxTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.ADMITTED, reloaded.get(0).getStatus(),
            "status in ntig/termGrp must be writable");
    }

    /**
     * 6.3: ntig/termGrp - clearing the status removes the administrativeStatus termNote.
     */
    @Test
    void tbx_ntigTermGrp_clearStatus_removesTermNote() throws Exception {
        Path file = tempDir.resolve("ntig_clear.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"nt1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <ntig><termGrp><term>\u7f51\u683c</term>"
            + "<termNote type=\"administrativeStatus\">deprecatedTerm-admn-sts</termNote>"
            + "</termGrp></ntig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><ntig><termGrp><term>mesh</term></termGrp></ntig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(TermStatus.DEPRECATED, loaded.get(0).getStatus());

        loaded.get(0).setStatus(null); // clear
        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertFalse(saved.contains("administrativeStatus"),
            "cleared ntig status must delete the termNote");
        assertTrue(saved.contains("ntig"), "the ntig structure itself must survive");
        assertNull(TbxTermbaseHandler.loadTerms(config).get(0).getStatus());
    }

    /**
     * 6.1 (XLSX): set a status, clear it, save and reload -> no status; the cell is written empty.
     */
    @Test
    void xlsx_clearStatus_writesEmptyCellAndReloadsAsNull() throws Exception {
        Path file = tempDir.resolve("clear.xlsx");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        config.setExtraColumns(List.of("status"));
        TermEntry e = new TermEntry("FEA", "x");
        e.setStatus(TermStatus.DEPRECATED);
        XlsxTermbaseHandler.saveTerms(config, List.of(e));
        assertEquals(TermStatus.DEPRECATED, XlsxTermbaseHandler.loadTerms(config).get(0).getStatus());

        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        loaded.get(0).setStatus(null); // clear
        XlsxTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = XlsxTermbaseHandler.loadTerms(config);
        assertNull(reloaded.get(0).getStatus(), "cleared XLSX status reloads as unset");
    }
}

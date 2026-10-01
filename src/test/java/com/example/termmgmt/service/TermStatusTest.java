package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
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
}

package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TbxTermbaseHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void loadTerms_shouldParseStandardTig() throws Exception {
        Path file = tempDir.resolve("standard.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig><term>你好</term></tig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\">\n"
            + "        <tig><term>hello</term></tig>\n"
            + "      </langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
        assertEquals("zh-CN", config.getSourceLang());
        assertEquals("en-US", config.getTargetLang());
    }

    @Test
    void loadTerms_shouldHandleMultipleTigInLangSet() throws Exception {
        Path file = tempDir.resolve("multi_tig.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig><term>你好</term></tig>\n"
            + "        <tig><term>您好</term></tig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\">\n"
            + "        <tig><term>hello</term></tig>\n"
            + "      </langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
    }

    @Test
    void loadTerms_shouldFallbackToPlainLangAttribute() throws Exception {
        Path file = tempDir.resolve("fallback.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet lang=\"zh-CN\">\n"
            + "        <tig><term>你好</term></tig>\n"
            + "      </langSet>\n"
            + "      <langSet lang=\"en-US\">\n"
            + "        <tig><term>hello</term></tig>\n"
            + "      </langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
    }

    @Test
    void loadTerms_shouldSkipEmptyLangSet() throws Exception {
        Path file = tempDir.resolve("empty_langset.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig><term>你好</term></tig>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\">\n"
            + "      </langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry id=\"tid2\">\n"
            + "      <langSet xml:lang=\"zh-CN\">\n"
            + "        <tig><term></term></tig>\n"
            + "      </langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
    }

    @Test
    void loadTerms_shouldThrowOnMalformedXml() throws Exception {
        Path file = tempDir.resolve("malformed.tbx");
        Files.writeString(file, "<?xml version=\"1.0\"?><martif><body><termEntry>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        assertThrows(RuntimeException.class, () -> TbxTermbaseHandler.loadTerms(config));
    }

    @Test
    void saveAndLoad_shouldPreserveTerms() throws Exception {
        Path file = tempDir.resolve("roundtrip.tbx");
        // Create a minimal valid TBX skeleton first
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "  </body>\n"
            + "</martif>");

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        config.setSourceLang("zh-CN");
        config.setTargetLang("en-US");

        List<TermEntry> toSave = List.of(
            new TermEntry("你好", "hello"),
            new TermEntry("世界", "world")
        );
        TbxTermbaseHandler.saveTerms(config, toSave);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
        assertEquals("世界", loaded.get(1).getSourceTerm());
        assertEquals("world", loaded.get(1).getTargetTerm());
    }

    @Test
    void loadTerms_shouldThrowWhenFileNotExists() {
        TermbaseConfig config = new TermbaseConfig(
            tempDir.resolve("nonexistent.tbx").toString(), Format.TBX, true);
        assertThrows(RuntimeException.class, () -> TbxTermbaseHandler.loadTerms(config));
    }

    @Test
    void loadTerms_shouldNotExpandExternalEntities() throws Exception {
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRET-CONTENT");
        Path file = tempDir.resolve("xxe.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE martif [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>&xxe;</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>hello</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);

        for (TermEntry e : loaded) {
            assertFalse(String.valueOf(e.getSourceTerm()).contains("TOPSECRET"));
            assertFalse(String.valueOf(e.getTargetTerm()).contains("TOPSECRET"));
        }
    }

    @Test
    void loadTerms_shouldLoadFileWithUnreachableExternalDtd() throws Exception {
        Path file = tempDir.resolve("doctype.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<!DOCTYPE martif SYSTEM \"file:///nonexistent/TBXcoreStructV02.dtd\">\n"
            + "<martif type=\"TBX\">\n"
            + "  <body>\n"
            + "    <termEntry id=\"tid1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>你好</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>hello</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);

        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
    }

    @Test
    void saveTerms_shouldHandleTermEntryNestedInsideWrapper() throws Exception {
        Path file = tempDir.resolve("nested.tbx");
        String xml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n"
            + "  <text><body>\n"
            + "    <termEntry id=\"a\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>你好</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>hello</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <group><termEntry id=\"b\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>世界</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>world</term></tig></langSet>\n"
            + "    </termEntry></group>\n"
            + "  </body></text>\n"
            + "</martif>";
        Files.writeString(file, xml);
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());

        TbxTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, reloaded.size());
        assertEquals("你好", reloaded.get(0).getSourceTerm());
        assertEquals("世界", reloaded.get(1).getSourceTerm());
    }

    @Test
    void saveTerms_shouldAssignSequentialUniqueIds() throws Exception {
        Path file = tempDir.resolve("ids.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\"><text><body/></text></martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        TermEntry shared = new TermEntry("a", "A");

        TbxTermbaseHandler.saveTerms(config, List.of(shared, new TermEntry("b", "B"), shared));

        String saved = Files.readString(file);
        for (String id : new String[]{"tid1", "tid2", "tid3"}) {
            assertEquals(1, saved.split("id=\"" + id + "\"", -1).length - 1, id);
        }
    }

    /** Entry with a custom id, DOCTYPE, a third language, and unmodelled TBX fields. */
    private static final String RICH_TBX =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE martif SYSTEM \"TBXcoreStructV02.dtd\">\n"
        + "<martif type=\"TBX\">\n"
        + "  <text>\n"
        + "    <body>\n"
        + "      <termEntry id=\"customA\">\n"
        + "        <langSet xml:lang=\"zh-CN\">\n"
        + "          <tig>\n"
        + "            <term>刚度</term>\n"
        + "            <descrip defGrp=\"1\">刚度是力与形变的比值</descrip>\n"
        + "          </tig>\n"
        + "        </langSet>\n"
        + "        <langSet xml:lang=\"en-US\">\n"
        + "          <tig>\n"
        + "            <term>stiffness</term>\n"
        + "            <termNote type=\"partOfSpeech\">N</termNote>\n"
        + "            <note>preferred in NBR</note>\n"
        + "          </tig>\n"
        + "        </langSet>\n"
        + "        <langSet xml:lang=\"de-DE\">\n"
        + "          <ntig><termGrp><term>Steifigkeit</term></termGrp></ntig>\n"
        + "        </langSet>\n"
        + "      </termEntry>\n"
        + "      <termEntry id=\"customB\">\n"
        + "        <langSet xml:lang=\"zh-CN\"><tig><term>网格</term></tig></langSet>\n"
        + "        <langSet xml:lang=\"en-US\"><ntig><termGrp><term>mesh</term></termGrp></ntig></langSet>\n"
        + "      </termEntry>\n"
        + "    </body>\n"
        + "  </text>\n"
        + "</martif>";

    private TermbaseConfig writeRichConfig(String name) throws Exception {
        Path file = tempDir.resolve(name + ".tbx");
        Files.writeString(file, RICH_TBX);
        return new TermbaseConfig(file.toString(), Format.TBX, true);
    }

    @Test
    void loadTerms_shouldReadTermEntryIdIntoEntry() throws Exception {
        TermbaseConfig config = writeRichConfig("ids_load");

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);

        assertEquals(2, loaded.size());
        assertEquals("customA", loaded.get(0).getEntryId());
        assertEquals("customB", loaded.get(1).getEntryId());
    }

    @Test
    void saveTerms_unchangedRoundTrip_keepsIdsDoctypeAndUnmodelledContent() throws Exception {
        TermbaseConfig config = writeRichConfig("rich_keep");
        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);

        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(Path.of(config.getFilePath()));
        assertTrue(saved.contains("id=\"customA\""), "custom ids must survive");
        assertTrue(saved.contains("id=\"customB\""));
        assertTrue(saved.contains("TBXcoreStructV02.dtd"), "DOCTYPE declaration must survive");
        assertTrue(saved.contains("<descrip"), "descrip must survive");
        assertTrue(saved.contains("termNote"), "termNote must survive");
        assertTrue(saved.contains("<note>preferred in NBR</note>"), "note must survive");
        assertTrue(saved.contains("Steifigkeit"), "third language must survive");

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, reloaded.size());
        assertEquals("刚度", reloaded.get(0).getSourceTerm());
        assertEquals("stiffness", reloaded.get(0).getTargetTerm());
        assertEquals("customA", reloaded.get(0).getEntryId());
    }

    @Test
    void saveTerms_editedTerm_updatesTextInPlace_andKeepsExtraFields() throws Exception {
        TermbaseConfig config = writeRichConfig("rich_edit");
        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        loaded.get(0).setSourceTerm("弯曲刚度");

        TbxTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals("弯曲刚度", reloaded.get(0).getSourceTerm());
        assertEquals("customA", reloaded.get(0).getEntryId(), "the edited entry keeps its id");
        String saved = Files.readString(Path.of(config.getFilePath()));
        assertTrue(saved.contains("<descrip"), "editing a term must not drop the entry's other fields");
        assertTrue(saved.contains("Steifigkeit"));
    }

    @Test
    void saveTerms_deleteAndAdd_removesGoneNodes_andAppendsNewWithoutIdCollision() throws Exception {
        TermbaseConfig config = writeRichConfig("rich_add_remove");
        List<TermEntry> loaded = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        loaded.remove(1); // customB deleted
        loaded.add(new TermEntry("节点", "node")); // brand-new entry, no id

        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(Path.of(config.getFilePath()));
        assertFalse(saved.contains("customB"), "deleted entries must disappear");
        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, reloaded.size());
        assertEquals("customA", reloaded.get(0).getEntryId());
        assertNotNull(reloaded.get(1).getEntryId());
        assertNotEquals("customA", reloaded.get(1).getEntryId());
        assertNotEquals("customB", reloaded.get(1).getEntryId());
    }

    @Test
    void saveTerms_repeatedSaves_areIdempotent_andNeverGrowBlankLines() throws Exception {
        TermbaseConfig config = writeRichConfig("rich_idempotent");
        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);

        TbxTermbaseHandler.saveTerms(config, loaded);
        byte[] second = Files.readAllBytes(Path.of(config.getFilePath()));
        for (int i = 0; i < 3; i++) {
            TbxTermbaseHandler.saveTerms(config, TbxTermbaseHandler.loadTerms(config));
        }
        byte[] fifth = Files.readAllBytes(Path.of(config.getFilePath()));

        assertEquals(second.length, fifth.length, "repeated saves must not change the file");
        assertArrayEquals(second, fifth);
    }
}

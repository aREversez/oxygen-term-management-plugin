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

    // ---- Step 5 patch-plan regression tests ----

    /**
     * 5.1: A termEntry without id that carries a <descrip> must survive a no-op save
     * (original bug: it was deleted and replaced by a fresh 2-lang node at end-of-file).
     */
    @Test
    void saveTerms_noIdEntryWithDescrip_preservesContentAndPosition() throws Exception {
        Path file = tempDir.resolve("no_id_descrip.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"first\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>first</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>first-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>KEEPME</term>"
            + "        <descrip>Important definition</descrip>"
            + "      </tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>keep-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        assertNull(loaded.get(1).getEntryId(), "no-id entry");
        assertEquals(1, loaded.get(1).getEntryOrdinal(), "ordinal recorded");

        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertTrue(saved.contains("KEEPME"), "source term survived");
        assertTrue(saved.contains("Important definition"), "descrip must NOT be lost");
        // Verify position: KEEPME comes after "first" in document order
        int firstPos = saved.indexOf("first");
        int keepPos = saved.indexOf("KEEPME");
        assertTrue(firstPos < keepPos, "entry must stay at original document position");
    }

    /**
     * 5.2: A termEntry that is not loadable (no term text in any langSet, only a descrip)
     * must survive a save untouched (original bug: it was deleted because nobody claimed it).
     */
    @Test
    void saveTerms_nonLoadableEntry_staysInFile() throws Exception {
        Path file = tempDir.resolve("non_loadable.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"e1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>abc</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>abc-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry id=\"orphan\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><descrip>ORPHAN</descrip></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size(), "orphan entry is not loadable");

        TbxTermbaseHandler.saveTerms(config, loaded);

        String saved = Files.readString(file);
        assertTrue(saved.contains("ORPHAN"), "non-loadable entry must NOT be deleted");
    }

    /**
     * 5.3: A 3-lang entry (zh/en/de), clearing the en target and saving then reloading
     * must still treat en as the target (not de). Original bug: selectLangSets skipped
     * empty terms, so de shifted to position 1 (target).
     */
    @Test
    void saveTerms_threeLangClearTarget_reloadStillSelectsCorrectLanguages() throws Exception {
        Path file = tempDir.resolve("three_lang.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"tri\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>\u7f51\u7edc</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>mesh</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"de-DE\"><tig><term>Netz</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        // Load: zh=source, en=target
        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("\u7f51\u7edc", loaded.get(0).getSourceTerm());
        assertEquals("mesh", loaded.get(0).getTargetTerm());

        // Clear target (user action)
        loaded.get(0).setTargetTerm("");
        TbxTermbaseHandler.saveTerms(config, loaded);

        // Reload: en should still be the target position (empty/null), NOT de
        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(1, reloaded.size());
        assertEquals("\u7f51\u7edc", reloaded.get(0).getSourceTerm());
        assertNull(reloaded.get(0).getTargetTerm(),
            "cleared target stays null; de must not shift into target position");
        assertEquals("zh-CN", config.getSourceLang());
        assertEquals("en-US", config.getTargetLang());

        // Verify de is still present in the file
        String saved = Files.readString(file);
        assertTrue(saved.contains("Netz"), "third language must survive");
    }

    /**
     * 5.5 (functional aspect): Multiple entries with same id are handled correctly.
     * (Performance is measured manually; this test just validates correctness of Map claim.)
     */
    @Test
    void saveTerms_duplicateIds_claimsNodesInDocumentOrder() throws Exception {
        Path file = tempDir.resolve("dup_id.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"dup\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>A1</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>a1</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry id=\"dup\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>A2</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>a2</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> loaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        // Both have same entryId "dup" but different ordinals
        loaded.get(0).setSourceTerm("A1-modified");
        loaded.get(1).setSourceTerm("A2-modified");

        TbxTermbaseHandler.saveTerms(config, loaded);

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals("A1-modified", reloaded.get(0).getSourceTerm());
        assertEquals("A2-modified", reloaded.get(1).getSourceTerm());
    }

    // ---- Step 10 patch-plan regression tests: claiming info must survive a save ----

    /** Parse the file and, for every loadable termEntry, map its source term to the termEntry id
     *  and to the text of the first &lt;descrip&gt; found inside the node (empty string if none). */
    private static void readNodeMarkers(Path file, java.util.Map<String, String> sourceToId,
            java.util.Map<String, String> sourceToDescrip) throws Exception {
        javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
            .newInstance().newDocumentBuilder();
        org.w3c.dom.Document doc = db.parse(file.toFile());
        org.w3c.dom.NodeList nodes = doc.getElementsByTagName("termEntry");
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element te = (org.w3c.dom.Element) nodes.item(i);
            org.w3c.dom.NodeList lsList = te.getElementsByTagName("langSet");
            String source = null;
            for (int j = 0; j < lsList.getLength(); j++) {
                org.w3c.dom.NodeList terms = ((org.w3c.dom.Element) lsList.item(j)).getElementsByTagName("term");
                if (terms.getLength() > 0) {
                    String t = terms.item(0).getTextContent();
                    if (t != null && !t.trim().isEmpty()) { source = t.trim(); break; }
                }
            }
            if (source == null) continue; // non-loadable node: skipped
            String id = te.getAttribute("id");
            org.w3c.dom.NodeList d = te.getElementsByTagName("descrip");
            String descrip = d.getLength() > 0 ? d.item(0).getTextContent().trim() : "";
            sourceToId.put(source, id);
            sourceToDescrip.put(source, descrip);
        }
    }

    /**
     * 10.1 repro one: three no-id entries A/B/C each carry a distinctive &lt;descrip&gt;. After
     * deleting A and saving, editing B and saving AGAIN on the same reused list (never reloaded)
     * must not scramble the descriptions. Bug: stale entryOrdinal made B claim C's node, so
     * DEF-B was lost and DEF-C migrated onto the edited entry.
     */
    @Test
    void saveTerms_reusedListAfterDelete_descripsStayWithCorrectEntries() throws Exception {
        Path file = tempDir.resolve("ordinal_drift.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>A</term><descrip>DEF-A</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>a-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>B</term><descrip>DEF-B</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>b-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>C</term><descrip>DEF-C</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>c-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        assertEquals(3, list.size());
        // Step 1: delete A, save (list reused after this, never reloaded).
        list.remove(0);
        TbxTermbaseHandler.saveTerms(config, list);
        // Step 2: edit B's translation, save again on the SAME list objects.
        list.get(0).setTargetTerm("B-edited");
        TbxTermbaseHandler.saveTerms(config, list);

        java.util.Map<String, String> id = new java.util.HashMap<>();
        java.util.Map<String, String> desc = new java.util.HashMap<>();
        readNodeMarkers(file, id, desc);
        assertEquals("DEF-B", desc.get("B"), "B must keep its own description");
        assertEquals("DEF-C", desc.get("C"), "C must keep its own description");
        assertEquals(2, id.size(), "only B and C remain");
    }

    /**
     * 10.1 repro two: a freshly added entry gets an id on the first save; editing it and saving
     * again on the reused list must keep that id. Bug: the assigned id was never written back to
     * the TermEntry, so the second save treated it as new, deleted the tid1 node and minted tid2.
     */
    @Test
    void saveTerms_reusedList_newEntryIdStableAcrossSaves() throws Exception {
        Path file = tempDir.resolve("id_stability.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\"><body></body></martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> list = new java.util.ArrayList<>();
        TermEntry e = new TermEntry("x", "y");
        list.add(e);
        TbxTermbaseHandler.saveTerms(config, list);
        // Write-back side effect: the entry now knows its on-disk id.
        assertEquals("tid1", e.getEntryId(), "save must write the assigned id back to the entry");

        // Edit and save AGAIN without reloading.
        e.setTargetTerm("y2");
        TbxTermbaseHandler.saveTerms(config, list);

        java.util.Map<String, String> id = new java.util.HashMap<>();
        java.util.Map<String, String> desc = new java.util.HashMap<>();
        readNodeMarkers(file, id, desc);
        assertEquals(1, id.size(), "still exactly one node");
        assertEquals("tid1", id.get("x"), "id must not change between saves");
        assertEquals("tid1", e.getEntryId());

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals("y2", reloaded.get(0).getTargetTerm());
    }

    /**
     * 10.3: add three new entries, save, then edit the second and save again on the reused
     * list. The three node ids and their document order must be stable across both saves.
     */
    @Test
    void saveTerms_addThreeThenEditSecond_idsAndOrderStable() throws Exception {
        Path file = tempDir.resolve("multi_add.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\"><body></body></martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> list = new java.util.ArrayList<>();
        list.add(new TermEntry("n1", "e1"));
        list.add(new TermEntry("n2", "e2"));
        list.add(new TermEntry("n3", "e3"));
        TbxTermbaseHandler.saveTerms(config, list);
        java.util.List<String> ids1 = new java.util.ArrayList<>();
        for (TermEntry e : list) ids1.add(e.getEntryId());
        assertEquals(java.util.Arrays.asList("tid1", "tid2", "tid3"), ids1);

        list.get(1).setTargetTerm("e2-edited");
        TbxTermbaseHandler.saveTerms(config, list);
        java.util.List<String> ids2 = new java.util.ArrayList<>();
        for (TermEntry e : list) ids2.add(e.getEntryId());
        assertEquals(ids1, ids2, "ids stable across the second save");

        List<TermEntry> r = TbxTermbaseHandler.loadTerms(config);
        assertEquals(3, r.size());
        assertEquals("n1", r.get(0).getSourceTerm());
        assertEquals("n2", r.get(1).getSourceTerm());
        assertEquals("e2-edited", r.get(1).getTargetTerm());
        assertEquals("n3", r.get(2).getSourceTerm());
    }

    /**
     * 10.3: when the write fails, the entries' entryOrdinal / entryId must stay at their
     * previous values - memory must not diverge from an unchanged file. A read-only target
     * makes the atomic move fail.
     */
    @Test
    void saveTerms_writeFails_claimingInfoUnchanged() throws Exception {
        Path file = tempDir.resolve("write_fail.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"e1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>S1</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>t1</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>S2</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>t2</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));

        String[] beforeId = new String[list.size()];
        int[] beforeOrd = new int[list.size()];
        String[] beforeFp = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            beforeId[i] = list.get(i).getEntryId();
            beforeOrd[i] = list.get(i).getEntryOrdinal();
            beforeFp[i] = list.get(i).getPersistedFingerprint();
        }

        java.io.File f = file.toFile();
        boolean originalWritable = f.canWrite();
        f.setWritable(false);
        try {
            list.get(0).setTargetTerm("boom");
            assertThrows(RuntimeException.class, () -> TbxTermbaseHandler.saveTerms(config, list));
        } finally {
            f.setWritable(originalWritable);
        }

        for (int i = 0; i < list.size(); i++) {
            assertEquals(beforeId[i], list.get(i).getEntryId(), "entryId unchanged after failed write");
            assertEquals(beforeOrd[i], list.get(i).getEntryOrdinal(), "entryOrdinal unchanged after failed write");
            assertEquals(beforeFp[i], list.get(i).getPersistedFingerprint(), "fingerprint unchanged after failed write");
        }
    }

    /** Three loadable no-id entries; each carries a distinctive descrip inside its source tig. */
    private TermbaseConfig writeThreeNoIdEntries(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>A</term><descrip>DEF-A</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>a-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>B</term><descrip>DEF-B</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>b-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>C</term><descrip>DEF-C</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>c-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        return new TermbaseConfig(file.toString(), Format.TBX, true);
    }

    /**
     * 12.1 repro: the panel keeps the full pre-delete list as an undo snapshot. Deleting A
     * saves [B, C] (refreshing only those two entries' ordinals); undo then saves the whole
     * snapshot, in which A still carries its load-time ordinal 0 - now B's position. Bug: A
     * claimed B's node (DEF-B), B fell through to a fresh node and DEF-B ended up under A.
     */
    @Test
    void saveTerms_undoSnapshotAfterDelete_noIdEntries_descripsStayWithTheirEntries() throws Exception {
        TermbaseConfig config = writeThreeNoIdEntries("undo_snapshot.tbx");
        List<TermEntry> all = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        List<TermEntry> snapshot = new java.util.ArrayList<>(all);   // what undoDelete holds on to

        List<TermEntry> afterDelete = new java.util.ArrayList<>(all);
        afterDelete.remove(0);                                        // user deletes A
        TbxTermbaseHandler.saveTerms(config, afterDelete);
        TbxTermbaseHandler.saveTerms(config, snapshot);               // undo: whole snapshot

        java.util.Map<String, String> id = new java.util.HashMap<>();
        java.util.Map<String, String> desc = new java.util.HashMap<>();
        readNodeMarkers(Path.of(config.getFilePath()), id, desc);
        assertEquals(3, id.size(), "A is back, B and C are still there");
        assertEquals("DEF-B", desc.get("B"), "B must keep its own description");
        assertEquals("DEF-C", desc.get("C"), "C must keep its own description");
        assertEquals("", desc.get("A"), "A is re-created as a plain node (its old content went with the delete)");
    }

    /**
     * 12.1: two no-id nodes share a source term but have different targets and descrips. The
     * first one is deleted and restored from the snapshot. The restored entry's stale ordinal
     * points at the second node, whose source text matches - the target must tell them apart.
     */
    @Test
    void saveTerms_undoSnapshot_duplicateSourceNodes_keepOwnDescrips() throws Exception {
        Path file = tempDir.resolve("undo_dup_source.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>X</term><descrip>DEF-1</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>first</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>X</term><descrip>DEF-2</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>second</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        List<TermEntry> all = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        List<TermEntry> snapshot = new java.util.ArrayList<>(all);
        List<TermEntry> afterDelete = new java.util.ArrayList<>(all);
        afterDelete.remove(0);
        TbxTermbaseHandler.saveTerms(config, afterDelete);
        TbxTermbaseHandler.saveTerms(config, snapshot);

        // Read each node's target text next to its descrip: "second" must still own DEF-2.
        javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
            .newInstance().newDocumentBuilder();
        org.w3c.dom.NodeList nodes = db.parse(file.toFile()).getElementsByTagName("termEntry");
        java.util.Map<String, String> targetToDescrip = new java.util.HashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element te = (org.w3c.dom.Element) nodes.item(i);
            org.w3c.dom.NodeList terms = te.getElementsByTagName("term");
            org.w3c.dom.NodeList d = te.getElementsByTagName("descrip");
            targetToDescrip.put(terms.item(1).getTextContent().trim(),
                d.getLength() > 0 ? d.item(0).getTextContent().trim() : "");
        }
        assertEquals(2, nodes.getLength());
        assertEquals("DEF-2", targetToDescrip.get("second"), "the surviving node keeps its description");
        assertEquals("", targetToDescrip.get("first"), "the restored entry is a plain node");
    }

    /** 12.2: a successful save refreshes the fingerprint to what is now on disk. */
    @Test
    void saveTerms_refreshesPersistedFingerprint_toWhatWasWritten() throws Exception {
        TermbaseConfig config = writeThreeNoIdEntries("fingerprint_refresh.tbx");
        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        String loaded = list.get(1).getPersistedFingerprint();
        assertEquals("B\u0000b-en", loaded, "load records the fingerprint as source\\u0000target");

        list.get(1).setTargetTerm("b-edited");
        assertEquals(loaded, list.get(1).getPersistedFingerprint(), "an edit alone does not touch it");
        TbxTermbaseHandler.saveTerms(config, list);
        assertEquals("B\u0000b-edited", list.get(1).getPersistedFingerprint(),
            "the save rewrites it to the text that reached disk, source + edited target");

        // A brand-new entry has no fingerprint until it is written.
        TermEntry added = new TermEntry("D", "d-en");
        assertNull(added.getPersistedFingerprint());
        list.add(added);
        TbxTermbaseHandler.saveTerms(config, list);
        assertEquals("D\u0000d-en", added.getPersistedFingerprint(),
            "a freshly written entry gets the fingerprint of the node just saved");
        assertEquals(added.getPersistedFingerprint(), added.copy().getPersistedFingerprint(),
            "copy() carries the fingerprint");
    }

    /**
     * 13: the panel's delete/undo flow on a TBX file, with the same list-reuse the registry
     * does: delete B, add a term, then undo by re-inserting only B. The term added after the
     * delete must still be there, A and C keep their descrips, and B returns as a plain node.
     */
    @Test
    void deleteThenAddThenUndo_reinsertOnly_keepsLaterAdditionAndNeighbours() throws Exception {
        TermbaseConfig config = writeThreeNoIdEntries("undo_reinsert.tbx");
        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));

        List<com.example.termmgmt.util.TermEntryUtils.RemovedEntry> removed =
            com.example.termmgmt.util.TermEntryUtils.removeEntriesRecording(list, List.of(list.get(1)));
        TbxTermbaseHandler.saveTerms(config, list);                 // delete saved

        list.add(new TermEntry("D", "d-en"));                       // user adds a term afterwards
        TbxTermbaseHandler.saveTerms(config, list);

        com.example.termmgmt.util.TermEntryUtils.reinsertRemoved(list, removed);
        TbxTermbaseHandler.saveTerms(config, list);                 // undo saved

        java.util.Map<String, String> id = new java.util.HashMap<>();
        java.util.Map<String, String> desc = new java.util.HashMap<>();
        readNodeMarkers(Path.of(config.getFilePath()), id, desc);
        assertEquals(4, id.size(), "A, B (restored), C and the later D");
        assertEquals("DEF-A", desc.get("A"));
        assertEquals("DEF-C", desc.get("C"));
        assertEquals("DEF-B", desc.get("B"), "phase C: B comes back with its descrip, not as a plain node");
        assertTrue(id.containsKey("D"), "the term added after the delete must survive the undo");

        // And the file reloads to the expected order: A, B (back in place), C, D.
        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(List.of("A", "B", "C", "D"),
            reloaded.stream().map(TermEntry::getSourceTerm).collect(java.util.stream.Collectors.toList()),
            "phase C: the restored node is placed after its list neighbour, not appended");
    }

    /**
     * 14 (建议): the contract for a restored TBX entry when the restore stash no longer has the
     * deleted node (evicted, or the plugin was restarted). Undo carries no claim on the deleted
     * node, so it is written as a brand-new node: appended after the surviving entries, given a
     * fresh id (the deleted node's id is not reused), and without the unmodelled content
     * (descrip/note), which was only recoverable from the stash. With the stash present the whole
     * node comes back instead: see TbxFullRestoreTest.
     */
    @Test
    void undoRestoredTbxEntry_withoutStash_isANewNodeAppendedWithAFreshId() throws Exception {
        Path file = tempDir.resolve("undo_freshid.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"keep1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>A</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>a-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "    <termEntry id=\"gone\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>B</term><descrip>DEF-B</descrip></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>b-en</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);

        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        List<com.example.termmgmt.util.TermEntryUtils.RemovedEntry> removed =
            com.example.termmgmt.util.TermEntryUtils.removeEntriesRecording(list, List.of(list.get(1)));
        TbxTermbaseHandler.saveTerms(config, list);                 // delete B (id "gone")
        TbxTermbaseHandler.clearRestoreStash();                     // the stash no longer has it

        com.example.termmgmt.util.TermEntryUtils.reinsertRemoved(list, removed);
        TbxTermbaseHandler.saveTerms(config, list);                 // undo B

        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(config);
        assertEquals(List.of("A", "B"),
            reloaded.stream().map(TermEntry::getSourceTerm).collect(java.util.stream.Collectors.toList()),
            "the surviving entry stays first; the restored one is appended at the end");
        TermEntry restoredB = reloaded.get(1);
        assertEquals("keep1", reloaded.get(0).getEntryId(), "the surviving entry keeps its original id");
        assertNotEquals("gone", restoredB.getEntryId(), "the deleted id is not reused for the new node");
        assertFalse(Files.readString(file).contains("DEF-B"),
            "the restored node does not carry the deleted node's unmodelled descrip");
    }

    /**
     * 10.3: random add / delete / edit sequence on one reused list (fixed seed), saving after
     * every step without ever reloading. Invariants checked each step:
     * - each surviving original entry's node keeps its own <descrip> marker (no drift, no loss);
     * - both non-loadable nodes always survive;
     * - total termEntry count == list size + 2 non-loadable;
     * - a node's id never changes once an entry has been saved with it.
     */
    @Test
    void saveTerms_randomSequence_reusedList_invariantsHold() throws Exception {
        Path file = tempDir.resolve("random_seq.tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry id=\"i0\"><langSet xml:lang=\"zh-CN\"><tig><term>S0</term><descrip>D0</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S0-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><tig><term>S1</term><descrip>D1</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S1-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry id=\"orphan1\"><langSet xml:lang=\"zh-CN\"><descrip>ORPHAN-1</descrip></langSet></termEntry>\n"
            + "    <termEntry id=\"i3\"><langSet xml:lang=\"zh-CN\"><tig><term>S3</term><descrip>D3</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S3-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><tig><term>S4</term><descrip>D4</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S4-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><descrip>ORPHAN-2</descrip></langSet></termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        assertEquals(4, list.size(), "two orphans are not loadable");

        // Track original entries by object identity: expected source text and expected descrip.
        java.util.Map<TermEntry, String> marker = new java.util.IdentityHashMap<>();
        java.util.Map<TermEntry, String> expectedSource = new java.util.IdentityHashMap<>();
        java.util.Map<TermEntry, String> lastId = new java.util.IdentityHashMap<>();
        String[] origSrc = { "S0", "S1", "S3", "S4" };
        String[] origDesc = { "D0", "D1", "D3", "D4" };
        for (int i = 0; i < list.size(); i++) {
            marker.put(list.get(i), origDesc[i]);
            expectedSource.put(list.get(i), origSrc[i]);
        }

        java.util.Random rnd = new java.util.Random(20261002L);
        int counter = 0;
        for (int step = 0; step < 40; step++) {
            int op = rnd.nextInt(4);
            counter++;
            switch (op) {
                case 0: { // ADD
                    TermEntry e = new TermEntry("A" + counter, "At" + counter);
                    list.add(e);
                    break;
                }
                case 1: { // DELETE
                    if (!list.isEmpty()) {
                        TermEntry e = list.remove(rnd.nextInt(list.size()));
                        marker.remove(e);
                        expectedSource.remove(e);
                        lastId.remove(e);
                    }
                    break;
                }
                case 2: { // EDIT_TARGET
                    if (!list.isEmpty()) list.get(rnd.nextInt(list.size())).setTargetTerm("T" + counter);
                    break;
                }
                default: { // EDIT_SOURCE
                    if (!list.isEmpty()) {
                        TermEntry e = list.get(rnd.nextInt(list.size()));
                        String ns = "E" + counter;
                        e.setSourceTerm(ns);
                        if (marker.containsKey(e)) expectedSource.put(e, ns);
                    }
                    break;
                }
            }

            TbxTermbaseHandler.saveTerms(config, list);

            java.util.Map<String, String> id = new java.util.HashMap<>();
            java.util.Map<String, String> desc = new java.util.HashMap<>();
            int total = inspectNodes(file, id, desc);
            String raw = Files.readString(file);
            // (B) non-loadable nodes survive
            assertTrue(raw.contains("ORPHAN-1"), "orphan 1 lost at step " + step);
            assertTrue(raw.contains("ORPHAN-2"), "orphan 2 lost at step " + step);
            // (C) count
            assertEquals(list.size() + 2, total, "node count wrong at step " + step);
            // (A) markers + (D) id stability, for every tracked original entry still in the list
            for (TermEntry e : list) {
                if (!marker.containsKey(e)) continue;
                String src = expectedSource.get(e);
                assertTrue(desc.containsKey(src), "entry lost at step " + step + " (source " + src + ")");
                assertEquals(marker.get(e), desc.get(src), "descrip drifted at step " + step);
                String nowId = id.get(src);
                String prev = lastId.get(e);
                if (prev == null) lastId.put(e, nowId);
                else assertEquals(prev, nowId, "id changed at step " + step);
            }
        }
    }

    /**
     * 12.3: same random walk as above, plus an operation that deletes an entry, saves, and then
     * restores the whole pre-delete snapshot (what the panel's undo did). Surviving entries must
     * never swap or lose their content; the restored entry comes back as a plain node.
     */
    @Test
    void saveTerms_randomSequenceWithUndoRestore_invariantsHold() throws Exception {
        for (long seed = 1; seed <= 12; seed++) {
            walkWithUndoRestore(seed);
        }
    }

    private void walkWithUndoRestore(long seed) throws Exception {
        Path file = tempDir.resolve("random_seq_undo_" + seed + ".tbx");
        Files.writeString(file,
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><tig><term>S0</term><descrip>D0</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S0-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><tig><term>S1</term><descrip>D1</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S1-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry id=\"orphan1\"><langSet xml:lang=\"zh-CN\"><descrip>ORPHAN-1</descrip></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><tig><term>S3</term><descrip>D3</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S3-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><tig><term>S4</term><descrip>D4</descrip></tig></langSet>"
            + "<langSet xml:lang=\"en-US\"><tig><term>S4-en</term></tig></langSet></termEntry>\n"
            + "    <termEntry><langSet xml:lang=\"zh-CN\"><descrip>ORPHAN-2</descrip></langSet></termEntry>\n"
            + "  </body>\n</martif>");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.TBX, true);
        List<TermEntry> list = new java.util.ArrayList<>(TbxTermbaseHandler.loadTerms(config));
        assertEquals(4, list.size(), "two orphans are not loadable");

        // Track original entries by object identity: expected source text and expected descrip.
        java.util.Map<TermEntry, String> marker = new java.util.IdentityHashMap<>();
        java.util.Map<TermEntry, String> expectedSource = new java.util.IdentityHashMap<>();
        java.util.Map<TermEntry, String> lastId = new java.util.IdentityHashMap<>();
        String[] origSrc = { "S0", "S1", "S3", "S4" };
        String[] origDesc = { "D0", "D1", "D3", "D4" };
        for (int i = 0; i < list.size(); i++) {
            marker.put(list.get(i), origDesc[i]);
            expectedSource.put(list.get(i), origSrc[i]);
        }

        java.util.Random rnd = new java.util.Random(seed);
        int counter = 0;
        for (int step = 0; step < 40; step++) {
            int op = rnd.nextInt(5);
            counter++;
            switch (op) {
                case 0: { // ADD
                    TermEntry e = new TermEntry("A" + counter, "At" + counter);
                    list.add(e);
                    break;
                }
                case 1: { // DELETE
                    if (!list.isEmpty()) {
                        TermEntry e = list.remove(rnd.nextInt(list.size()));
                        marker.remove(e);
                        expectedSource.remove(e);
                        lastId.remove(e);
                    }
                    break;
                }
                case 2: { // EDIT_TARGET
                    if (!list.isEmpty()) list.get(rnd.nextInt(list.size())).setTargetTerm("T" + counter);
                    break;
                }
                case 4: { // DELETE then UNDO: restore the whole pre-delete snapshot, as the panel did
                    if (!list.isEmpty()) {
                        List<TermEntry> snapshot = new java.util.ArrayList<>(list);
                        TermEntry gone = list.remove(rnd.nextInt(list.size()));
                        TbxTermbaseHandler.saveTerms(config, list);   // the delete is saved
                        list.clear();
                        list.addAll(snapshot);                        // undo puts the snapshot back
                        // The deleted node's own content went with the delete; the entry returns
                        // as a plain node under a new id.
                        if (marker.containsKey(gone)) { marker.put(gone, ""); lastId.remove(gone); }
                    }
                    break;
                }
                default: { // EDIT_SOURCE
                    if (!list.isEmpty()) {
                        TermEntry e = list.get(rnd.nextInt(list.size()));
                        String ns = "E" + counter;
                        e.setSourceTerm(ns);
                        if (marker.containsKey(e)) expectedSource.put(e, ns);
                    }
                    break;
                }
            }

            TbxTermbaseHandler.saveTerms(config, list);

            java.util.Map<String, String> id = new java.util.HashMap<>();
            java.util.Map<String, String> desc = new java.util.HashMap<>();
            int total = inspectNodes(file, id, desc);
            String raw = Files.readString(file);
            // (B) non-loadable nodes survive
            assertTrue(raw.contains("ORPHAN-1"), "orphan 1 lost at step " + step);
            assertTrue(raw.contains("ORPHAN-2"), "orphan 2 lost at step " + step);
            // (C) count
            assertEquals(list.size() + 2, total, "node count wrong at step " + step);
            // (A) markers + (D) id stability, for every tracked original entry still in the list
            for (TermEntry e : list) {
                if (!marker.containsKey(e)) continue;
                String src = expectedSource.get(e);
                assertTrue(desc.containsKey(src), "entry lost at step " + step + " (source " + src + ")");
                assertEquals(marker.get(e), desc.get(src), "descrip drifted at step " + step);
                String nowId = id.get(src);
                String prev = lastId.get(e);
                if (prev == null) lastId.put(e, nowId);
                else assertEquals(prev, nowId, "id changed at step " + step);
            }
        }
    }

    /** Like readNodeMarkers but also returns the total termEntry node count (incl. non-loadable). */
    private static int inspectNodes(Path file, java.util.Map<String, String> sourceToId,
            java.util.Map<String, String> sourceToDescrip) throws Exception {
        javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
            .newInstance().newDocumentBuilder();
        org.w3c.dom.Document doc = db.parse(file.toFile());
        org.w3c.dom.NodeList nodes = doc.getElementsByTagName("termEntry");
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Element te = (org.w3c.dom.Element) nodes.item(i);
            org.w3c.dom.NodeList lsList = te.getElementsByTagName("langSet");
            String source = null;
            for (int j = 0; j < lsList.getLength(); j++) {
                org.w3c.dom.NodeList terms = ((org.w3c.dom.Element) lsList.item(j)).getElementsByTagName("term");
                if (terms.getLength() > 0) {
                    String t = terms.item(0).getTextContent();
                    if (t != null && !t.trim().isEmpty()) { source = t.trim(); break; }
                }
            }
            if (source == null) continue;
            sourceToId.put(source, te.getAttribute("id"));
            org.w3c.dom.NodeList d = te.getElementsByTagName("descrip");
            sourceToDescrip.put(source, d.getLength() > 0 ? d.item(0).getTextContent().trim() : "");
        }
        return nodes.getLength();
    }
}

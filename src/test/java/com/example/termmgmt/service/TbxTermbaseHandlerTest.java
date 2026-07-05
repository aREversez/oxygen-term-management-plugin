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
}

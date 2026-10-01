package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CsvTermbaseHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_shouldPreserveTerms() {
        Path file = tempDir.resolve("test.csv");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");

        List<TermEntry> toSave = List.of(
            new TermEntry("你好", "hello"),
            new TermEntry("世界", "world")
        );
        CsvTermbaseHandler.saveTerms(config, toSave);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
        assertEquals("世界", loaded.get(1).getSourceTerm());
        assertEquals("world", loaded.get(1).getTargetTerm());
    }

    @Test
    void loadTerms_shouldSkipUtf8Bom() throws Exception {
        Path file = tempDir.resolve("bom.csv");
        String content = "\uFEFFzh-cn,en-us\n你好,hello\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
    }

    @Test
    void loadTerms_shouldReturnEmptyListForHeaderOnly() throws Exception {
        Path file = tempDir.resolve("empty.csv");
        Files.writeString(file, "zh-cn,en-us\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadTerms_shouldHandleShortRowGracefully() throws Exception {
        Path file = tempDir.resolve("short.csv");
        Files.writeString(file, "zh-cn,en-us\n你好\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertNull(loaded.get(0).getTargetTerm());
    }

    @Test
    void loadTerms_shouldThrowWhenFileNotExists() {
        TermbaseConfig config = new TermbaseConfig(
            tempDir.resolve("nonexistent.csv").toString(), Format.CSV, true);
        assertThrows(RuntimeException.class, () -> CsvTermbaseHandler.loadTerms(config));
    }

    @Test
    void loadTerms_shouldSkipBlankRowsButKeepPartialOnes() throws Exception {
        Path file = tempDir.resolve("blank_rows.csv");
        Files.writeString(file, "zh-cn,en-us\n你好,hello\n\n , \n,orphan\n谢谢,thanks\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);

        assertEquals(3, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("orphan", loaded.get(1).getTargetTerm());
        assertEquals("谢谢", loaded.get(2).getSourceTerm());
    }

    @Test
    void loadTerms_shouldReadExtraColumnsIntoConfigAndEntries() throws Exception {
        Path file = tempDir.resolve("extra.csv");
        Files.writeString(file, "zh-cn,en-us,domain,note\n刚度,stiffness,mechanics,NBR note\n网格,mesh\n");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);

        assertEquals(List.of("domain", "note"), config.getExtraColumns());
        assertEquals(2, loaded.size());
        assertEquals(Map.of("domain", "mechanics", "note", "NBR note"), loaded.get(0).getExtraFields());
        assertEquals(List.of("domain", "note"), List.copyOf(loaded.get(0).getExtraFields().keySet()));
        // Row shorter than the header: the missing extra cells come back as empty strings.
        assertEquals(Map.of("domain", "", "note", ""), loaded.get(1).getExtraFields());
    }

    @Test
    void saveAndLoad_shouldPreserveExtraColumnsAndValues() {
        Path file = tempDir.resolve("roundtrip.csv");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        config.setExtraColumns(List.of("domain", "note"));

        TermEntry withExtras = new TermEntry("刚度", "stiffness");
        withExtras.getExtraFields().put("domain", "mechanics");
        withExtras.getExtraFields().put("note", "NBR term");
        TermEntry withoutExtras = new TermEntry("网格", "mesh");
        CsvTermbaseHandler.saveTerms(config, List.of(withExtras, withoutExtras));

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        assertEquals(List.of("domain", "note"), config.getExtraColumns());
        assertEquals("mechanics", loaded.get(0).getExtraFields().get("domain"));
        assertEquals("NBR term", loaded.get(0).getExtraFields().get("note"));
        assertEquals("", loaded.get(1).getExtraFields().get("domain"));
    }

    @Test
    void saveTerms_shouldWriteHeaderFromExtraColumnsAndKeepBom() throws Exception {
        Path file = tempDir.resolve("header.csv");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        config.setExtraColumns(List.of("status"));

        TermEntry entry = new TermEntry("刚度", "stiffness");
        entry.getExtraFields().put("status", "preferred");
        CsvTermbaseHandler.saveTerms(config, List.of(entry));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("\uFEFF"), "UTF-8 BOM must stay");
        // CSVWriter quotes every field by default; strip quotes to pin the column order.
        String normalized = content.substring(1).replace("\r\n", "\n").replace("\"", "").trim();
        assertEquals("zh-cn,en-us,status\n刚度,stiffness,preferred", normalized);
    }

    @Test
    void saveTerms_withTwoColumnConfig_writesNoExtraHeader() {
        // Old files without extra columns must not gain one.
        Path file = tempDir.resolve("plain.csv");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.CSV, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");

        CsvTermbaseHandler.saveTerms(config, List.of(new TermEntry("a", "b")));

        List<TermEntry> loaded = CsvTermbaseHandler.loadTerms(config);
        assertTrue(config.getExtraColumns().isEmpty());
        assertTrue(loaded.get(0).getExtraFields().isEmpty());
    }
}

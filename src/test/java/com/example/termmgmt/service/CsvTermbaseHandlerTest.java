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
}

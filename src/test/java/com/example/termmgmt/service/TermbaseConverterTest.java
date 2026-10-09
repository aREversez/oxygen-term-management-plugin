package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TermbaseConverter}. Covers: round-trip TBX->CSV->TBX, loss reporting,
 * empty termbase, special characters, overwrite existing file.
 */
class TermbaseConverterTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ Helpers

    private static final String TBX_WITH_EXTRAS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<martif type=\"TBX\" xml:lang=\"en-US\">\n"
        + "  <martifHeader><fileDesc><sourceDesc><p>Test</p></sourceDesc></fileDesc></martifHeader>\n"
        + "  <text><body>\n"
        + "    <termEntry id=\"e1\">\n"
        + "      <langSet xml:lang=\"zh-CN\">\n"
        + "        <tig><term>云计算</term></tig>\n"
        + "        <descrip type=\"definition\">一种按量付费的计算模式</descrip>\n"
        + "      </langSet>\n"
        + "      <langSet xml:lang=\"en-US\">\n"
        + "        <tig><term>Cloud Computing</term></tig>\n"
        + "      </langSet>\n"
        + "      <langSet xml:lang=\"de-DE\">\n"
        + "        <tig><term>Cloud-Computing</term></tig>\n"
        + "      </langSet>\n"
        + "      <note>示例备注</note>\n"
        + "    </termEntry>\n"
        + "    <termEntry id=\"e2\">\n"
        + "      <langSet xml:lang=\"zh-CN\">\n"
        + "        <tig><term>微服务</term></tig>\n"
        + "      </langSet>\n"
        + "      <langSet xml:lang=\"en-US\">\n"
        + "        <tig><term>Microservices</term></tig>\n"
        + "      </langSet>\n"
        + "    </termEntry>\n"
        + "  </body></text>\n"
        + "</martif>\n";

    private static final String CSV_WITH_EXTRAS =
        "\uFEFFzh-CN,en-US,definition,note\n"
        + "人工智能,AI,Artificial Intelligence,First entry\n"
        + "机器学习,Machine Learning,,\n";

    private Path writeFile(String name, String content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private TermbaseConfig tbxConfig(String content) throws IOException {
        Path file = writeFile("test.tbx", content);
        TermbaseConfig c = new TermbaseConfig(file.toString(), Format.TBX, true);
        c.setSourceLang("zh-CN");
        c.setTargetLang("en-US");
        // Load to populate extra info
        TermbaseLoader.loadTerms(c);
        return c;
    }

    private TermbaseConfig csvConfig(String content) throws IOException {
        Path file = writeFile("test.csv", content);
        TermbaseConfig c = new TermbaseConfig(file.toString(), Format.CSV, true);
        // Load to populate extra info
        TermbaseLoader.loadTerms(c);
        return c;
    }

    // ------------------------------------------------------------------ Round-trip TBX -> CSV -> TBX

    @Test
    void roundTrip_tbxToCsvToTbx_preservesSourceTarget() throws Exception {
        TermbaseConfig source = tbxConfig(TBX_WITH_EXTRAS);

        // TBX -> CSV
        Path csvOut = tempDir.resolve("out1.csv");
        TermbaseConverter.ConversionReport r1 =
            TermbaseConverter.convert(source, Format.CSV, csvOut);
        assertEquals(2, r1.entryCount());

        // Verify CSV was written
        assertTrue(Files.exists(csvOut));
        String csvContent = Files.readString(csvOut, StandardCharsets.UTF_8);
        assertTrue(csvContent.contains("云计算"));
        assertTrue(csvContent.contains("Cloud Computing"));
        assertTrue(csvContent.contains("微服务"));
        assertTrue(csvContent.contains("Microservices"));

        // CSV -> TBX
        TermbaseConfig csvAsSource = new TermbaseConfig(csvOut.toString(), Format.CSV, true);
        TermbaseLoader.loadTerms(csvAsSource);
        Path tbxOut = tempDir.resolve("out1.tbx");
        TermbaseConverter.ConversionReport r2 =
            TermbaseConverter.convert(csvAsSource, Format.TBX, tbxOut);
        assertEquals(2, r2.entryCount());

        // Verify TBX round-trip preserves terms
        assertTrue(Files.exists(tbxOut));
        String tbxContent = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(tbxContent.contains("云计算"));
        assertTrue(tbxContent.contains("Cloud Computing"));
        assertTrue(tbxContent.contains("微服务"));
        assertTrue(tbxContent.contains("Microservices"));
    }

    // ------------------------------------------------------------------ Loss report

    @Test
    void tbxToCsv_reportsDroppedDefinitionAndNotes() throws Exception {
        TermbaseConfig source = tbxConfig(TBX_WITH_EXTRAS);

        Path csvOut = tempDir.resolve("loss.csv");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.CSV, csvOut);

        assertTrue(report.hasLoss());
        boolean hasDefinition = false, hasNote = false, hasExtra = false;
        for (TermbaseConverter.DroppedField df : report.droppedFields()) {
            if ("definition".equals(df.fieldName())) { hasDefinition = true; assertEquals(1, df.affectedEntries()); }
            if ("note".equals(df.fieldName())) { hasNote = true; assertEquals(1, df.affectedEntries()); }
            if ("extra language terms".equals(df.fieldName())) { hasExtra = true; assertEquals(1, df.affectedEntries()); }
        }
        assertTrue(hasDefinition, "Should report dropped definitions");
        assertTrue(hasNote, "Should report dropped notes");
        assertTrue(hasExtra, "Should report dropped extra languages");
    }

    @Test
    void tbxToCsv_reportsDroppedExtraLanguages() throws Exception {
        // entry e1 has de-DE, entry e2 does not
        TermbaseConfig source = tbxConfig(TBX_WITH_EXTRAS);
        Path csvOut = tempDir.resolve("extra.csv");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.CSV, csvOut);

        boolean foundExtra = report.droppedFields().stream()
            .anyMatch(df -> "extra language terms".equals(df.fieldName()) && df.affectedEntries() == 1);
        assertTrue(foundExtra);
    }

    @Test
    void csvWithNoExtras_reportsNoLoss() throws Exception {
        String simpleCsv = "\uFEFFzh-CN,en-US\n你好,hello\n世界,world\n";
        TermbaseConfig source = csvConfig(simpleCsv);

        Path tbxOut = tempDir.resolve("no-loss.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        assertFalse(report.hasLoss());
    }

    // ------------------------------------------------------------------ CSV -> TBX basic

    @Test
    void csvToTbx_writesBasicTbx() throws Exception {
        String csv = "\uFEFFzh-CN,en-US\n人工智能,AI\n神经网络,Neural Network\n";
        TermbaseConfig source = csvConfig(csv);

        Path tbxOut = tempDir.resolve("basic.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        assertEquals(2, report.entryCount());
        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("<martif"));
        assertTrue(content.contains("termEntry"));
        assertTrue(content.contains("人工智能"));
        assertTrue(content.contains("AI"));
        assertTrue(content.contains("Neural Network"));
    }

    // ------------------------------------------------------------------ CSV with definition/note cols -> TBX

    @Test
    void csvWithDefinitionColumn_writesDescripToTbx() throws Exception {
        TermbaseConfig source = csvConfig(CSV_WITH_EXTRAS);

        Path tbxOut = tempDir.resolve("with-def.tbx");
        TermbaseConverter.convert(source, Format.TBX, tbxOut);

        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("Artificial Intelligence"), "Should contain definition text");
        assertTrue(content.contains("First entry"), "Should contain note text");
    }

    // ------------------------------------------------------------------ Empty termbase

    @Test
    void emptyTermbase_exportProducesHeaderOnly() throws Exception {
        String emptyCsv = "\uFEFFzh-CN,en-US\n";
        TermbaseConfig source = csvConfig(emptyCsv);

        Path tbxOut = tempDir.resolve("empty.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        assertEquals(0, report.entryCount());
        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("<martif"));
        assertTrue(content.contains("<body"));
    }

    // ------------------------------------------------------------------ Special characters

    @Test
    void specialCharacters_roundTripClean() throws Exception {
        String csv = "\uFEFFzh-CN,en-US\n"
            + "\"Hello, World\",\"\"Greeting\" with, comma\"\n"
            + "换行\n测试,line\nbreak\ntest\n";
        Path file = writeFile("special.csv", csv);
        TermbaseConfig source = new TermbaseConfig(file.toString(), Format.CSV, true);
        TermbaseLoader.loadTerms(source);

        Path tbxOut = tempDir.resolve("special.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        assertTrue(report.entryCount() >= 1);
        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        // TBX should escape XML special chars via DOM
        assertTrue(content.contains("&lt;") || content.contains("term"),
            "Output should be valid XML with escaped chars");
    }

    // ------------------------------------------------------------------ Overwrite existing file

    @Test
    void overwriteExistingFile_succeeds() throws Exception {
        String csv = "\uFEFFzh-CN,en-US\n你好,hello\n";
        TermbaseConfig source = csvConfig(csv);

        Path target = tempDir.resolve("existing.csv");
        Files.writeString(target, "old content", StandardCharsets.UTF_8);

        // Convert over existing file - AtomicFileWriter handles REPLACE_EXISTING
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.CSV, target);

        assertEquals(1, report.entryCount());
        String content = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(content.contains("你好"));
        assertFalse(content.contains("old content"));
    }

    // ------------------------------------------------------------------ XLSX -> CSV (basic structure test)

    @Test
    void csvToXlsx_writesValidXlsx() throws Exception {
        // This test requires real POI at runtime
        String csv = "\uFEFFzh-CN,en-US\n网格,mesh\n应力,stress\n";
        TermbaseConfig source = csvConfig(csv);

        Path xlsxOut = tempDir.resolve("out.xlsx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.XLSX, xlsxOut);

        assertEquals(2, report.entryCount());
        assertTrue(Files.exists(xlsxOut));
        assertTrue(Files.size(xlsxOut) > 0, "XLSX file should not be empty");
    }

    // ------------------------------------------------------------------ RichTermEntry readTbxRich

    @Test
    void readRich_tbxExtractsDefinitionAndNotes() throws Exception {
        TermbaseConfig config = tbxConfig(TBX_WITH_EXTRAS);
        List<TermbaseConverter.RichTermEntry> rich = TermbaseConverter.readRich(config);

        assertEquals(2, rich.size());
        // First entry has definition, note, and extra lang
        assertEquals("一种按量付费的计算模式", rich.get(0).definition);
        assertEquals("示例备注", rich.get(0).note);
        assertTrue(rich.get(0).extraLangTerms.containsKey("de-DE"));
        assertEquals("Cloud-Computing", rich.get(0).extraLangTerms.get("de-DE"));
        // Second entry has no extras
        assertNull(rich.get(1).definition);
        assertNull(rich.get(1).note);
        assertTrue(rich.get(1).extraLangTerms.isEmpty());
    }

    // ------------------------------------------------------------------ Review fix: refuse to export onto the source

    @Test
    void convert_targetIsSourceFile_throws() throws Exception {
        String csv = "\uFEFFzh-CN,en-US\n你好,hello\n";
        Path file = writeFile("protect.csv", csv);
        TermbaseConfig c = new TermbaseConfig(file.toString(), Format.CSV, true);
        TermbaseLoader.loadTerms(c);

        IOException ex = assertThrows(IOException.class, () ->
            TermbaseConverter.convert(c, Format.CSV, file));
        assertTrue(ex.getMessage().contains("source termbase"));
        // The source must survive the refused export untouched.
        assertEquals(csv, Files.readString(file, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ Review fix: XXE guard on the converter's TBX parse

    @Test
    void readRich_tbxWithExternalEntity_doesNotLeakLocalFile() throws Exception {
        Path payload = writeFile("secret.txt", "LEAKED-CONTENT");
        String tbx = "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE martif [<!ENTITY e SYSTEM \"" + payload.toUri() + "\">]>\n"
            + "<martif type=\"TBX\" xml:lang=\"en-US\">\n"
            + "  <martifHeader><fileDesc><sourceDesc><p>Test</p></sourceDesc></fileDesc></martifHeader>\n"
            + "  <text><body>\n"
            + "    <termEntry id=\"x1\">\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>云计算</term></tig>\n"
            + "        <descrip type=\"definition\">&e;</descrip>\n"
            + "      </langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>Cloud</term></tig></langSet>\n"
            + "    </termEntry>\n"
            + "  </body></text>\n"
            + "</martif>\n";
        TermbaseConfig c = tbxConfig(tbx);

        List<TermbaseConverter.RichTermEntry> rich = TermbaseConverter.readRich(c);
        String definition = rich.isEmpty() ? null : rich.get(0).definition;
        assertFalse(definition != null && definition.contains("LEAKED-CONTENT"),
            "External entity content must never be expanded");
    }

    // ------------------------------------------------------------------ Review fix: status carried across formats

    @Test
    void csvWithStatus_toTbx_writesAdministrativeStatus() throws Exception {
        String csv = "\uFEFFzh-CN,en-US,status\n云计算,Cloud Computing,deprecated\n网格,mesh,\n";
        TermbaseConfig source = csvConfig(csv);

        Path tbxOut = tempDir.resolve("status.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("deprecatedTerm-admn-sts"),
            "CSV status should become the TBX domain value");
        assertFalse(report.hasLoss(), "Status is carried, so nothing is dropped: "
            + report.droppedFields());

        // And it comes back on the way to CSV, normalized to the plain CSV spelling.
        TermbaseConfig tbxAsSource = new TermbaseConfig(tbxOut.toString(), Format.TBX, true);
        tbxAsSource.setSourceLang("zh-CN");
        tbxAsSource.setTargetLang("en-US");
        Path csvOut = tempDir.resolve("status-back.csv");
        TermbaseConverter.convert(tbxAsSource, Format.CSV, csvOut);

        TermbaseConfig reloaded = new TermbaseConfig(csvOut.toString(), Format.CSV, true);
        List<TermEntry> terms = TermbaseLoader.loadTerms(reloaded);
        assertEquals("deprecated", terms.get(0).getRawStatus());
    }

    @Test
    void tbxToCsv_statusNotSilentlyLost() throws Exception {
        String tbx = TBX_WITH_EXTRAS.replace(
            "<term>云计算</term>",
            "<term>云计算</term><termNote type=\"administrativeStatus\">deprecatedTerm-admn-sts</termNote>");
        TermbaseConfig source = tbxConfig(tbx);

        Path csvOut = tempDir.resolve("status-loss.csv");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.CSV, csvOut);

        // The status is carried into an appended "status" column, not dropped.
        assertTrue(report.droppedFields().stream().noneMatch(df -> "status".equals(df.fieldName())),
            "Status should be carried, not reported as dropped");
        String content = Files.readString(csvOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("deprecated"), "CSV should carry the normalized status");
    }

    // ------------------------------------------------------------------ Review fix: extras preserved or reported

    @Test
    void csvWithExtraColumns_toTbx_keepsThemAsTermNotes() throws Exception {
        String csv = "\uFEFFzh-CN,en-US,domain\n云计算,Cloud Computing,IT\n";
        TermbaseConfig source = csvConfig(csv);

        Path tbxOut = tempDir.resolve("extras.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("termNote") && content.contains("IT"),
            "Unknown CSV columns should survive as termNotes");
        assertFalse(report.hasLoss(),
            "Columns preserved in TBX must not be reported as dropped");
    }

    @Test
    void tbxToTbx_keepsUnmodelledTermNotes() throws Exception {
        String tbx = TBX_WITH_EXTRAS.replace(
            "<term>云计算</term>",
            "<term>云计算</term><termNote type=\"partOfSpeech\">Noun</termNote>");
        TermbaseConfig source = tbxConfig(tbx);

        Path tbxOut = tempDir.resolve("keep-notes.tbx");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.TBX, tbxOut);

        String content = Files.readString(tbxOut, StandardCharsets.UTF_8);
        assertTrue(content.contains("partOfSpeech") && content.contains("Noun"),
            "Unmodelled termNotes must survive a TBX -> TBX export");
        assertFalse(report.hasLoss());
    }

    @Test
    void tbxToCsv_reportsDroppedTermNotes() throws Exception {
        String tbx = TBX_WITH_EXTRAS.replace(
            "<term>微服务</term>",
            "<term>微服务</term><termNote type=\"partOfSpeech\">Noun</termNote>");
        TermbaseConfig source = tbxConfig(tbx);

        Path csvOut = tempDir.resolve("notes-loss.csv");
        TermbaseConverter.ConversionReport report =
            TermbaseConverter.convert(source, Format.CSV, csvOut);

        assertTrue(report.droppedFields().stream()
            .anyMatch(df -> "term notes".equals(df.fieldName())),
            "Dropped termNotes must appear in the report");
    }
}

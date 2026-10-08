package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.service.TermbaseConverter.RichTermEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DitaGlossaryExporter}. Validates XML well-formedness, element structure,
 * id handling, and special character escaping by re-parsing the output with a standard XML parser.
 */
class DitaGlossaryExporterTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ Helpers

    private RichTermEntry rich(String source, String target, String entryId, String definition) {
        TermEntry e = new TermEntry(source, target);
        e.setEntryId(entryId);
        RichTermEntry r = new RichTermEntry(e);
        r.definition = definition;
        return r;
    }

    private Document parseOutput(Path file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        // Don't load external DTD (file won't have it locally)
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(file.toFile());
    }

    // ------------------------------------------------------------------ Well-formedness

    @Test
    void export_producesWellFormedXml() throws Exception {
        List<RichTermEntry> entries = List.of(
            rich("云计算", "Cloud Computing", "e1", "定义文本"),
            rich("微服务", "Microservices", "e2", null)
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "zh-CN", "en-US", out);

        // Must parse without exception
        Document doc = parseOutput(out);
        assertNotNull(doc.getDocumentElement());
        assertEquals("glossgroup", doc.getDocumentElement().getTagName());
    }

    // ------------------------------------------------------------------ Element structure

    @Test
    void glossentry_hasId_fromEntryId() throws Exception {
        List<RichTermEntry> entries = List.of(
            rich("术语", "term", "myCustomId", null)
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "zh-CN", "en-US", out);

        Document doc = parseOutput(out);
        NodeList glossentries = doc.getElementsByTagName("glossentry");
        assertEquals(1, glossentries.getLength());
        Element ge = (Element) glossentries.item(0);
        assertEquals("myCustomId", ge.getAttribute("id"));
    }

    @Test
    void glossentry_hasId_generatedWhenNull() throws Exception {
        List<RichTermEntry> entries = List.of(
            rich("A", "a", null, null),
            rich("B", "b", null, null),
            rich("C", "c", null, null)
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "zh-CN", "en-US", out);

        Document doc = parseOutput(out);
        NodeList glossentries = doc.getElementsByTagName("glossentry");
        assertEquals(3, glossentries.getLength());

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < glossentries.getLength(); i++) {
            String id = ((Element) glossentries.item(i)).getAttribute("id");
            assertFalse(id.isEmpty(), "id must not be empty");
            assertTrue(ids.add(id), "ids must be unique, got duplicate: " + id);
        }
    }

    // ------------------------------------------------------------------ glossterm and glossdef

    @Test
    void glossterm_isPresentForEachEntry() throws Exception {
        List<RichTermEntry> entries = List.of(
            rich("hello", null, "h1", null),
            rich("world", null, "h2", null)
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "en-US", "zh-CN", out);

        Document doc = parseOutput(out);
        NodeList terms = doc.getElementsByTagName("glossterm");
        assertEquals(2, terms.getLength());
        assertEquals("hello", terms.item(0).getTextContent());
        assertEquals("world", terms.item(1).getTextContent());
    }

    @Test
    void glossdef_presentWhenDefinitionAvailable() throws Exception {
        List<RichTermEntry> entries = List.of(
            rich("云计算", "Cloud", "e1", "一种计算模式")
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "zh-CN", "en-US", out);

        Document doc = parseOutput(out);
        NodeList defs = doc.getElementsByTagName("glossdef");
        assertEquals(1, defs.getLength());
        assertEquals("一种计算模式", defs.item(0).getTextContent());
    }

    @Test
    void glossdef_absentForCsvEntries() throws Exception {
        // No definition available
        List<RichTermEntry> entries = List.of(
            rich("网格", "mesh", "n1", null)
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "zh-CN", "en-US", out);

        Document doc = parseOutput(out);
        NodeList defs = doc.getElementsByTagName("glossdef");
        assertEquals(0, defs.getLength(), "No glossdef when definition is null");
    }

    // ------------------------------------------------------------------ XML special characters

    @Test
    void xmlSpecialCharacters_escapedCorrectly() throws Exception {
        List<RichTermEntry> entries = List.of(
            rich("A<B>C&D\"E", null, "sc1", "value < 10 && x > 5")
        );
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "en-US", "en-US", out);

        // Must parse (DOM handles escaping automatically)
        Document doc = parseOutput(out);
        NodeList terms = doc.getElementsByTagName("glossterm");
        assertEquals("A<B>C&D\"E", terms.item(0).getTextContent());

        NodeList defs = doc.getElementsByTagName("glossdef");
        assertEquals("value < 10 && x > 5", defs.item(0).getTextContent());
    }

    // ------------------------------------------------------------------ Empty termbase

    @Test
    void emptyTermbase_producesValidEmptyGlossary() throws Exception {
        List<RichTermEntry> entries = List.of();
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "zh-CN", "en-US", out);

        Document doc = parseOutput(out);
        assertEquals("glossgroup", doc.getDocumentElement().getTagName());
        NodeList glossentries = doc.getElementsByTagName("glossentry");
        assertEquals(0, glossentries.getLength());

        // Verify title is present (required by spec)
        NodeList titles = doc.getElementsByTagName("title");
        assertTrue(titles.getLength() >= 1);
    }

    // ------------------------------------------------------------------ DOCTYPE present

    @Test
    void output_containsDoctypeDeclaration() throws Exception {
        List<RichTermEntry> entries = List.of(rich("test", "t", "x1", null));
        Path out = tempDir.resolve("gloss.dita");
        DitaGlossaryExporter.export(entries, "en-US", "en-US", out);

        String content = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(content.contains("<!DOCTYPE glossgroup"), "Should contain DOCTYPE");
        assertTrue(content.contains("-//OASIS//DTD DITA Glossary Group//EN"), "Should contain PUBLIC identifier");
    }

    // ------------------------------------------------------------------ ID validity

    @Test
    void resolveId_rejectsNumericStartId() {
        TermEntry e = new TermEntry("x", "y");
        e.setEntryId("123abc"); // invalid NCName (starts with digit)
        Set<String> used = new HashSet<>();
        String id = DitaGlossaryExporter.resolveId(e, 1, used);
        assertNotEquals("123abc", id, "Invalid id should be replaced");
        assertTrue(id.startsWith("term_"));
    }

    @Test
    void resolveId_rejectsDuplicateId() {
        TermEntry e = new TermEntry("x", "y");
        e.setEntryId("dup");
        Set<String> used = new HashSet<>();
        used.add("dup");
        String id = DitaGlossaryExporter.resolveId(e, 1, used);
        assertNotEquals("dup", id, "Duplicate id should be replaced");
    }
}

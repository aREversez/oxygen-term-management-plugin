package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.AtomicFileWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a termbase from one format (CSV, XLSX, TBX) to another.
 * Reads via existing handlers, writes to a new file without touching the source or registry.
 * Produces a {@link ConversionReport} listing fields that could not be carried over.
 */
public final class TermbaseConverter {

    private TermbaseConverter() {}

    // ------------------------------------------------------------------ Report

    /** Result of a conversion: entry count and which fields were dropped. */
    public record ConversionReport(int entryCount, List<DroppedField> droppedFields) {
        public boolean hasLoss() { return !droppedFields.isEmpty(); }
    }

    /** A field category that the target format could not hold. */
    public record DroppedField(String fieldName, int affectedEntries) {}

    // ------------------------------------------------------------------ Rich model for TBX extras

    /** Extends a TermEntry with TBX-only fields for loss reporting. */
    public static final class RichTermEntry {
        public final TermEntry entry;
        public String definition; // descrip type="definition" from source langSet
        public String note;       // first note element text
        public Map<String, String> extraLangTerms = new LinkedHashMap<>(); // lang -> term for 3rd+ langs

        public RichTermEntry(TermEntry entry) { this.entry = entry; }
    }

    // ------------------------------------------------------------------ Public API

    /**
     * Convert the termbase described by {@code config} into {@code targetFormat} and write
     * the result to {@code targetPath}. Does not modify the source file or registry.
     */
    public static ConversionReport convert(TermbaseConfig config, Format targetFormat,
                                           Path targetPath) throws IOException {
        // Read rich entries
        List<RichTermEntry> richEntries = readRich(config);
        List<TermEntry> entries = new ArrayList<>();
        for (RichTermEntry r : richEntries) entries.add(r.entry);

        // Detect dropped fields
        List<DroppedField> dropped = detectDrops(config, targetFormat, richEntries);

        // Write to target
        TermbaseConfig targetConfig = makeTargetConfig(config, targetFormat, targetPath);
        switch (targetFormat) {
            case CSV -> writeCsv(targetConfig, entries, richEntries);
            case XLSX -> writeXlsx(targetConfig, entries, richEntries);
            case TBX -> writeTbx(targetConfig, entries, richEntries);
        }
        return new ConversionReport(entries.size(), dropped);
    }

    // ------------------------------------------------------------------ Reading

    /**
     * Read entries with full TBX field extraction (definition, note, extra languages).
     * For CSV/XLSX, wraps entries in RichTermEntry with no extras.
     */
    public static List<RichTermEntry> readRich(TermbaseConfig config) {
        List<TermEntry> raw = TermbaseLoader.loadTerms(config);
        List<RichTermEntry> result = new ArrayList<>();
        if (config.getFormat() == Format.TBX) {
            // Parse DOM for extra fields
            Map<String, String[]> extraById = parseTbxExtras(config);
            for (TermEntry e : raw) {
                RichTermEntry r = new RichTermEntry(e);
                String key = e.getEntryId() != null ? e.getEntryId()
                    : "ordinal_" + e.getEntryOrdinal();
                String[] extras = extraById.get(key);
                if (extras != null) {
                    r.definition = extras[0];
                    r.note = extras[1];
                    if (extras.length > 2 && extras[2] != null && !extras[2].isEmpty()) {
                        // extras[2+] encoded as "lang\u0000term" pairs
                        for (int i = 2; i < extras.length; i++) {
                            String pair = extras[i];
                            if (pair == null) continue;
                            int sep = pair.indexOf('\u0000');
                            if (sep > 0) {
                                r.extraLangTerms.put(pair.substring(0, sep), pair.substring(sep + 1));
                            }
                        }
                    }
                }
                result.add(r);
            }
        } else {
            for (TermEntry e : raw) {
                result.add(new RichTermEntry(e));
            }
        }
        return result;
    }

    /**
     * Parse a TBX file's DOM to extract definition, note, and extra language terms per entry.
     * Returns a map from entry id (or "ordinal_N") to String[]{definition, note, extraLangPairs...}.
     */
    private static Map<String, String[]> parseTbxExtras(TermbaseConfig config) {
        Map<String, String[]> result = new LinkedHashMap<>();
        String sourceLang = config.getSourceLang();
        String targetLang = config.getTargetLang();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new File(config.getFilePath()));
            NodeList entries = doc.getElementsByTagName("termEntry");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entryNode = (Element) entries.item(i);
                String id = entryNode.getAttribute("id");
                String key = (id != null && !id.isEmpty()) ? id : "ordinal_" + i;

                // Definition: look in source langSet for descrip type="definition"
                String definition = findDefinition(entryNode, sourceLang);
                // Note: first note element in the termEntry
                String note = findFirstNote(entryNode);
                // Extra languages: langSets beyond source and target
                List<String> extras = new ArrayList<>();
                List<Element> langSets = getLangSets(entryNode);
                for (Element ls : langSets) {
                    String lang = getLang(ls);
                    if (lang == null) continue;
                    if (sourceLang != null && lang.equalsIgnoreCase(sourceLang)) continue;
                    if (targetLang != null && lang.equalsIgnoreCase(targetLang)) continue;
                    String term = getTermTextInLangSet(ls);
                    if (term != null && !term.isEmpty()) {
                        extras.add(lang + "\u0000" + term);
                    }
                }
                String[] arr = new String[2 + extras.size()];
                arr[0] = definition;
                arr[1] = note;
                for (int j = 0; j < extras.size(); j++) arr[2 + j] = extras.get(j);
                result.put(key, arr);
            }
        } catch (Exception e) {
            // If DOM parse fails, return empty (basic loadTerms already succeeded)
        }
        return result;
    }

    private static String findDefinition(Element termEntry, String sourceLang) {
        // Look for descrip type="definition" inside the source langSet
        for (Element ls : getLangSets(termEntry)) {
            String lang = getLang(ls);
            if (sourceLang == null || (lang != null && lang.equalsIgnoreCase(sourceLang))) {
                NodeList children = ls.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                    if ("descrip".equals(child.getNodeName())
                        && "definition".equals(((Element) child).getAttribute("type"))) {
                        String text = child.getTextContent();
                        return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
                    }
                }
            }
        }
        // Also check at termEntry level (some TBX variants)
        NodeList children = termEntry.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if ("descrip".equals(child.getNodeName())
                && "definition".equals(((Element) child).getAttribute("type"))) {
                String text = child.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        return null;
    }

    private static String findFirstNote(Element termEntry) {
        NodeList children = termEntry.getElementsByTagName("note");
        if (children.getLength() > 0) {
            String text = children.item(0).getTextContent();
            return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
        }
        return null;
    }

    private static List<Element> getLangSets(Element termEntry) {
        List<Element> result = new ArrayList<>();
        NodeList children = termEntry.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "langSet".equals(child.getNodeName())) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private static String getLang(Element langSet) {
        Node attr = langSet.getAttributeNode("xml:lang");
        if (attr == null) attr = langSet.getAttributeNode("lang");
        return attr != null ? attr.getNodeValue() : null;
    }

    private static String getTermTextInLangSet(Element langSet) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName();
            if ("tig".equals(name)) {
                Element term = firstChildElement((Element) child, "term");
                if (term != null) return term.getTextContent();
            } else if ("ntig".equals(name)) {
                Element termGrp = firstChildElement((Element) child, "termGrp");
                if (termGrp != null) {
                    Element term = firstChildElement(termGrp, "term");
                    if (term != null) return term.getTextContent();
                }
            }
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ Loss detection

    private static List<DroppedField> detectDrops(TermbaseConfig sourceConfig, Format targetFormat,
                                                  List<RichTermEntry> entries) {
        List<DroppedField> dropped = new ArrayList<>();
        if (targetFormat == Format.CSV || targetFormat == Format.XLSX) {
            // TBX definitions, notes, extra languages cannot be held
            if (sourceConfig.getFormat() == Format.TBX) {
                int defCount = 0, noteCount = 0, extraCount = 0;
                for (RichTermEntry r : entries) {
                    if (r.definition != null && !r.definition.isEmpty()) defCount++;
                    if (r.note != null && !r.note.isEmpty()) noteCount++;
                    if (!r.extraLangTerms.isEmpty()) extraCount++;
                }
                if (defCount > 0) dropped.add(new DroppedField("definition", defCount));
                if (noteCount > 0) dropped.add(new DroppedField("note", noteCount));
                if (extraCount > 0) dropped.add(new DroppedField("extra language terms", extraCount));
            }
        }
        return dropped;
    }

    // ------------------------------------------------------------------ Target config helper

    private static TermbaseConfig makeTargetConfig(TermbaseConfig source, Format targetFormat,
                                                   Path targetPath) {
        TermbaseConfig tc = new TermbaseConfig(targetPath.toString(), targetFormat, true);
        tc.setSourceLang(source.getSourceLang());
        tc.setTargetLang(source.getTargetLang());
        tc.setExtraColumns(new ArrayList<>(source.getExtraColumns()));
        tc.setSelectedLangs(source.getSelectedSourceLang(), source.getSelectedTargetLang());
        tc.setLangColumns(source.getSourceColumn(), source.getTargetColumn());
        tc.setAvailableLangs(new ArrayList<>(source.getAvailableLangs()));
        return tc;
    }

    // ------------------------------------------------------------------ CSV writing

    private static void writeCsv(TermbaseConfig config, List<TermEntry> entries,
                                 List<RichTermEntry> richEntries) throws IOException {
        String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
        String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";
        List<String> extraCols = config.getExtraColumns();

        AtomicFileWriter.write(Path.of(config.getFilePath()), out -> {
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writer.write('\uFEFF');
            var csvWriter = new com.opencsv.CSVWriter(writer);
            // Headers
            String[] headers = new String[2 + extraCols.size()];
            headers[0] = sourceLang;
            headers[1] = targetLang;
            for (int i = 0; i < extraCols.size(); i++) headers[2 + i] = extraCols.get(i);
            csvWriter.writeNext(headers);
            // Rows
            for (int idx = 0; idx < entries.size(); idx++) {
                TermEntry e = entries.get(idx);
                RichTermEntry r = richEntries.get(idx);
                String[] row = new String[headers.length];
                row[0] = e.getSourceTerm() != null ? e.getSourceTerm() : "";
                row[1] = e.getTargetTerm() != null ? e.getTargetTerm() : "";
                for (int i = 0; i < extraCols.size(); i++) {
                    String colName = extraCols.get(i);
                    String val = e.getExtraFields().get(colName);
                    row[2 + i] = val != null ? val : "";
                }
                csvWriter.writeNext(row);
            }
            csvWriter.flush();
        });
    }

    // ------------------------------------------------------------------ XLSX writing

    private static void writeXlsx(TermbaseConfig config, List<TermEntry> entries,
                                  List<RichTermEntry> richEntries) throws IOException {
        String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
        String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";
        List<String> extraCols = config.getExtraColumns();

        try {
            var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
            try {
                var sheet = workbook.createSheet("Terms");
                // Header row
                var headerRow = sheet.createRow(0);
                headerRow.createCell(0).setCellValue(sourceLang);
                headerRow.createCell(1).setCellValue(targetLang);
                for (int i = 0; i < extraCols.size(); i++) {
                    headerRow.createCell(2 + i).setCellValue(extraCols.get(i));
                }
                // Data rows
                for (int idx = 0; idx < entries.size(); idx++) {
                    TermEntry e = entries.get(idx);
                    var row = sheet.createRow(idx + 1);
                    row.createCell(0).setCellValue(e.getSourceTerm() != null ? e.getSourceTerm() : "");
                    row.createCell(1).setCellValue(e.getTargetTerm() != null ? e.getTargetTerm() : "");
                    for (int i = 0; i < extraCols.size(); i++) {
                        String val = e.getExtraFields().get(extraCols.get(i));
                        row.createCell(2 + i).setCellValue(val != null ? val : "");
                    }
                }
                AtomicFileWriter.write(Path.of(config.getFilePath()), out -> workbook.write(out));
            } finally {
                workbook.close();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to write XLSX: " + config.getFilePath(), e);
        }
    }

    // ------------------------------------------------------------------ TBX writing

    private static void writeTbx(TermbaseConfig config, List<TermEntry> entries,
                                 List<RichTermEntry> richEntries) throws IOException {
        String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
        String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.newDocument();

            // Root: martif
            Element martif = doc.createElement("martif");
            martif.setAttribute("type", "TBX");
            martif.setAttribute("xml:lang", sourceLang);
            doc.appendChild(martif);

            // martifHeader
            Element header = doc.createElement("martifHeader");
            martif.appendChild(header);
            Element fileDesc = doc.createElement("fileDesc");
            header.appendChild(fileDesc);
            Element sourceDesc = doc.createElement("sourceDesc");
            fileDesc.appendChild(sourceDesc);
            Element p = doc.createElement("p");
            p.setTextContent("Exported Terminology");
            sourceDesc.appendChild(p);

            // text > body
            Element text = doc.createElement("text");
            martif.appendChild(text);
            Element body = doc.createElement("body");
            text.appendChild(body);

            int idCounter = 1;
            for (int idx = 0; idx < entries.size(); idx++) {
                TermEntry e = entries.get(idx);
                RichTermEntry r = richEntries.get(idx);

                Element termEntry = doc.createElement("termEntry");
                String id = e.getEntryId() != null ? e.getEntryId() : "tid" + idCounter;
                termEntry.setAttribute("id", id);
                idCounter++;

                // Source langSet
                Element srcLangSet = doc.createElement("langSet");
                srcLangSet.setAttribute("xml:lang", sourceLang);
                Element srcTig = doc.createElement("tig");
                Element srcTerm = doc.createElement("term");
                srcTerm.setTextContent(e.getSourceTerm() != null ? e.getSourceTerm() : "");
                srcTig.appendChild(srcTerm);
                srcLangSet.appendChild(srcTig);
                // Definition in source langSet
                if (r.definition != null && !r.definition.isEmpty()) {
                    Element descrip = doc.createElement("descrip");
                    descrip.setAttribute("type", "definition");
                    descrip.setTextContent(r.definition);
                    srcLangSet.appendChild(descrip);
                }
                termEntry.appendChild(srcLangSet);

                // Target langSet
                Element tgtLangSet = doc.createElement("langSet");
                tgtLangSet.setAttribute("xml:lang", targetLang);
                Element tgtTig = doc.createElement("tig");
                Element tgtTerm = doc.createElement("term");
                tgtTerm.setTextContent(e.getTargetTerm() != null ? e.getTargetTerm() : "");
                tgtTig.appendChild(tgtTerm);
                tgtLangSet.appendChild(tgtTig);
                termEntry.appendChild(tgtLangSet);

                // Extra languages
                for (Map.Entry<String, String> exLang : r.extraLangTerms.entrySet()) {
                    Element exLangSet = doc.createElement("langSet");
                    exLangSet.setAttribute("xml:lang", exLang.getKey());
                    Element exTig = doc.createElement("tig");
                    Element exTerm = doc.createElement("term");
                    exTerm.setTextContent(exLang.getValue());
                    exTig.appendChild(exTerm);
                    exLangSet.appendChild(exTig);
                    termEntry.appendChild(exLangSet);
                }

                // Note
                if (r.note != null && !r.note.isEmpty()) {
                    Element note = doc.createElement("note");
                    note.setTextContent(r.note);
                    termEntry.appendChild(note);
                }

                // CSV/XLSX extras: map "definition" and "note" column names
                if (r.definition == null && r.note == null) {
                    // Check extraFields for columns named "definition"/"note"
                    String defVal = e.getExtraFields().get("definition");
                    String noteVal = e.getExtraFields().get("note");
                    if (defVal != null && !defVal.isEmpty()) {
                        // Already handled above if from TBX; here from CSV/XLSX
                        Element descrip = doc.createElement("descrip");
                        descrip.setAttribute("type", "definition");
                        descrip.setTextContent(defVal);
                        srcLangSet.appendChild(descrip);
                    }
                    if (noteVal != null && !noteVal.isEmpty()) {
                        Element noteElem = doc.createElement("note");
                        noteElem.setTextContent(noteVal);
                        termEntry.appendChild(noteElem);
                    }
                }

                body.appendChild(termEntry);
            }

            // Write
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            AtomicFileWriter.write(Path.of(config.getFilePath()), out -> {
                try {
                    transformer.transform(new DOMSource(doc), new StreamResult(out));
                } catch (javax.xml.transform.TransformerException ex) {
                    throw new IOException("TBX serialization failed", ex);
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to write TBX: " + config.getFilePath(), e);
        }
    }
}

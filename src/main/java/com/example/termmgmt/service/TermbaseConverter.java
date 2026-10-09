package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        /** lang -> (termNote type -> text) for types the converter does not model. */
        public Map<String, Map<String, String>> termNotes = new LinkedHashMap<>();

        public RichTermEntry(TermEntry entry) { this.entry = entry; }
    }

    // ------------------------------------------------------------------ Public API

    /**
     * Convert the termbase described by {@code config} into {@code targetFormat} and write
     * the result to {@code targetPath}. Does not modify the source file or registry.
     *
     * @throws IOException when {@code targetPath} is the source termbase itself: the export
     * regenerates the whole file, so writing onto the source would destroy the fields the
     * target format cannot carry (the UI refuses registered termbases on top of this).
     */
    public static ConversionReport convert(TermbaseConfig config, Format targetFormat,
                                           Path targetPath) throws IOException {
        Path sourcePath = Path.of(config.getFilePath());
        if (sameFile(sourcePath, targetPath)) {
            throw new IOException(
                "Export target is the source termbase file; exporting would destroy it");
        }
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

    /** Whether two paths denote the same file; falls back to absolute-path equality. */
    private static boolean sameFile(Path a, Path b) {
        try {
            Path ra = a.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Path rb = b.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return ra.equals(rb);
        } catch (IOException e) {
            // Target does not exist yet (the normal export case): compare what we can.
            try {
                return a.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    .equals(b.toAbsolutePath().normalize());
            } catch (IOException ignored) {
                return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
            }
        }
    }

    // ------------------------------------------------------------------ Reading

    /**
     * Read entries with full TBX field extraction (definition, note, extra languages,
     * unmodelled termNotes). For CSV/XLSX, wraps entries in RichTermEntry with no extras.
     * A failed TBX extras parse throws: swallowing it would report "no loss" for fields
     * the reader never actually saw.
     */
    public static List<RichTermEntry> readRich(TermbaseConfig config) throws IOException {
        List<TermEntry> raw = TermbaseLoader.loadTerms(config);
        List<RichTermEntry> result = new ArrayList<>();
        if (config.getFormat() == Format.TBX) {
            // Parse DOM for extra fields
            Map<String, RichExtras> extraById = parseTbxExtras(config);
            for (TermEntry e : raw) {
                RichTermEntry r = new RichTermEntry(e);
                String key = e.getEntryId() != null ? e.getEntryId()
                    : "ordinal_" + e.getEntryOrdinal();
                RichExtras extras = extraById.get(key);
                if (extras != null) {
                    r.definition = extras.definition;
                    r.note = extras.note;
                    r.extraLangTerms.putAll(extras.extraLangTerms);
                    for (Map.Entry<String, Map<String, String>> langNotes : extras.termNotes.entrySet()) {
                        r.termNotes.put(langNotes.getKey(), new LinkedHashMap<>(langNotes.getValue()));
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

    /** Unmodelled TBX fields read for one termEntry, before they are folded into a RichTermEntry. */
    private static final class RichExtras {
        String definition;
        String note;
        final Map<String, String> extraLangTerms = new LinkedHashMap<>();
        final Map<String, Map<String, String>> termNotes = new LinkedHashMap<>();
    }

    /**
     * Parse a TBX file's DOM to extract definition, note, extra language terms and unmodelled
     * termNotes per entry, keyed by entry id (or "ordinal_N").
     * Parsed through {@link TbxTermbaseHandler#newSecureDocumentBuilderFactory()} - termbases
     * may come from third parties, so external entities must never be expanded. A parse
     * failure is reported instead of being swallowed: silently returning nothing would turn
     * every dropped field into a misleading "no loss" report.
     */
    private static Map<String, RichExtras> parseTbxExtras(TermbaseConfig config) throws IOException {
        Map<String, RichExtras> result = new LinkedHashMap<>();
        String sourceLang = config.getSourceLang();
        String targetLang = config.getTargetLang();
        try {
            DocumentBuilderFactory dbf = TbxTermbaseHandler.newSecureDocumentBuilderFactory();
            dbf.setNamespaceAware(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new File(config.getFilePath()));
            NodeList entries = doc.getElementsByTagName("termEntry");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entryNode = (Element) entries.item(i);
                String id = entryNode.getAttribute("id");
                String key = (id != null && !id.isEmpty()) ? id : "ordinal_" + i;

                RichExtras extras = new RichExtras();
                // Definition: look in source langSet for descrip type="definition"
                extras.definition = findDefinition(entryNode, sourceLang);
                // Note: first note element in the termEntry
                extras.note = findFirstNote(entryNode);
                for (Element ls : getLangSets(entryNode)) {
                    String lang = getLang(ls);
                    if (lang == null) continue;
                    boolean isSource = sourceLang != null && lang.equalsIgnoreCase(sourceLang);
                    boolean isTarget = targetLang != null && lang.equalsIgnoreCase(targetLang);
                    if (!isSource && !isTarget) {
                        String term = getTermTextInLangSet(ls);
                        if (term != null && !term.isEmpty()) {
                            extras.extraLangTerms.put(lang, term);
                        }
                        continue;
                    }
                    // Unmodelled termNotes of the pair's own langSets survive a TBX->TBX
                    // export; administrativeStatus is modelled as the entry status instead.
                    collectTermNotes(ls, extras.termNotes.computeIfAbsent(lang, k -> new LinkedHashMap<>()));
                }
                result.put(key, extras);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse TBX extras in " + config.getFilePath(), e);
        }
        return result;
    }

    /**
     * Collect the termNotes of a langSet's term container except administrativeStatus, which
     * the loader already surfaces as the entry status. Later duplicates of a type win.
     */
    private static void collectTermNotes(Element langSet, Map<String, String> out) {
        Element container = termContainer(langSet);
        if (container == null) return;
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE || !"termNote".equals(child.getNodeName())) continue;
            String type = ((Element) child).getAttribute("type");
            if (type.isEmpty() || "administrativeStatus".equals(type)) continue;
            String text = child.getTextContent();
            if (text != null && !text.trim().isEmpty()) {
                out.put(type, text.trim());
            }
        }
    }

    /** The element holding the term and termNote children of a langSet (tig, or ntig/termGrp). */
    private static Element termContainer(Element langSet) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName();
            if ("tig".equals(name) || "ntig".equals(name)) {
                Element termGrp = "ntig".equals(name) ? firstChildElement((Element) child, "termGrp") : null;
                return termGrp != null ? termGrp : (Element) child;
            }
        }
        return null;
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

    /**
     * The extra-column values the CSV/XLSX writer will actually persist, name to count of
     * entries carrying a non-empty value. Shares its column resolution with
     * {@link #writeCsv}/{@link #writeXlsx} so the report and the file never disagree.
     */
    private static Map<String, Integer> writtenExtraColumns(TermbaseConfig config, List<RichTermEntry> entries) {
        List<String> extraCols = resolvedExtraColumns(config, entries);
        int statusIdx = CsvTermbaseHandler.findStatusColumn(extraCols);
        Map<String, Integer> written = new LinkedHashMap<>();
        for (int i = 0; i < extraCols.size(); i++) {
            String name = extraCols.get(i);
            int count = 0;
            for (RichTermEntry r : entries) {
                String v = i == statusIdx ? statusForCsvWrite(r)
                    : valueOfExtra(r.entry, name);
                if (v != null && !v.isEmpty()) count++;
            }
            if (count > 0) written.put(name, count);
        }
        return written;
    }

    /** The status text a CSV/XLSX write would store for this entry, or null/empty for none. */
    private static String statusForCsvWrite(RichTermEntry r) {
        String s = normalizeStatusForCsv(r.entry.getStoredStatusValue());
        if (s != null && !s.isEmpty()) return s;
        // The loader takes the status column into the entry, not into extraFields.
        return valueOfExtra(r.entry, TermEntry.STATUS_FIELD);
    }

    /** Extra-column value from the entry, preferring a case-matched header name. */
    private static String valueOfExtra(TermEntry e, String name) {
        String v = e.getExtraFields().get(name);
        if (v == null && name != null) {
            for (Map.Entry<String, String> en : e.getExtraFields().entrySet()) {
                if (name.equalsIgnoreCase(en.getKey())) {
                    v = en.getValue();
                    break;
                }
            }
        }
        return v;
    }

    private static List<DroppedField> detectDrops(TermbaseConfig sourceConfig, Format targetFormat,
                                                  List<RichTermEntry> entries) {
        List<DroppedField> dropped = new ArrayList<>();
        if (targetFormat == Format.CSV || targetFormat == Format.XLSX) {
            if (sourceConfig.getFormat() == Format.TBX) {
                // TBX definitions, notes, extra languages cannot be held
                int defCount = 0, noteCount = 0, extraCount = 0, termNoteCount = 0, statusCount = 0;
                for (RichTermEntry r : entries) {
                    if (r.definition != null && !r.definition.isEmpty()) defCount++;
                    if (r.note != null && !r.note.isEmpty()) noteCount++;
                    if (!r.extraLangTerms.isEmpty()) extraCount++;
                    boolean anyNote = r.termNotes.values().stream().mapToInt(Map::size).sum() > 0;
                    if (anyNote) termNoteCount++;
                    if (r.entry.getStoredStatusValue() != null
                        && !r.entry.getStoredStatusValue().isEmpty()) statusCount++;
                }
                if (defCount > 0) dropped.add(new DroppedField("definition", defCount));
                if (noteCount > 0) dropped.add(new DroppedField("note", noteCount));
                if (extraCount > 0) dropped.add(new DroppedField("extra language terms", extraCount));
                if (termNoteCount > 0) dropped.add(new DroppedField("term notes", termNoteCount));
                // Status is carried into a "status" column (appended when absent); it can only
                // be lost if the value itself could not be written.
                if (statusCount > 0
                    && !writtenExtraColumns(sourceConfig, entries).containsKey(TermEntry.STATUS_FIELD)) {
                    dropped.add(new DroppedField("status", statusCount));
                }
            }
        }
        return dropped;
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        for (String s : names) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
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

    /**
     * The status text a CSV/XLSX write stores: the TBX domain spelling is normalized to the
     * plain lower-case value the CSV handlers use; unknown text is kept verbatim.
     */
    private static String normalizeStatusForCsv(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        TermStatus ts = TermStatus.parse(stored);
        return ts != null ? ts.value() : stored;
    }

    // ------------------------------------------------------------------ CSV writing

    private static void writeCsv(TermbaseConfig config, List<TermEntry> entries,
                                 List<RichTermEntry> richEntries) throws IOException {
        String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
        String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";
        List<String> extraCols = resolvedExtraColumns(config, richEntries);
        int statusIdx = CsvTermbaseHandler.findStatusColumn(extraCols);

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
                    String val = i == statusIdx ? statusForCsvWrite(r)
                        : valueOfExtra(e, extraCols.get(i));
                    row[2 + i] = val != null ? val : "";
                }
                csvWriter.writeNext(row);
            }
            csvWriter.flush();
        });
    }

    /**
     * The extra columns the export writes, mirroring the CSV/XLSX handlers: the configured
     * columns first, then entry extras the source config does not list (an export builds a
     * fresh config, so a TBX->CSV round-trip's columns are only on the entries), and finally
     * an appended status column when no configured column exists and some entry carries one.
     */
    private static List<String> resolvedExtraColumns(TermbaseConfig config, List<RichTermEntry> entries) {
        List<String> extraCols = new ArrayList<>(config.getExtraColumns());
        for (RichTermEntry r : entries) {
            for (String name : r.entry.getExtraFields().keySet()) {
                if (!containsIgnoreCase(extraCols, name) && !TermEntry.STATUS_FIELD.equalsIgnoreCase(name)) {
                    extraCols.add(name);
                }
            }
        }
        if (CsvTermbaseHandler.findStatusColumn(extraCols) < 0
            && CsvTermbaseHandler.hasStatusColumn(entries.stream().map(r -> r.entry).toList())) {
            extraCols.add(TermEntry.STATUS_FIELD);
        }
        return extraCols;
    }

    // ------------------------------------------------------------------ XLSX writing

    private static void writeXlsx(TermbaseConfig config, List<TermEntry> entries,
                                  List<RichTermEntry> richEntries) throws IOException {
        String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
        String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";
        List<String> extraCols = resolvedExtraColumns(config, richEntries);
        int statusIdx = CsvTermbaseHandler.findStatusColumn(extraCols);

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
                    RichTermEntry r = richEntries.get(idx);
                    var row = sheet.createRow(idx + 1);
                    row.createCell(0).setCellValue(e.getSourceTerm() != null ? e.getSourceTerm() : "");
                    row.createCell(1).setCellValue(e.getTargetTerm() != null ? e.getTargetTerm() : "");
                    for (int i = 0; i < extraCols.size(); i++) {
                        String val = i == statusIdx ? statusForCsvWrite(r)
                            : valueOfExtra(e, extraCols.get(i));
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

                // Source langSet: term, unmodelled termNotes, status, definition
                Element srcLangSet = appendTbxLangSet(doc, termEntry, sourceLang,
                    e.getSourceTerm(), termNotesFor(r, sourceLang));
                String statusVal = normalizeStatusForTbx(e.getStoredStatusValue());
                if (statusVal != null) {
                    setTbxAdministrativeStatus(srcLangSet, statusVal);
                }
                String defText = r.definition != null && !r.definition.isEmpty()
                    ? r.definition : valueOfExtra(e, "definition");
                if (defText != null && !defText.isEmpty()) {
                    Element descrip = doc.createElement("descrip");
                    descrip.setAttribute("type", "definition");
                    descrip.setTextContent(defText);
                    srcLangSet.appendChild(descrip);
                }

                // Target langSet
                appendTbxLangSet(doc, termEntry, targetLang,
                    e.getTargetTerm(), termNotesFor(r, targetLang));

                // Extra languages
                for (Map.Entry<String, String> exLang : r.extraLangTerms.entrySet()) {
                    appendTbxLangSet(doc, termEntry, exLang.getKey(), exLang.getValue(), null);
                }

                // Note: the TBX <note> element, or the CSV/XLSX "note" column
                String noteText = r.note != null && !r.note.isEmpty()
                    ? r.note : valueOfExtra(e, "note");
                if (noteText != null && !noteText.isEmpty()) {
                    Element note = doc.createElement("note");
                    note.setTextContent(noteText);
                    termEntry.appendChild(note);
                }

                // Remaining CSV/XLSX extra columns (domain, partOfSpeech, other languages)
                // are kept as termNotes in the source langSet, matching the per-language
                // termNote model the TBX rich read uses for a TBX -> TBX export.
                for (Map.Entry<String, String> ex : e.getExtraFields().entrySet()) {
                    String name = ex.getKey();
                    String val = ex.getValue();
                    if (val == null || val.isEmpty()) continue;
                    if (TermEntry.STATUS_FIELD.equalsIgnoreCase(name)
                        || "definition".equalsIgnoreCase(name) || "note".equalsIgnoreCase(name)) continue;
                    if (name.equalsIgnoreCase(sourceLang) || name.equalsIgnoreCase(targetLang)) continue;
                    Element container = termContainer(srcLangSet);
                    if (container == null) continue;
                    Element termNote = doc.createElement("termNote");
                    termNote.setAttribute("type", name);
                    termNote.setTextContent(val);
                    container.appendChild(termNote);
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

    /**
     * Append a langSet with a tig/term (and, when given, the unmodelled termNotes kept by
     * the rich read) to a termEntry; returns the langSet so callers can add status or a
     * definition to it.
     */
    private static Element appendTbxLangSet(Document doc, Element termEntry, String lang,
                                            String termText, Map<String, String> termNotes) {
        Element langSet = doc.createElement("langSet");
        langSet.setAttribute("xml:lang", lang);
        Element tig = doc.createElement("tig");
        Element term = doc.createElement("term");
        term.setTextContent(termText != null ? termText : "");
        tig.appendChild(term);
        if (termNotes != null) {
            for (Map.Entry<String, String> tn : termNotes.entrySet()) {
                Element termNote = doc.createElement("termNote");
                termNote.setAttribute("type", tn.getKey());
                termNote.setTextContent(tn.getValue());
                tig.appendChild(termNote);
            }
        }
        langSet.appendChild(tig);
        termEntry.appendChild(langSet);
        return langSet;
    }

    /** The stored termNotes for a language, matched case-insensitively against the file's tag. */
    private static Map<String, String> termNotesFor(RichTermEntry r, String lang) {
        if (lang == null) return null;
        for (Map.Entry<String, Map<String, String>> en : r.termNotes.entrySet()) {
            if (lang.equalsIgnoreCase(en.getKey())) return en.getValue();
        }
        return null;
    }

    /** The TBX-Basic domain value for a stored status, or null when there is nothing to write. */
    private static String normalizeStatusForTbx(String stored) {
        if (stored == null || stored.isEmpty()) return null;
        TermStatus ts = TermStatus.parse(stored);
        return ts != null ? ts.tbxValue() : stored;
    }

    /** Set the termNote type="administrativeStatus" in the langSet's term container. */
    private static void setTbxAdministrativeStatus(Element langSet, String statusValue) {
        Element container = termContainer(langSet);
        if (container == null) return;
        Element termNote = container.getOwnerDocument().createElement("termNote");
        termNote.setAttribute("type", "administrativeStatus");
        termNote.setTextContent(statusValue);
        container.appendChild(termNote);
    }
}

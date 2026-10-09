package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.service.TermbaseConverter.DroppedField;
import com.example.termmgmt.service.TermbaseConverter.RichTermEntry;
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
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports a termbase as a DITA 1.3 Glossary Group document.
 *
 * <p>Verified against the DITA 1.3 specification (OASIS Std 04 Nov 2015):
 * <ul>
 *   <li>Top-level: {@code <glossgroup>} with required {@code id} and {@code <title>} child.</li>
 *   <li>Per entry: {@code <glossentry id="...">} containing {@code <glossterm>} (required)
 *       and {@code <glossdef>} (optional; omitted when no definition is available).</li>
 *   <li>DOCTYPE: {@code <!DOCTYPE glossgroup PUBLIC "-//OASIS//DTD DITA Glossary Group//EN"
 *       "glossgroup.dtd">}</li>
 *   <li>Class attributes (DTD-based documents do not require class attributes in content,
 *       but we add them for maximum interoperability with DITA-aware processors).</li>
 * </ul>
 */
public final class DitaGlossaryExporter {

    /** DITA 1.3 DOCTYPE for a glossary group document. */
    static final String DOCTYPE_PUBLIC = "-//OASIS//DTD DITA Glossary Group//EN";
    static final String DOCTYPE_SYSTEM = "glossgroup.dtd";

    private DitaGlossaryExporter() {}

    /**
     * The fields a DITA glossary export cannot carry: only the source term and a definition
     * survive as {@code glossterm}/{@code glossdef}, everything else (status, notes, other
     * languages, unmodelled termNotes) is dropped and must be reported to the user.
     */
    public static List<DroppedField> detectDrops(List<RichTermEntry> entries) {
        List<DroppedField> dropped = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return dropped;
        int statusCount = 0, noteCount = 0, extraCount = 0, termNoteCount = 0;
        for (RichTermEntry r : entries) {
            String status = r.entry.getStoredStatusValue();
            if (status != null && !status.isEmpty()) statusCount++;
            if (r.note != null && !r.note.isEmpty()) noteCount++;
            if (!r.extraLangTerms.isEmpty()) extraCount++;
            boolean anyTermNote = r.termNotes.values().stream().mapToInt(Map::size).sum() > 0;
            if (anyTermNote) termNoteCount++;
        }
        if (statusCount > 0) dropped.add(new DroppedField("status", statusCount));
        if (noteCount > 0) dropped.add(new DroppedField("note", noteCount));
        if (extraCount > 0) dropped.add(new DroppedField("extra language terms", extraCount));
        if (termNoteCount > 0) dropped.add(new DroppedField("term notes", termNoteCount));
        return dropped;
    }

    /**
     * Export the given entries as a DITA glossary group file.
     *
     * @param richEntries entries with optional definitions (from TBX) or plain terms (CSV/XLSX)
     * @param sourceLang  language tag for the glossterm
     * @param targetLang  language tag (reserved for future multi-language glossdef)
     * @param targetPath  output file path
     */
    public static void export(List<RichTermEntry> richEntries,
                              String sourceLang, String targetLang,
                              Path targetPath) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.newDocument();

            // Root: glossgroup
            Element glossgroup = doc.createElement("glossgroup");
            glossgroup.setAttribute("id", "termbase-glossary");
            glossgroup.setAttribute("class", "- topic/topic concept/concept glossgroup/glossgroup ");
            doc.appendChild(glossgroup);

            // Required title
            Element title = doc.createElement("title");
            title.setTextContent("Terminology Glossary");
            glossgroup.appendChild(title);

            // Generate glossentry elements
            Set<String> usedIds = new HashSet<>();
            usedIds.add("termbase-glossary"); // the root id itself
            int seqCounter = 1;

            for (RichTermEntry r : richEntries) {
                Element entry = doc.createElement("glossentry");
                String id = resolveId(r.entry, seqCounter, usedIds);
                entry.setAttribute("id", id);
                entry.setAttribute("class", "- topic/topic concept/concept glossentry/glossentry ");
                usedIds.add(id);
                seqCounter++;

                // glossterm (required)
                Element glossterm = doc.createElement("glossterm");
                glossterm.setAttribute("class", "- topic/title concept/title glossentry/glossterm ");
                String termText = r.entry.getSourceTerm();
                if (termText == null) termText = "";
                glossterm.setTextContent(termText);
                entry.appendChild(glossterm);

                // glossdef (optional; from TBX definition or CSV/XLSX "definition" extra column)
                String defText = r.definition;
                if (defText == null || defText.isEmpty()) {
                    // Try the extraFields "definition" column (for CSV/XLSX sources)
                    defText = r.entry.getExtraFields().get("definition");
                }
                if (defText != null && !defText.isEmpty()) {
                    Element glossdef = doc.createElement("glossdef");
                    glossdef.setAttribute("class",
                        "- topic/abstract concept/abstract glossentry/glossdef ");
                    glossdef.setTextContent(defText);
                    entry.appendChild(glossdef);
                }

                glossgroup.appendChild(entry);
            }

            // Write with DOCTYPE
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, DOCTYPE_PUBLIC);
            transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, DOCTYPE_SYSTEM);
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            AtomicFileWriter.write(targetPath, out -> {
                try {
                    transformer.transform(new DOMSource(doc), new StreamResult(out));
                } catch (javax.xml.transform.TransformerException ex) {
                    throw new IOException("DITA glossary serialization failed", ex);
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to write DITA glossary: " + targetPath, e);
        }
    }

    /**
     * Resolve a unique id for the glossentry: prefer the TBX entryId, otherwise
     * generate "term_N" and ensure uniqueness.
     */
    static String resolveId(TermEntry entry, int fallbackSeq, Set<String> usedIds) {
        String id = entry.getEntryId();
        if (id != null && !id.isEmpty() && isValidDitaId(id) && !usedIds.contains(id)) {
            return id;
        }
        // Generate a unique sequential id
        String generated = "term_" + fallbackSeq;
        while (usedIds.contains(generated)) {
            fallbackSeq++;
            generated = "term_" + fallbackSeq;
        }
        return generated;
    }

    /**
     * DITA ids must be valid XML NCNames (no leading digit, no special characters).
     * Basic check: starts with letter or underscore, contains only alnum + hyphen/underscore/period.
     */
    static boolean isValidDitaId(String id) {
        if (id == null || id.isEmpty()) return false;
        char first = id.charAt(0);
        if (!Character.isLetter(first) && first != '_') return false;
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                return false;
            }
        }
        return true;
    }
}

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
import java.util.LinkedHashSet;
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
 *   <li>DITA has no dedicated translation element, so a target-language translation is
 *       carried inside the same {@code glossentry} as {@code <glossBody><glossAlt>
 *       <glossSynonym xml:lang="...">}. The spec notes {@code glossAlt}/{@code glossSynonym}
 *       are language-scoped, hence the {@code xml:lang} tag marking the target language.</li>
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

    /** The XML namespace, whose {@code xml} prefix is predefined and always bound. */
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    /**
     * A language tag as BCP 47 writes it: trimmed, with the underscore some locale APIs use
     * ({@code zh_CN}) turned into the hyphen {@code xml:lang} requires. Null when unknown.
     */
    static String normalizeLang(String lang) {
        if (lang == null) return null;
        String t = lang.trim().replace('_', '-');
        return t.isEmpty() ? null : t;
    }

    /** Set the standard {@code xml:lang} attribute on an element when the tag is known. */
    static void setLang(Element e, String lang) {
        String tag = normalizeLang(lang);
        if (tag != null) {
            e.setAttributeNS(XML_NS, "xml:lang", tag);
        }
    }

    /**
     * The fields a DITA glossary export cannot carry: the source term survives as
     * {@code glossterm}, the definition as {@code glossdef}, and the target translation as a
     * {@code glossSynonym} inside {@code glossBody}/{@code glossAlt} (only when it is in a
     * language distinct from the source); everything else (status, notes, third languages,
     * unmodelled termNotes) is dropped and must be reported to the user.
     *
     * <p>Without the language pair the target language is treated as unknown, so target
     * translations are conservatively reported as dropped; {@link #detectDrops(List, String,
     * String)} is the accurate form used by the real export.
     */
    public static List<DroppedField> detectDrops(List<RichTermEntry> entries) {
        return detectDrops(entries, null, null);
    }

    /**
     * As {@link #detectDrops(List)}, knowing the language pair of the export so that target
     * translations which will in fact be written as linked {@code glossentry} elements are
     * not reported as dropped.
     */
    public static List<DroppedField> detectDrops(List<RichTermEntry> entries,
                                                 String sourceLang, String targetLang) {
        List<DroppedField> dropped = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return dropped;
        int statusCount = 0, noteCount = 0, extraCount = 0, termNoteCount = 0, extraColumnCount = 0, targetCount = 0;
        Set<String> termNoteTypes = new LinkedHashSet<>();
        for (RichTermEntry r : entries) {
            String status = r.entry.getStoredStatusValue();
            if (status != null && !status.isEmpty()) statusCount++;
            if (r.note != null && !r.note.isEmpty()) noteCount++;
            if (!r.extraLangTerms.isEmpty()) extraCount++;
            if (!canExportTarget(r, sourceLang, targetLang)) {
                String target = r.entry.getTargetTerm();
                if (target != null && !target.isEmpty()) targetCount++;
            }
            boolean anyTermNote = r.termNotes.values().stream().mapToInt(Map::size).sum() > 0;
            if (anyTermNote) {
                termNoteCount++;
                for (Map<String, String> byType : r.termNotes.values()) {
                    termNoteTypes.addAll(byType.keySet());
                }
            }
            // CSV/XLSX columns beyond source/target (domain, partOfSpeech, note, ...) have no
            // place in a glossentry; only a "definition" column is carried into glossdef, and
            // status is reported on its own line - so anything else is dropped.
            for (Map.Entry<String, String> ef : r.entry.getExtraFields().entrySet()) {
                String name = ef.getKey();
                String val = ef.getValue();
                if (val == null || val.isEmpty()) continue;
                if ("definition".equalsIgnoreCase(name)
                    || TermEntry.STATUS_FIELD.equalsIgnoreCase(name)) continue;
                extraColumnCount++;
                break;
            }
        }
        if (statusCount > 0) dropped.add(new DroppedField("status", statusCount));
        if (noteCount > 0) dropped.add(new DroppedField("note", noteCount));
        if (targetCount > 0) dropped.add(new DroppedField("target language terms", targetCount));
        if (extraCount > 0) dropped.add(new DroppedField("extra language terms", extraCount));
        if (termNoteCount > 0)
            dropped.add(new DroppedField(TermbaseConverter.termNotesLabel(termNoteTypes), termNoteCount));
        if (extraColumnCount > 0) dropped.add(new DroppedField("extra columns", extraColumnCount));
        return dropped;
    }

    /**
     * Export the given entries as a DITA glossary group file.
     *
     * @param richEntries entries with optional definitions (from TBX) or plain terms (CSV/XLSX)
     * @param sourceLang  language tag for the source glossterm
     * @param targetLang  language tag for the target translation, written as a
     *                    {@code glossSynonym}; carried only when it differs from
     *                    {@code sourceLang}
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
                setLang(entry, sourceLang);
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

                // Target translation: DITA has no dedicated "translation" element, so the
                // translation is carried inside glossBody > glossAlt > glossSynonym. The
                // spec notes glossAlt/glossSynonym are language-scoped, so xml:lang marks
                // the synonym as being in the target language.
                if (canExportTarget(r, sourceLang, targetLang)) {
                    Element glossBody = doc.createElement("glossBody");
                    glossBody.setAttribute("class",
                        "- topic/body concept/conbody glossentry/glossBody ");

                    Element glossAlt = doc.createElement("glossAlt");
                    glossAlt.setAttribute("class",
                        "- topic/section concept/section glossentry/glossAlt ");

                    Element glossSynonym = doc.createElement("glossSynonym");
                    glossSynonym.setAttribute("class",
                        "- topic/title concept/title glossentry/glossSynonym ");
                    setLang(glossSynonym, targetLang);
                    String targetText = r.entry.getTargetTerm();
                    if (targetText == null) targetText = "";
                    glossSynonym.setTextContent(targetText);

                    glossAlt.appendChild(glossSynonym);
                    glossBody.appendChild(glossAlt);
                    entry.appendChild(glossBody);
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
     * Whether the entry's target translation can be exported: it needs a non-empty target
     * term and a target language distinct from the source one (otherwise the
     * {@code glossSynonym} would only repeat the source term in its own language).
     */
    static boolean canExportTarget(RichTermEntry r, String sourceLang, String targetLang) {
        if (r == null || r.entry == null) return false;
        String target = r.entry.getTargetTerm();
        if (target == null || target.isEmpty()) return false;
        return isDistinctLang(sourceLang, targetLang);
    }

    /**
     * True when both tags are known and denote different languages (case-insensitive, and
     * {@code zh_CN} counts as {@code zh-CN}).
     */
    static boolean isDistinctLang(String sourceLang, String targetLang) {
        String source = normalizeLang(sourceLang);
        String target = normalizeLang(targetLang);
        if (source == null || target == null) return false;
        return !source.equalsIgnoreCase(target);
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

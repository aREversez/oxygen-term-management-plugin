package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.util.AtomicFileWriter;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.NamedNodeMap;
import java.io.*;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class TbxTermbaseHandler {

    /**
     * Termbase files may come from untrusted sources. Turn off external entities and
     * external DTD loading (XXE / SSRF). DOCTYPE declarations stay allowed because real
     * TBX files commonly carry one, but their external DTD is neither fetched nor required.
     */
    private static DocumentBuilderFactory newSecureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        setFeatureIfSupported(dbf, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(dbf, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(dbf, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        return dbf;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory dbf, String feature, boolean value) {
        try {
            dbf.setFeature(feature, value);
        } catch (ParserConfigurationException | RuntimeException e) {
            // Feature not known to this JAXP implementation; the others still apply.
        }
    }

    private static TransformerFactory newSecureTransformerFactory() {
        TransformerFactory tf = TransformerFactory.newInstance();
        try {
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (Exception e) {
            // Not supported by this implementation.
        }
        try {
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        } catch (IllegalArgumentException e) {
            // JAXP 1.5 attributes not supported by this implementation.
        }
        return tf;
    }

    public static List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms = new ArrayList<>();
        String filePath = config.getFilePath();

        try {
            Document doc = parseFile(filePath);

            NodeList termEntryNodes = doc.getElementsByTagName("termEntry");
            LangSelection selection = resolveSelection(doc, config);

            String detectedSourceLang = null;
            String detectedTargetLang = null;

            for (int i = 0; i < termEntryNodes.getLength(); i++) {
                Element termEntryNode = (Element) termEntryNodes.item(i);
                // 5.2: skip non-loadable nodes entirely; they stay untouched on save.
                if (!isLoadable(termEntryNode, selection)) continue;

                TermEntry entry = new TermEntry();
                String id = termEntryNode.getAttribute("id");
                if (!id.isEmpty()) {
                    entry.setEntryId(id);
                }
                entry.setEntryOrdinal(i);

                // Same selection the save uses, so both operate on the same pair of langSets.
                LangPair pair = pairOf(termEntryNode, selection);
                if (pair.source != null) {
                    entry.setSourceTerm(getTermTextOrNull(pair.source));
                    if (detectedSourceLang == null) detectedSourceLang = langOf(pair.source);
                }
                entry.setPersistedFingerprint(nodeFingerprint(termEntryNode, selection));
                if (pair.target != null) {
                    entry.setTargetTerm(getTermTextOrNull(pair.target));
                    if (detectedTargetLang == null) detectedTargetLang = langOf(pair.target);
                }

                // Read administrativeStatus from the source langSet's term container.
                if (pair.source != null) {
                    String raw = findAdministrativeStatus(pair.source);
                    if (raw != null) {
                        entry.setStatusRaw(raw);
                    }
                }
                terms.add(entry);
            }

            if (selection != null) {
                // The chosen pair, spelled as the file spells it, even if no loadable entry has it.
                config.setSourceLang(selection.source);
                config.setTargetLang(selection.target);
            } else {
                if (detectedSourceLang != null) config.setSourceLang(detectedSourceLang);
                if (detectedTargetLang != null) config.setTargetLang(detectedTargetLang);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to load TBX: " + filePath, e);
        }

        return terms;
    }

    /**
     * Save without losing what the plugin does not model. The file on disk is re-parsed and
     * matched against the list by termEntry id:
     * - matched nodes keep every descendant (descrip, note, termNote, further langSets,
     *   ntig/termGrp structures); only the term text inside the same two langSets that
     *   loadTerms selected is replaced;
     * - entries without an id, or whose id is no longer on disk, get a fresh node with a
     *   collision-free id appended to body;
     * - disk nodes absent from the list are removed.
     * Existing ids are never rewritten.
     *
     * Side effect on the passed list (step 10): after the file has been written successfully,
     * every {@link TermEntry} that maps to a node in the saved document has its
     * {@code entryOrdinal} refreshed to that node's final document-order index and, when the
     * node carries an id, its {@code entryId} refreshed to that id (including ids minted for
     * brand-new entries). This keeps a reused, never-reloaded list self-consistent so the next
     * save claims the right nodes instead of drifting on stale ordinals. On a failed write the
     * entries are left untouched, so memory never diverges from an unchanged disk file.
     */
    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        String filePath = config.getFilePath();

        try {
            Document doc = parseFile(filePath);

            NodeList bodyNodes = doc.getElementsByTagName("body");
            if (bodyNodes.getLength() == 0) {
                throw new RuntimeException("No body node in TBX file");
            }
            Element body = (Element) bodyNodes.item(0);
            LangSelection selection = resolveSelection(doc, config);

            String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-CN";
            String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-US";
            if (selection != null) {
                sourceLang = selection.source;
                targetLang = selection.target;
            }

            // Snapshot the termEntry nodes and index them by id (the live list would shift
            // under removals, and the same id may legitimately appear on several nodes).
            NodeList allEntryNodes = doc.getElementsByTagName("termEntry");
            List<Element> snapshot = new ArrayList<>();
            for (int i = 0; i < allEntryNodes.getLength(); i++) {
                snapshot.add((Element) allEntryNodes.item(i));
            }
            // 5.5: Index by id for O(1) claiming.
            Map<String, Deque<Element>> idMap = new HashMap<>();
            Set<String> usedIds = new HashSet<>();
            for (Element e : snapshot) {
                String id = e.getAttribute("id");
                if (!id.isEmpty()) {
                    usedIds.add(id);
                    idMap.computeIfAbsent(id, k -> new ArrayDeque<>()).addLast(e);
                }
            }

            Set<Element> kept = Collections.newSetFromMap(new IdentityHashMap<>());
            // 10.2: Remember which final node each entry landed in (claimed or freshly
            // created), so after a successful write we can refresh its ordinal/id in memory.
            Map<Element, TermEntry> nodeToEntry = new IdentityHashMap<>();
            for (TermEntry entry : terms) {
                Element node = null;
                // Try id-based claim first; fall back to ordinal-based claim for no-id entries.
                if (entry.getEntryId() != null) {
                    Deque<Element> queue = idMap.get(entry.getEntryId());
                    if (queue != null && !queue.isEmpty()) {
                        node = queue.pollFirst();
                    }
                } else if (entry.getEntryOrdinal() >= 0
                        && entry.getEntryOrdinal() < snapshot.size()) {
                    // Claim the same document-position node if it has no id and is loadable.
                    // 12: the ordinal is only a position hint. It may be stale (an entry held in
                    // an undo snapshot, for one), so the node must still carry the source and
                    // target text this entry last had on disk; otherwise it is someone else's.
                    Element candidate = snapshot.get(entry.getEntryOrdinal());
                    String candidateId = candidate.getAttribute("id");
                    if (candidateId.isEmpty() && isLoadable(candidate, selection) && !kept.contains(candidate)
                            && Objects.equals(nodeFingerprint(candidate, selection),
                                    entry.getPersistedFingerprint())) {
                        node = candidate;
                    }
                }

                if (node != null && !kept.contains(node)) {
                    kept.add(node);
                    nodeToEntry.put(node, entry);
                    updateEntryNode(doc, node, entry, sourceLang, targetLang, selection);
                } else {
                    String newId = nextFreeId(usedIds);
                    usedIds.add(newId);
                    Element fresh = doc.createElement("termEntry");
                    fresh.setAttribute("id", newId);
                    appendLangSet(fresh, sourceLang, entry.getSourceTerm());
                    appendLangSet(fresh, targetLang, entry.getTargetTerm());
                    String statusVal = entry.getStoredStatusValue();
                    if (statusVal != null && !statusVal.isEmpty()) {
                        LangPair freshPair = pairOf(fresh, selection);
                        if (freshPair.source != null) {
                            setAdministrativeStatus(freshPair.source, toTbxValue(statusVal));
                        }
                    }
                    body.appendChild(fresh);
                    nodeToEntry.put(fresh, entry);
                }
            }

            // 5.2: Only delete loadable unclaimed nodes; non-loadable nodes stay untouched.
            for (Element e : snapshot) {
                if (!kept.contains(e) && isLoadable(e, selection) && e.getParentNode() != null) {
                    e.getParentNode().removeChild(e);
                }
            }

            // 10.2: The DOM is now final (additions appended, removals done). Walk the live
            // termEntry list in document order and record, for each entry, the index and id of
            // the node it ended up in. Applied to the entries only once the write succeeds, so
            // a failed save leaves their claiming info at the last known-good values.
            NodeList finalNodes = doc.getElementsByTagName("termEntry");
            List<Object[]> writeBack = new ArrayList<>();
            for (int i = 0; i < finalNodes.getLength(); i++) {
                Element node = (Element) finalNodes.item(i);
                TermEntry entry = nodeToEntry.get(node);
                if (entry == null) continue;
                String id = node.getAttribute("id");
                writeBack.add(new Object[] { entry, i, id.isEmpty() ? null : id,
                        nodeFingerprint(node, selection) });
            }

            // Drop the whitespace-only text nodes left by pretty-printing. The transformer
            // adds its own indentation, so keeping the old ones makes every save add another
            // layer of blank lines. Removing them first makes repeated saves byte-identical.
            stripWhitespaceTextNodes(doc.getDocumentElement());

            Transformer transformer = newSecureTransformerFactory().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            DocumentType doctype = doc.getDoctype();
            if (doctype != null) {
                // Real TBX files reference their DTD; keep the declaration round-tripping.
                if (doctype.getSystemId() != null) {
                    transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, doctype.getSystemId());
                }
                if (doctype.getPublicId() != null) {
                    transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, doctype.getPublicId());
                }
            }
            // An internal subset (<!DOCTYPE ... [...]>) cannot be carried through
            // OutputKeys; such files lose only that subset, never term data.

            AtomicFileWriter.write(Path.of(filePath), out -> {
                try {
                    transformer.transform(new DOMSource(doc), new StreamResult(out));
                } catch (javax.xml.transform.TransformerException e) {
                    throw new IOException("TBX serialization failed", e);
                }
            });

            // 10.2: Write reached the disk successfully - now refresh the entries' claiming
            // info. Doing it here (not earlier) guarantees memory and disk never diverge when
            // the write throws. The final node count is the guard bound for the assertion.
            int nodeCount = finalNodes.getLength();
            for (Object[] w : writeBack) {
                TermEntry entry = (TermEntry) w[0];
                int ordinal = (Integer) w[1];
                // 10.2 #6: cheap consistency guard - the ordinal is by construction a valid
                // final index; a violation means the write-back and DOM drifted apart.
                if (ordinal >= nodeCount) {
                    System.err.println("TBX save: entry ordinal " + ordinal
                        + " out of range for " + nodeCount + " nodes");
                }
                entry.setEntryOrdinal(ordinal);
                entry.setPersistedFingerprint((String) w[3]);
                if (w[2] != null) {
                    entry.setEntryId((String) w[2]);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save TBX: " + filePath, e);
        }
    }

    private static Document parseFile(String filePath) throws Exception {
        DocumentBuilderFactory dbf = newSecureDocumentBuilderFactory();
        dbf.setNamespaceAware(false);
        DocumentBuilder builder = dbf.newDocumentBuilder();
        return builder.parse(new File(filePath));
    }

    /** "tid1", "tid2", ... skipping anything already used on disk or earlier in this save. */
    private static String nextFreeId(Set<String> usedIds) {
        int n = 1;
        String id = "tid" + n;
        while (usedIds.contains(id)) {
            n++;
            id = "tid" + n;
        }
        return id;
    }

    /**
     * Convert a stored status value (which may be the short CSV form like "preferred" or
     * an already-TBX form like "deprecatedTerm-admn-sts" or an unknown string) into the
     * TBX-Basic administrativeStatus domain value.
     */
    private static String toTbxValue(String storedValue) {
        TermStatus ts = TermStatus.parse(storedValue);
        return ts != null ? ts.tbxValue() : storedValue;
    }

    /**
     * 6.3: Return the element that holds the term and termNote children for a langSet.
     * For tig: returns the tig element.
     * For ntig: returns the ntig/termGrp element.
     * Used by all three status helpers (find, set, remove).
     */
    private static Element termContainer(Element langSet) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName();
            if ("tig".equals(name)) return (Element) child;
            if ("ntig".equals(name)) {
                Node tg = firstElementChild(child, "termGrp");
                if (tg != null) return (Element) tg;
            }
        }
        return null;
    }

    /**
     * The termNote type="administrativeStatus" text inside the langSet's term container, or null
     * if absent. Used during load to populate TermEntry.loadedStatusRaw.
     */
    private static String findAdministrativeStatus(Element langSet) {
        Element container = termContainer(langSet);
        if (container == null) return null;
        NodeList containerChildren = container.getChildNodes();
        for (int j = 0; j < containerChildren.getLength(); j++) {
            Node tn = containerChildren.item(j);
            if (tn.getNodeType() != Node.ELEMENT_NODE || !"termNote".equals(tn.getNodeName())) continue;
            if ("administrativeStatus".equals(((Element) tn).getAttribute("type"))) {
                String text = tn.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }
        return null;
    }

    /**
     * Set the termNote type="administrativeStatus" in the langSet's term container.
     * Creates it if needed. No-op if statusValue is null.
     */
    private static void setAdministrativeStatus(Element langSet, String statusValue) {
        if (statusValue == null) return;
        Element container = termContainer(langSet);
        if (container == null) return;
        // Look for existing termNote type="administrativeStatus".
        Element existing = null;
        NodeList containerChildren = container.getChildNodes();
        for (int j = 0; j < containerChildren.getLength(); j++) {
            Node tn = containerChildren.item(j);
            if (tn.getNodeType() != Node.ELEMENT_NODE || !"termNote".equals(tn.getNodeName())) continue;
            if ("administrativeStatus".equals(((Element) tn).getAttribute("type"))) {
                existing = (Element) tn;
                break;
            }
        }
        if (existing != null) {
            existing.setTextContent(statusValue);
        } else {
            Document doc = langSet.getOwnerDocument();
            Element termNote = doc.createElement("termNote");
            termNote.setAttribute("type", "administrativeStatus");
            termNote.setTextContent(statusValue);
            container.appendChild(termNote);
        }
    }

    /** Remove the termNote type="administrativeStatus" from the langSet's term container, if present. */
    private static void removeAdministrativeStatus(Element langSet) {
        Element container = termContainer(langSet);
        if (container == null) return;
        NodeList containerChildren = container.getChildNodes();
        for (int j = containerChildren.getLength() - 1; j >= 0; j--) {
            Node tn = containerChildren.item(j);
            if (tn.getNodeType() != Node.ELEMENT_NODE || !"termNote".equals(tn.getNodeName())) continue;
            if ("administrativeStatus".equals(((Element) tn).getAttribute("type"))) {
                container.removeChild(tn);
            }
        }
    }

    /** Replace the term text in the same langSets loadTerms selected; append any missing one. */
    private static void updateEntryNode(Document doc, Element node, TermEntry entry,
                                        String sourceLang, String targetLang,
                                        LangSelection selection) {
        LangPair pair = pairOf(node, selection);
        if (pair.source != null) {
            setTermText(pair.source, entry.getSourceTerm());
        } else if (pair.target == null || !isBlank(entry.getSourceTerm())) {
            // A node that has neither language gets a source langSet as before; one that has only
            // the target is not given an empty source it never had.
            appendLangSet(node, sourceLang, entry.getSourceTerm());
            pair = pairOf(node, selection);
        }
        if (pair.target != null) {
            setTermText(pair.target, entry.getTargetTerm());
        } else if (entry.getTargetTerm() != null && !entry.getTargetTerm().isEmpty()) {
            appendLangSet(node, targetLang, entry.getTargetTerm());
        }
        // Handle administrativeStatus termNote.
        if (pair.source != null) {
            String statusVal = entry.getStoredStatusValue();
            if (statusVal != null && statusVal.isEmpty()) {
                // 6.1: Explicitly cleared – remove the termNote.
                removeAdministrativeStatus(pair.source);
            } else if (statusVal != null) {
                setAdministrativeStatus(pair.source, toTbxValue(statusVal));
            }
            // If statusVal is null, leave existing termNote untouched (preserves unknown values
            // on noop save, and entries that never had a status simply don't get one added).
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void appendLangSet(Element parent, String lang, String termText) {
        Document doc = parent.getOwnerDocument();
        Element langSet = doc.createElement("langSet");
        langSet.setAttribute("xml:lang", lang);
        Element tig = doc.createElement("tig");
        Element term = doc.createElement("term");
        term.setTextContent(termText != null ? termText : "");
        tig.appendChild(term);
        langSet.appendChild(tig);
        parent.appendChild(langSet);
    }

    /**
     * 5.3: The langSets that structurally expose a term element (tig/term or
     * ntig/termGrp/term), in document order: index 0 is the source and index 1 the target
     * used by both load and save. Selection no longer requires non-empty text.
     */
    private static List<Element> selectLangSets(Node termEntry) {
        List<Element> selected = new ArrayList<>();
        NodeList children = termEntry.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node langSet = children.item(i);
            if (langSet.getNodeType() != Node.ELEMENT_NODE || !langSet.getNodeName().equals("langSet")) {
                continue;
            }
            NamedNodeMap attrs = langSet.getAttributes();
            if (attrs == null) continue;
            Node langAttr = attrs.getNamedItem("xml:lang");
            if (langAttr == null) langAttr = attrs.getNamedItem("lang");
            if (langAttr == null || langAttr.getNodeValue() == null) continue;
            // 5.3 fix: structural check only – langSet must contain a term element.
            if (!hasTermElement((Element) langSet)) continue;
            selected.add((Element) langSet);
        }
        return selected;
    }

    /** Check whether a langSet structurally contains a tig/term or ntig/termGrp/term element. */
    private static boolean hasTermElement(Element langSet) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName();
            if ("tig".equals(name) && firstElementChild(child, "term") != null) return true;
            if ("ntig".equals(name)) {
                Node termGrp = firstElementChild(child, "termGrp");
                if (termGrp != null && firstElementChild(termGrp, "term") != null) return true;
            }
        }
        return false;
    }

    /**
     * Source and target text of a termEntry node, read exactly as loadTerms reads them (same
     * langSet selection, same blank-to-null rule), so a load-time and a save-time value are
     * comparable. Null for a node without any langSet. The NUL separator cannot occur in XML
     * text, so two different pairs never produce the same string.
     */
    private static String nodeFingerprint(Element termEntry, LangSelection selection) {
        LangPair pair = pairOf(termEntry, selection);
        if (pair.source == null && pair.target == null) return null;
        String source = pair.source == null ? null : getTermTextOrNull(pair.source);
        String target = pair.target == null ? null : getTermTextOrNull(pair.target);
        return (source == null ? "" : source) + "\u0000" + (target == null ? "" : target);
    }

    /**
     * 5.2: A termEntry is loadable if it has at least one langSet with a non-blank term text.
     * Used during load to decide whether to produce a TermEntry, and during save to decide
     * whether a node on disk is "eligible for claiming". Non-loadable nodes are left untouched.
     * With a language pair selected, only the two chosen langSets count: an entry that has the
     * other languages but neither of these is not part of this pair's termbase.
     */
    static boolean isLoadable(Element termEntry) {
        return isLoadable(termEntry, null);
    }

    static boolean isLoadable(Element termEntry, LangSelection selection) {
        if (selection == null) {
            // Default pair: any langSet with text makes the node an entry, as it always has.
            for (Element ls : selectLangSets(termEntry)) {
                if (getTermTextOrNull(ls) != null) return true;
            }
            return false;
        }
        LangPair pair = pairOf(termEntry, selection);
        return (pair.source != null && getTermTextOrNull(pair.source) != null)
            || (pair.target != null && getTermTextOrNull(pair.target) != null);
    }

    /** The langSets one termEntry offers for the source and the target side; either may be null. */
    private static final class LangPair {
        final Element source;
        final Element target;

        LangPair(Element source, Element target) {
            this.source = source;
            this.target = target;
        }
    }

    /**
     * The source and target langSets of a termEntry. Without a selection that is the first and the
     * second langSet that carries a term (the long-standing default); with one it is the first
     * langSet in each chosen language.
     */
    private static LangPair pairOf(Node termEntry, LangSelection selection) {
        List<Element> all = selectLangSets(termEntry);
        if (selection == null) {
            return new LangPair(all.isEmpty() ? null : all.get(0), all.size() > 1 ? all.get(1) : null);
        }
        Element source = null;
        Element target = null;
        for (Element ls : all) {
            String lang = langOf(ls);
            if (source == null && selection.source.equalsIgnoreCase(lang)) {
                source = ls;
            } else if (target == null && selection.target.equalsIgnoreCase(lang)) {
                target = ls;
            }
        }
        return new LangPair(source, target);
    }

    /**
     * The selection to use for this file, or null for the default pair. A selection holds only
     * when the file offers both chosen languages; otherwise the default applies and the config is
     * flagged so the UI can say so. Also refreshes the config's list of available languages. The
     * returned languages are spelled as the file spells them.
     */
    private static LangSelection resolveSelection(Document doc, TermbaseConfig config) {
        List<String> available = new ArrayList<>();
        NodeList entries = doc.getElementsByTagName("termEntry");
        for (int i = 0; i < entries.getLength(); i++) {
            for (Element ls : selectLangSets(entries.item(i))) {
                String lang = langOf(ls);
                if (lang == null || lang.trim().isEmpty()) continue;
                boolean known = false;
                for (String a : available) {
                    if (a.equalsIgnoreCase(lang)) {
                        known = true;
                        break;
                    }
                }
                if (!known) available.add(lang);
            }
        }
        config.setAvailableLangs(available);
        config.setSelectionFallback(false);
        if (!config.hasSelectedLangs()) {
            return null;
        }
        String src = null;
        String tgt = null;
        for (String a : available) {
            if (a.equalsIgnoreCase(config.getSelectedSourceLang())) src = a;
            if (a.equalsIgnoreCase(config.getSelectedTargetLang())) tgt = a;
        }
        if (src == null || tgt == null || src.equalsIgnoreCase(tgt)) {
            config.setSelectionFallback(true);
            return null;
        }
        return new LangSelection(src, tgt);
    }

    /** A chosen language pair, spelled as the TBX file spells it. */
    static final class LangSelection {
        final String source;
        final String target;

        LangSelection(String source, String target) {
            this.source = source;
            this.target = target;
        }
    }

    /** Get non-blank term text from a langSet, or null if blank/missing. */
    private static String getTermTextOrNull(Element langSet) {
        String t = getTermText(langSet);
        return (t != null && !t.trim().isEmpty()) ? t.trim() : null;
    }

    private static String langOf(Element langSet) {
        Node attr = langSet.getAttributeNode("xml:lang");
        if (attr == null) attr = langSet.getAttributeNode("lang");
        return attr != null ? attr.getNodeValue() : null;
    }

    /** Set the first term text in the langSet (tig/term or ntig/termGrp/term), as getTermText reads it. */
    private static void setTermText(Node langSet, String text) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = child.getNodeName();
            if ("tig".equals(name)) {
                Element term = firstElementChild(child, "term");
                if (term != null) {
                    term.setTextContent(text != null ? text : "");
                    return;
                }
            } else if ("ntig".equals(name)) {
                Node termGrp = firstElementChild(child, "termGrp");
                if (termGrp != null) {
                    Element term = firstElementChild(termGrp, "term");
                    if (term != null) {
                        term.setTextContent(text != null ? text : "");
                        return;
                    }
                }
            }
        }
    }

    private static Element firstElementChild(Node parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    /** Recursively remove text nodes that are only whitespace; element content is untouched. */
    private static void stripWhitespaceTextNodes(Node element) {
        List<Node> victims = new ArrayList<>();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                stripWhitespaceTextNodes(child);
            } else if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && child.getNodeValue().trim().isEmpty()) {
                victims.add(child);
            }
        }
        for (Node victim : victims) {
            element.removeChild(victim);
        }
    }

    private static String getTermText(Node langSet) {
        NodeList children = langSet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String nodeName = child.getNodeName();
                if ("tig".equals(nodeName)) {
                    String text = getTextFromTig(child);
                    if (text != null) return text;
                } else if ("ntig".equals(nodeName)) {
                    String text = getTextFromNtig(child);
                    if (text != null) return text;
                }
            }
        }
        return null;
    }

    private static String getTextFromTig(Node tig) {
        Element term = firstElementChild(tig, "term");
        if (term == null) return null;
        String text = term.getTextContent();
        return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
    }

    private static String getTextFromNtig(Node ntig) {
        NodeList children = ntig.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "termGrp".equals(child.getNodeName())) {
                Element term = firstElementChild(child, "term");
                if (term != null) {
                    String text = term.getTextContent();
                    if (text != null && !text.trim().isEmpty()) return text.trim();
                }
            }
        }
        return null;
    }
}

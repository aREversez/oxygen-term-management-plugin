package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.TermEntryUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan 7 phase C: undoing a delete in a TBX termbase brings back the whole termEntry (definition,
 * notes, further languages, ntig structures), not just the two terms.
 */
class TbxFullRestoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void freshStash() {
        TbxTermbaseHandler.clearRestoreStash();
    }

    private static final String RICH =
        "    <termEntry id=\"%s\">\n"
        + "      <descrip type=\"definition\">DEF-%s</descrip>\n"
        + "      <note>NOTE-%s</note>\n"
        + "      <langSet xml:lang=\"zh-CN\"><ntig><termGrp><term>%s</term><termNote type=\"partOfSpeech\">noun</termNote></termGrp></ntig></langSet>\n"
        + "      <langSet xml:lang=\"en-US\"><tig><term>%s</term><termNote type=\"administrativeStatus\">admittedTerm-admn-sts</termNote></tig></langSet>\n"
        + "      <langSet xml:lang=\"de-DE\"><tig><term>DE-%s</term></tig></langSet>\n"
        + "    </termEntry>\n";

    private static String rich(String id, String zh, String en) {
        return String.format(RICH, id, id, id, zh, en, id);
    }

    private TermbaseConfig tbx(String... entries) throws Exception {
        Path file = tempDir.resolve("t" + System.nanoTime() + ".tbx");
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + String.join("", entries) + "  </body>\n</martif>");
        return new TermbaseConfig(file.toString(), Format.TBX, true);
    }

    private static Document parse(TermbaseConfig c) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(c.getFilePath()));
    }

    /** Document-order ids of the termEntry nodes. */
    private static List<String> ids(TermbaseConfig c) throws Exception {
        List<String> ids = new ArrayList<>();
        NodeList nl = parse(c).getElementsByTagName("termEntry");
        for (int i = 0; i < nl.getLength(); i++) ids.add(((Element) nl.item(i)).getAttribute("id"));
        return ids;
    }

    /** The termEntry with this id as whitespace-free text: comparable across saves. */
    private static String canon(TermbaseConfig c, String id) throws Exception {
        NodeList nl = parse(c).getElementsByTagName("termEntry");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            if (e.getAttribute("id").equals(id)) return canon(e);
        }
        return null;
    }

    private static String canon(Node n) {
        if (n.getNodeType() == Node.TEXT_NODE) return n.getNodeValue().trim();
        if (n.getNodeType() != Node.ELEMENT_NODE) return "";
        StringBuilder sb = new StringBuilder("<" + n.getNodeName());
        for (int i = 0; i < n.getAttributes().getLength(); i++) {
            sb.append(' ').append(n.getAttributes().item(i).getNodeName()).append('=')
              .append(n.getAttributes().item(i).getNodeValue());
        }
        sb.append('>');
        for (Node ch = n.getFirstChild(); ch != null; ch = ch.getNextSibling()) sb.append(canon(ch));
        return sb.append("</").append(n.getNodeName()).append('>').toString();
    }

    private static List<TermEntry> deleteRecording(TermbaseConfig c, int... indexes) {
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        List<TermEntry> victims = new ArrayList<>();
        for (int i : indexes) victims.add(terms.get(i));
        // record + remove exactly as the panel does, save, and hand back the recorded entries
        List<TermEntryUtils.RemovedEntry> removed = TermEntryUtils.removeEntriesRecording(terms, victims);
        TbxTermbaseHandler.saveTerms(c, terms);
        lastRemoved = removed;
        return terms;
    }

    private static List<TermEntryUtils.RemovedEntry> lastRemoved;

    private static void undo(TermbaseConfig c) {
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        TermEntryUtils.reinsertRemoved(terms, lastRemoved);
        TbxTermbaseHandler.saveTerms(c, terms);
    }

    @Test
    void undo_restoresTheWholeEntry_definitionNoteThirdLanguageAndNtigIncluded() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"), rich("c", "载荷", "load"));
        String before = canon(c, "b");
        deleteRecording(c, 1);
        assertNull(canon(c, "b"));
        undo(c);
        assertEquals(before, canon(c, "b"), "restored node must equal the deleted one");
    }

    @Test
    void undo_restoresAtTheOldPosition_notAtTheEnd() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"), rich("c", "载荷", "load"));
        deleteRecording(c, 1);
        undo(c);
        assertEquals(List.of("a", "b", "c"), ids(c));
    }

    @Test
    void undo_ofSeveralNonAdjacentEntries_keepsTheirOrder() throws Exception {
        TermbaseConfig c = tbx(rich("a", "1", "one"), rich("b", "2", "two"), rich("c", "3", "three"),
                               rich("d", "4", "four"), rich("e", "5", "five"));
        String b = canon(c, "b");
        String d = canon(c, "d");
        deleteRecording(c, 1, 3);
        undo(c);
        assertEquals(List.of("a", "b", "c", "d", "e"), ids(c));
        assertEquals(b, canon(c, "b"));
        assertEquals(d, canon(c, "d"));
    }

    @Test
    void undo_ofTheFirstEntry_goesBackToTheFront() throws Exception {
        TermbaseConfig c = tbx(rich("a", "1", "one"), rich("b", "2", "two"));
        deleteRecording(c, 0);
        undo(c);
        assertEquals(List.of("a", "b"), ids(c));
    }

    @Test
    void undo_whenTheIdWasTakenInTheMeantime_usesAFreshIdAndKeepsBothEntries() throws Exception {
        TermbaseConfig c = tbx(rich("tid1", "1", "one"), rich("tid2", "2", "two"), rich("tid3", "3", "three"));
        deleteRecording(c, 1);
        // The user adds a new entry; the writer hands it the first free id, which is tid2.
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        terms.add(new TermEntry("新", "new"));
        TbxTermbaseHandler.saveTerms(c, terms);
        assertTrue(ids(c).contains("tid2"));

        undo(c);
        List<String> ids = ids(c);
        assertEquals(4, ids.size());
        assertEquals(4, new java.util.HashSet<>(ids).size(), "ids must stay unique: " + ids);
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertTrue(saved.contains("DEF-tid2"), "restored definition present");
        assertTrue(saved.contains("<term>new</term>"), "the newly added entry is untouched");
    }

    @Test
    void undo_whenTheStashIsGone_fallsBackToTheTermsOnly() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"));
        deleteRecording(c, 1);
        TbxTermbaseHandler.clearRestoreStash();   // evicted, or the plugin was restarted
        undo(c);
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertTrue(saved.contains("<term>stress</term>"));
        assertFalse(saved.contains("DEF-b"), "no stash, so only the terms come back");
        assertEquals(2, ids(c).size());
    }

    @Test
    void undoThenDeleteThenUndo_stillRestoresEverything() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"));
        String before = canon(c, "b");
        deleteRecording(c, 1);
        undo(c);
        deleteRecording(c, 1);
        assertNull(canon(c, "b"));
        undo(c);
        assertEquals(before, canon(c, "b"));
    }

    @Test
    void undo_afterTheRestoredEntryWasEditedBeforeSaving_keepsTheEditAndTheRest() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"));
        deleteRecording(c, 1);
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        TermEntryUtils.reinsertRemoved(terms, lastRemoved);
        terms.get(1).setTargetTerm("strain");
        TbxTermbaseHandler.saveTerms(c, terms);
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertTrue(saved.contains("<term>strain</term>"));
        assertFalse(saved.contains("<term>stress</term>"));
        assertTrue(saved.contains("DEF-b"));
        assertTrue(saved.contains("DE-b"));
    }

    @Test
    void stash_keepsOnlyTheMostRecentTwoHundredPerFile() throws Exception {
        String[] entries = new String[205];
        for (int i = 0; i < entries.length; i++) entries[i] = rich("e" + i, "z" + i, "t" + i);
        TermbaseConfig c = tbx(entries);
        int[] all = new int[205];
        for (int i = 0; i < all.length; i++) all[i] = i;
        deleteRecording(c, all);
        undo(c);
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertEquals(205, ids(c).size());
        // the oldest five were evicted: terms only; the rest are complete
        assertFalse(saved.contains("DEF-e0<"));
        assertTrue(saved.contains("DEF-e204<"));
        assertTrue(saved.contains("DEF-e5<"));
    }

    @Test
    void stash_isNotUsedAfterTheLanguagePairChanged() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"));
        deleteRecording(c, 1);
        c.setSelectedLangs("en-US", "de-DE");
        undo(c);
        assertFalse(Files.readString(Path.of(c.getFilePath())).contains("DEF-b"));
    }

    @Test
    void entriesWithoutIds_areRestoredByFingerprintAndOrdinal() throws Exception {
        String noId =
            "    <termEntry>\n      <descrip type=\"definition\">DEF-X</descrip>\n"
            + "      <langSet xml:lang=\"zh-CN\"><tig><term>甲</term></tig></langSet>\n"
            + "      <langSet xml:lang=\"en-US\"><tig><term>alpha</term></tig></langSet>\n    </termEntry>\n";
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), noId, rich("c", "载荷", "load"));
        deleteRecording(c, 1);
        undo(c);
        assertTrue(Files.readString(Path.of(c.getFilePath())).contains("DEF-X"));
    }

    @Test
    void aSaveThatRestoresNothing_leavesTheStashAlone() throws Exception {
        TermbaseConfig c = tbx(rich("a", "网格", "mesh"), rich("b", "应力", "stress"));
        deleteRecording(c, 1);
        TbxTermbaseHandler.StashProbe before = TbxTermbaseHandler.stashProbe(c.getFilePath());
        assertEquals(1, before.size());
        // saving the same list again (no restore) must leave the stash alone
        TbxTermbaseHandler.saveTerms(c, TbxTermbaseHandler.loadTerms(c));
        assertEquals(1, TbxTermbaseHandler.stashProbe(c.getFilePath()).size());
    }
}

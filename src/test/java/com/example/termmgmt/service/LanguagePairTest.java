package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-termbase language pair (plan 7, phase B): CSV and TBX. The default (no selection) must be
 * exactly what the plugin always did; a selection must read and write the chosen columns /
 * langSets and leave everything else where it was.
 */
class LanguagePairTest {

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------ CSV

    private static final String THREE_LANG_CSV =
        "zh-cn,en-us,de-de,note\n"
        + "网格,mesh,Netz,n1\n"
        + "应力,stress,Spannung,n2\n"
        + "载荷,load,,n3\n";

    private TermbaseConfig csv(String content) throws Exception {
        Path file = tempDir.resolve("t" + System.nanoTime() + ".csv");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return new TermbaseConfig(file.toString(), Format.CSV, true);
    }

    private static String read(TermbaseConfig c) throws Exception {
        return new String(Files.readAllBytes(Path.of(c.getFilePath())), StandardCharsets.UTF_8);
    }

    @Test
    void csv_noSelection_isTheFirstTwoColumns_andListsEveryLanguage() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals("网格", terms.get(0).getSourceTerm());
        assertEquals("mesh", terms.get(0).getTargetTerm());
        assertEquals(Arrays.asList("de-de", "note"), c.getExtraColumns());
        assertEquals(Arrays.asList("zh-cn", "en-us", "de-de", "note"), c.getAvailableLangs());
        assertFalse(c.isSelectionFallback());
    }

    @Test
    void csv_noSelection_noopSaveIsByteIdenticalToThePreviousFormat() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        CsvTermbaseHandler.saveTerms(c, CsvTermbaseHandler.loadTerms(c));
        String once = read(c);
        // The writer adds a BOM and quotes; a second pass must not change anything further.
        CsvTermbaseHandler.saveTerms(c, CsvTermbaseHandler.loadTerms(c));
        assertEquals(once, read(c));
        assertTrue(once.startsWith("﻿\"zh-cn\",\"en-us\",\"de-de\",\"note\""));
    }

    @Test
    void csv_selectEnglishToGerman_readsThoseColumns() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("en-us", "de-de");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals("mesh", terms.get(0).getSourceTerm());
        assertEquals("Netz", terms.get(0).getTargetTerm());
        assertEquals("en-us", c.getSourceLang());
        assertEquals("de-de", c.getTargetLang());
        assertEquals(Arrays.asList("zh-cn", "note"), c.getExtraColumns());
        assertEquals("网格", terms.get(0).getExtraFields().get("zh-cn"));
        assertEquals("n1", terms.get(0).getExtraFields().get("note"));
    }

    @Test
    void csv_selectionIsCaseInsensitiveAndTrimmed() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs(" EN-US ", "De-De");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals("mesh", terms.get(0).getSourceTerm());
        assertFalse(c.isSelectionFallback());
    }

    @Test
    void csv_reversedSelection_sourceIsTheLaterColumn() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("de-de", "zh-cn");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals("Netz", terms.get(0).getSourceTerm());
        assertEquals("网格", terms.get(0).getTargetTerm());
        assertEquals(Arrays.asList("en-us", "note"), c.getExtraColumns());
    }

    @Test
    void csv_selection_editWritesBackIntoTheSameColumns_othersStayPut() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("de-de", "zh-cn");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        terms.get(0).setSourceTerm("Gitter");
        terms.get(1).setTargetTerm("应变");
        CsvTermbaseHandler.saveTerms(c, terms);

        String[] lines = read(c).replace("﻿", "").split("\n");
        assertEquals("\"zh-cn\",\"en-us\",\"de-de\",\"note\"", lines[0], "header order unchanged");
        assertEquals("\"网格\",\"mesh\",\"Gitter\",\"n1\"", lines[1]);
        assertEquals("\"应变\",\"stress\",\"Spannung\",\"n2\"", lines[2]);
        assertEquals("\"载荷\",\"load\",\"\",\"n3\"", lines[3]);
    }

    @Test
    void csv_selection_noopSaveKeepsTheFileLayout() throws Exception {
        TermbaseConfig plain = csv(THREE_LANG_CSV);
        CsvTermbaseHandler.saveTerms(plain, CsvTermbaseHandler.loadTerms(plain));
        String baseline = read(plain);

        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("de-de", "en-us");
        CsvTermbaseHandler.saveTerms(c, CsvTermbaseHandler.loadTerms(c));
        assertEquals(baseline, read(c));
    }

    @Test
    void csv_selection_newEntryFillsTheChosenColumns() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("en-us", "de-de");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        terms.add(new TermEntry("node", "Knoten"));
        CsvTermbaseHandler.saveTerms(c, terms);
        String[] lines = read(c).replace("﻿", "").split("\n");
        assertEquals("\"\",\"node\",\"Knoten\",\"\"", lines[4]);
    }

    @Test
    void csv_statusColumnFollowsTheRow_whateverThePair() throws Exception {
        TermbaseConfig c = csv("zh-cn,en-us,de-de,status\n网格,mesh,Netz,deprecated\n应力,stress,Spannung,\n");
        c.setSelectedLangs("de-de", "en-us");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals(com.example.termmgmt.model.TermStatus.DEPRECATED, terms.get(0).getStatus());
        assertNull(terms.get(1).getStatus());
        assertFalse(c.getAvailableLangs().contains("status"), "status is not a language");
        CsvTermbaseHandler.saveTerms(c, terms);
        assertTrue(read(c).contains("\"deprecated\""));
    }

    @Test
    void csv_statusCannotBeChosenAsALanguage() throws Exception {
        TermbaseConfig c = csv("zh-cn,en-us,status\n网格,mesh,deprecated\n");
        c.setSelectedLangs("status", "zh-cn");
        CsvTermbaseHandler.loadTerms(c);
        assertTrue(c.isSelectionFallback());
        assertEquals("zh-cn", c.getSourceLang());
    }

    @Test
    void csv_missingLanguage_fallsBackToDefaultAndSaysSo() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("fr-fr", "en-us");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertTrue(c.isSelectionFallback());
        assertEquals("网格", terms.get(0).getSourceTerm());
        assertEquals("mesh", terms.get(0).getTargetTerm());
        assertEquals("zh-cn", c.getSourceLang());
        // Saving in fallback mode writes the default layout, losing nothing.
        CsvTermbaseHandler.saveTerms(c, terms);
        assertTrue(read(c).contains("\"Netz\""));
        assertTrue(read(c).replace("﻿", "").startsWith("\"zh-cn\",\"en-us\",\"de-de\",\"note\""));
    }

    @Test
    void csv_sameLanguageTwice_fallsBack() throws Exception {
        TermbaseConfig c = csv(THREE_LANG_CSV);
        c.setSelectedLangs("en-us", "EN-US");
        CsvTermbaseHandler.loadTerms(c);
        assertTrue(c.isSelectionFallback());
    }

    @Test
    void csv_rowsBlankInTheChosenColumnsAreNotEntries() throws Exception {
        TermbaseConfig c = csv("zh-cn,en-us,de-de\n网格,mesh,\n应力,stress,Spannung\n");
        c.setSelectedLangs("en-us", "de-de");
        List<TermEntry> terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals(2, terms.size(), "mesh has a source term even though the target is blank");
        c.setSelectedLangs("de-de", "zh-cn");
        terms = CsvTermbaseHandler.loadTerms(c);
        assertEquals(2, terms.size());
    }

    @Test
    void csv_selectionNeedsBothNames() {
        TermbaseConfig c = new TermbaseConfig("x.csv", Format.CSV, true);
        c.setSelectedLangs("en-us", null);
        assertFalse(c.hasSelectedLangs());
        c.setSelectedLangs("en-us", "de-de");
        assertTrue(c.hasSelectedLangs());
        c.setSelectedLangs("", "de-de");
        assertFalse(c.hasSelectedLangs());
        assertNull(c.getSelectedSourceLang());
    }

    // ------------------------------------------------------------------ TBX

    private static String entry(String id, String zh, String en, String de) {
        StringBuilder sb = new StringBuilder("    <termEntry id=\"" + id + "\">\n");
        if (zh != null) sb.append("      <langSet xml:lang=\"zh-CN\"><tig><term>").append(zh).append("</term></tig></langSet>\n");
        if (en != null) sb.append("      <langSet xml:lang=\"en-US\"><tig><term>").append(en).append("</term></tig></langSet>\n");
        if (de != null) sb.append("      <langSet xml:lang=\"de-DE\"><tig><term>").append(de).append("</term></tig></langSet>\n");
        return sb.append("    </termEntry>\n").toString();
    }

    private TermbaseConfig tbx(String... entries) throws Exception {
        Path file = tempDir.resolve("t" + System.nanoTime() + ".tbx");
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<martif type=\"TBX\">\n  <body>\n"
            + String.join("", entries) + "  </body>\n</martif>");
        return new TermbaseConfig(file.toString(), Format.TBX, true);
    }

    @Test
    void tbx_noSelection_isTheFirstTwoLangSets_andListsEveryLanguage() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"));
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertEquals("网格", terms.get(0).getSourceTerm());
        assertEquals("mesh", terms.get(0).getTargetTerm());
        assertEquals(Arrays.asList("zh-CN", "en-US", "de-DE"), c.getAvailableLangs());
        assertFalse(c.isSelectionFallback());
    }

    @Test
    void tbx_selectEnglishToGerman() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"));
        c.setSelectedLangs("en-us", "de-de");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertEquals("mesh", terms.get(0).getSourceTerm());
        assertEquals("Netz", terms.get(0).getTargetTerm());
        assertEquals("en-US", c.getSourceLang(), "spelled as the file spells it");
        assertEquals("de-DE", c.getTargetLang());
    }

    @Test
    void tbx_selection_editChangesOnlyTheChosenLangSets() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"));
        c.setSelectedLangs("de-DE", "en-US");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        terms.get(0).setSourceTerm("Gitter");
        TbxTermbaseHandler.saveTerms(c, terms);
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertTrue(saved.contains("<term>Gitter</term>"));
        assertFalse(saved.contains("Netz"));
        assertTrue(saved.contains("<term>网格</term>"));
        assertTrue(saved.contains("<term>mesh</term>"));
        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(c);
        assertEquals("Gitter", reloaded.get(0).getSourceTerm());
        assertEquals("mesh", reloaded.get(0).getTargetTerm());
    }

    @Test
    void tbx_selection_noopSaveIsStableAndAddsNothing() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"), entry("b", "应力", "stress", null),
                               entry("c", "载荷", null, null));
        c.setSelectedLangs("en-US", "de-DE");
        TbxTermbaseHandler.saveTerms(c, TbxTermbaseHandler.loadTerms(c));
        String first = Files.readString(Path.of(c.getFilePath()));
        TbxTermbaseHandler.saveTerms(c, TbxTermbaseHandler.loadTerms(c));
        assertEquals(first, Files.readString(Path.of(c.getFilePath())));
        assertEquals(3, count(first, "<termEntry"));
        assertEquals(1, count(first, "de-DE"), "entry b must not gain an empty German langSet");
        assertTrue(first.contains("载荷"), "entry c has neither chosen language and must survive untouched");
    }

    @Test
    void tbx_selection_entriesWithNeitherChosenLanguageAreNotLoadedButSurviveASave() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"), entry("b", "应力", null, null));
        c.setSelectedLangs("en-US", "de-DE");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertEquals(1, terms.size());
        terms.clear();
        TbxTermbaseHandler.saveTerms(c, terms); // user deleted the only visible entry
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertFalse(saved.contains("mesh"));
        assertTrue(saved.contains("应力"));
    }

    @Test
    void tbx_selection_missingTargetIsAddedOnlyWhenTheUserTypesOne() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"), entry("b", "应力", "stress", null));
        c.setSelectedLangs("en-US", "de-DE");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertNull(terms.get(1).getTargetTerm());
        terms.get(1).setTargetTerm("Spannung");
        TbxTermbaseHandler.saveTerms(c, terms);
        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(c);
        assertEquals("Spannung", reloaded.get(1).getTargetTerm());
        assertEquals(2, count(Files.readString(Path.of(c.getFilePath())), "xml:lang=\"de-DE\""));
    }

    @Test
    void tbx_selection_missingSourceIsAddedOnlyWhenTheUserTypesOne() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"), entry("b", "应力", null, "Spannung"));
        c.setSelectedLangs("en-US", "de-DE");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertNull(terms.get(1).getSourceTerm());
        assertEquals("Spannung", terms.get(1).getTargetTerm());
        TbxTermbaseHandler.saveTerms(c, terms);
        assertEquals(1, count(Files.readString(Path.of(c.getFilePath())), "xml:lang=\"en-US\""),
            "an unedited entry gains no empty source langSet");
        terms.get(1).setSourceTerm("stress");
        TbxTermbaseHandler.saveTerms(c, terms);
        assertEquals("stress", TbxTermbaseHandler.loadTerms(c).get(1).getSourceTerm());
    }

    @Test
    void tbx_selection_newEntryUsesTheChosenLanguages() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"));
        c.setSelectedLangs("en-US", "de-DE");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        terms.add(new TermEntry("node", "Knoten"));
        TbxTermbaseHandler.saveTerms(c, terms);
        List<TermEntry> reloaded = TbxTermbaseHandler.loadTerms(c);
        assertEquals(2, reloaded.size());
        assertEquals("node", reloaded.get(1).getSourceTerm());
        assertEquals("Knoten", reloaded.get(1).getTargetTerm());
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertEquals(2, count(saved, "xml:lang=\"en-US\""));
        assertEquals(0, count(saved, "xml:lang=\"zh-CN\"") - 1);
    }

    @Test
    void tbx_missingLanguage_fallsBackToDefaultAndSaysSo() throws Exception {
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"));
        c.setSelectedLangs("fr-FR", "en-US");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertTrue(c.isSelectionFallback());
        assertEquals("网格", terms.get(0).getSourceTerm());
        assertEquals("mesh", terms.get(0).getTargetTerm());
    }

    @Test
    void tbx_default_entryWithBlankFirstTwoLangSetsStillLoadsOnAThird() throws Exception {
        // Long-standing behaviour: any langSet with text makes the node an entry.
        TermbaseConfig c = tbx(entry("a", "", "", "Netz"));
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        assertEquals(1, terms.size());
    }

    @Test
    void tbx_selection_switchingThePairKeepsEveryEntryClaimable() throws Exception {
        // Load under one pair, switch, load again, edit, save: ids keep matching their nodes.
        TermbaseConfig c = tbx(entry("a", "网格", "mesh", "Netz"), entry("b", "应力", "stress", "Spannung"));
        TbxTermbaseHandler.loadTerms(c);
        c.setSelectedLangs("en-US", "de-DE");
        List<TermEntry> terms = TbxTermbaseHandler.loadTerms(c);
        terms.get(1).setTargetTerm("Belastung");
        TbxTermbaseHandler.saveTerms(c, terms);
        String saved = Files.readString(Path.of(c.getFilePath()));
        assertEquals(2, count(saved, "<termEntry"));
        assertTrue(saved.contains("Belastung"));
        assertFalse(saved.contains("Spannung"));
        assertTrue(saved.contains("应力"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}

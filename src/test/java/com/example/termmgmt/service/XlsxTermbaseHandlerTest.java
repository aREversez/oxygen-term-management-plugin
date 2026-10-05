package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XlsxTermbaseHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoad_shouldPreserveTerms() throws Exception {
        Path file = tempDir.resolve("test.xlsx");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");

        List<TermEntry> toSave = List.of(
            new TermEntry("你好", "hello"),
            new TermEntry("世界", "world")
        );
        XlsxTermbaseHandler.saveTerms(config, toSave);

        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
        assertEquals("世界", loaded.get(1).getSourceTerm());
        assertEquals("world", loaded.get(1).getTargetTerm());
    }

    @Test
    void loadTerms_shouldHandleNumericHeaderCellGracefully() throws Exception {
        Path file = tempDir.resolve("numeric_header.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue(123.0);
            header.createCell(1).setCellValue(456.0);

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("你好");
            row1.createCell(1).setCellValue("hello");
            wb.write(fos);
        }

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("你好", loaded.get(0).getSourceTerm());
    }

    @Test
    void loadTerms_shouldReturnEmptyListForHeaderOnly() throws Exception {
        Path file = tempDir.resolve("empty.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("zh-cn");
            header.createCell(1).setCellValue("en-us");
            wb.write(fos);
        }

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadTerms_shouldConvertNumericCellCorrectly() throws Exception {
        Path file = tempDir.resolve("numeric_cell.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("zh-cn");
            header.createCell(1).setCellValue("en-us");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue(2.4);
            row1.createCell(1).setCellValue("hello");
            wb.write(fos);
        }

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("2.4", loaded.get(0).getSourceTerm());
        assertEquals("hello", loaded.get(0).getTargetTerm());
    }

    @Test
    void loadTerms_shouldThrowWhenFileNotExists() {
        TermbaseConfig config = new TermbaseConfig(
            tempDir.resolve("nonexistent.xlsx").toString(), Format.XLSX, true);
        assertThrows(RuntimeException.class, () -> XlsxTermbaseHandler.loadTerms(config));
    }

    @Test
    void loadTerms_shouldReadIntegerAndFormulaCellsAsDisplayedText() throws Exception {
        Path file = tempDir.resolve("integer_and_formula.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("zh-cn");
            header.createCell(1).setCellValue("en-us");

            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue(4052);
            row1.createCell(1).setCellFormula("\"ab\"&\"cd\"");
            wb.write(fos);
        }

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("4052", loaded.get(0).getSourceTerm());
        assertEquals("abcd", loaded.get(0).getTargetTerm());
    }

    @Test
    void loadTerms_shouldReadExtraColumnsIntoConfigAndEntries() throws Exception {
        Path file = tempDir.resolve("extra.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("zh-cn");
            header.createCell(1).setCellValue("en-us");
            header.createCell(2).setCellValue("domain");
            header.createCell(3).setCellValue("note");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("刚度");
            row1.createCell(1).setCellValue("stiffness");
            row1.createCell(2).setCellValue("mechanics");
            row1.createCell(3).setCellValue("NBR note");
            Row row2 = sheet.createRow(2);
            row2.createCell(0).setCellValue("网格");
            row2.createCell(1).setCellValue("mesh");
            // row2 has no cells beyond column B.
            wb.write(fos);
        }

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);

        assertEquals(List.of("domain", "note"), config.getExtraColumns());
        assertEquals(2, loaded.size());
        assertEquals(Map.of("domain", "mechanics", "note", "NBR note"), loaded.get(0).getExtraFields());
        // Missing cells come back as empty strings, keys still in header order.
        assertEquals(Map.of("domain", "", "note", ""), loaded.get(1).getExtraFields());
        assertEquals(List.of("domain", "note"), List.copyOf(loaded.get(1).getExtraFields().keySet()));
    }

    @Test
    void saveTerms_shouldKeepOtherSheetsHeadersColumnWidthsAndExtraColumns() throws Exception {
        Path file = tempDir.resolve("multi_sheet.xlsx");
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            sheet.setColumnWidth(0, 7000);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("zh-cn");
            header.createCell(1).setCellValue("en-us");
            header.createCell(2).setCellValue("domain");
            Row row1 = sheet.createRow(1);
            row1.createCell(0).setCellValue("刚度");
            row1.createCell(1).setCellValue("stiffness");
            row1.createCell(2).setCellValue("mechanics");
            Sheet other = wb.createSheet("Notes");
            other.createRow(0).createCell(0).setCellValue("keep me");
            wb.write(fos);
        }

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        XlsxTermbaseHandler.saveTerms(config, loaded);

        try (FileInputStream fis = new FileInputStream(file.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            assertEquals(2, wb.getNumberOfSheets(), "other sheets must survive a save");
            assertEquals("keep me", wb.getSheet("Notes").getRow(0).getCell(0).getStringCellValue());
            Sheet terms = wb.getSheetAt(0);
            assertEquals(7000, terms.getColumnWidth(0), "column widths must survive a save");
            assertEquals("domain", terms.getRow(0).getCell(2).getStringCellValue());
            assertEquals("mechanics", terms.getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void saveAndLoad_shouldPreserveExtraColumnsAndValues() {
        Path file = tempDir.resolve("roundtrip.xlsx");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        config.setExtraColumns(List.of("domain", "note"));

        TermEntry withExtras = new TermEntry("刚度", "stiffness");
        withExtras.getExtraFields().put("domain", "mechanics");
        withExtras.getExtraFields().put("note", "NBR term");
        XlsxTermbaseHandler.saveTerms(config, List.of(withExtras, new TermEntry("网格", "mesh")));

        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals(2, loaded.size());
        assertEquals(List.of("domain", "note"), config.getExtraColumns());
        assertEquals("mechanics", loaded.get(0).getExtraFields().get("domain"));
        assertEquals("NBR term", loaded.get(0).getExtraFields().get("note"));
        assertEquals("", loaded.get(1).getExtraFields().get("note"));
    }

    @Test
    void saveTerms_shouldNotAddExtraHeaderWhenNoneExist() throws Exception {
        Path file = tempDir.resolve("plain.xlsx");
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");
        XlsxTermbaseHandler.saveTerms(config, List.of(new TermEntry("a", "b")));

        try (FileInputStream fis = new FileInputStream(file.toFile());
             Workbook wb = new XSSFWorkbook(fis)) {
            Sheet terms = wb.getSheetAt(0);
            assertEquals(1, wb.getNumberOfSheets());
            assertTrue(terms.getRow(0).getLastCellNum() <= 2, "two-column files must not gain a column");
        }
        assertTrue(XlsxTermbaseHandler.loadTerms(config).get(0).getExtraFields().isEmpty());
    }

    /**
     * 5.4: If an existing non-empty XLSX file cannot be parsed, saveTerms must throw and
     * the file must remain unchanged (original bug: silently overwrote with a new workbook).
     */
    @Test
    void saveTerms_corruptExistingFile_throwsAndPreservesOriginalBytes() throws Exception {
        Path file = tempDir.resolve("corrupt.xlsx");
        byte[] garbage = new byte[]{0x50, 0x4B, 0x03, 0x04, (byte)0xFF, (byte)0xFE, 0x00, 0x00};
        Files.write(file, garbage);

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");

        assertThrows(Exception.class, () ->
            XlsxTermbaseHandler.saveTerms(config, List.of(new TermEntry("a", "b"))));

        // Original bytes must be untouched
        assertArrayEquals(garbage, Files.readAllBytes(file),
            "a corrupt XLSX must not be silently overwritten");
    }

    /**
     * 5.4: A 0-byte file (the new-termbase flow creates one before the first save) is not
     * "existing content": saveTerms must build a fresh workbook instead of throwing.
     */
    @Test
    void saveTerms_zeroSizeExistingFile_createsNewWorkbook() throws Exception {
        Path file = tempDir.resolve("zero.xlsx");
        Files.write(file, new byte[0]);

        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSourceLang("zh-cn");
        config.setTargetLang("en-us");

        XlsxTermbaseHandler.saveTerms(config, List.of(new TermEntry("a", "b")));

        List<TermEntry> loaded = XlsxTermbaseHandler.loadTerms(config);
        assertEquals(1, loaded.size());
        assertEquals("a", loaded.get(0).getSourceTerm());
        assertEquals("b", loaded.get(0).getTargetTerm());
    }

    // ---- per-termbase language pair (plan 7, phase B) ----

    private Path threeLanguageSheet() throws Exception {
        Path file = tempDir.resolve("three.xlsx");
        try (Workbook wb = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(file.toFile())) {
            Sheet sheet = wb.createSheet("Terms");
            String[][] rows = {
                {"zh-cn", "en-us", "de-de", "note"},
                {"网格", "mesh", "Netz", "n1"},
                {"应力", "stress", "Spannung", "n2"},
            };
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(fos);
        }
        return file;
    }

    private static String cell(Path file, int r, int c) throws Exception {
        try (FileInputStream in = new FileInputStream(file.toFile()); Workbook wb = new XSSFWorkbook(in)) {
            return wb.getSheetAt(0).getRow(r).getCell(c).getStringCellValue();
        }
    }

    @Test
    void languagePair_default_isTheFirstTwoColumns() throws Exception {
        TermbaseConfig config = new TermbaseConfig(threeLanguageSheet().toString(), Format.XLSX, true);
        List<TermEntry> terms = XlsxTermbaseHandler.loadTerms(config);
        assertEquals("网格", terms.get(0).getSourceTerm());
        assertEquals("mesh", terms.get(0).getTargetTerm());
        // "note" is metadata, not a language: it is no longer offered as a selectable language
        // (defect E), though it still round-trips as an extra column.
        assertEquals(List.of("zh-cn", "en-us", "de-de"), config.getAvailableLangs());
        assertFalse(config.isSelectionFallback());
    }

    @Test
    void languagePair_selected_readsThoseColumnsAndWritesBackInPlace() throws Exception {
        Path file = threeLanguageSheet();
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSelectedLangs("de-de", "zh-cn");
        List<TermEntry> terms = XlsxTermbaseHandler.loadTerms(config);
        assertEquals("Netz", terms.get(0).getSourceTerm());
        assertEquals("网格", terms.get(0).getTargetTerm());
        assertEquals(List.of("en-us", "note"), config.getExtraColumns());

        terms.get(0).setSourceTerm("Gitter");
        XlsxTermbaseHandler.saveTerms(config, terms);
        assertEquals("zh-cn", cell(file, 0, 0));
        assertEquals("en-us", cell(file, 0, 1));
        assertEquals("de-de", cell(file, 0, 2));
        assertEquals("note", cell(file, 0, 3));
        assertEquals("网格", cell(file, 1, 0));
        assertEquals("mesh", cell(file, 1, 1));
        assertEquals("Gitter", cell(file, 1, 2));
        assertEquals("n1", cell(file, 1, 3));
    }

    @Test
    void languagePair_missingLanguage_fallsBackToDefault() throws Exception {
        TermbaseConfig config = new TermbaseConfig(threeLanguageSheet().toString(), Format.XLSX, true);
        config.setSelectedLangs("fr-fr", "en-us");
        List<TermEntry> terms = XlsxTermbaseHandler.loadTerms(config);
        assertTrue(config.isSelectionFallback());
        assertEquals("网格", terms.get(0).getSourceTerm());
    }

    @Test
    void languagePair_statusColumnIsAppendedAfterEveryExistingColumn() throws Exception {
        Path file = threeLanguageSheet();
        TermbaseConfig config = new TermbaseConfig(file.toString(), Format.XLSX, true);
        config.setSelectedLangs("en-us", "de-de");
        List<TermEntry> terms = XlsxTermbaseHandler.loadTerms(config);
        terms.get(0).setStatus(com.example.termmgmt.model.TermStatus.DEPRECATED);
        XlsxTermbaseHandler.saveTerms(config, terms);
        assertEquals("status", cell(file, 0, 4));
        assertEquals("note", cell(file, 0, 3));
        assertEquals("mesh", cell(file, 1, 0 + 1));
    }
}

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
}

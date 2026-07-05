package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

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
}

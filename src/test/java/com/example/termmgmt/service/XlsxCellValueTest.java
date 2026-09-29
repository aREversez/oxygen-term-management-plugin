package com.example.termmgmt.service;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cell-to-text conversion, exercised on in-memory HSSF cells: the conversion only
 * depends on the shared ss.usermodel interfaces, so it behaves the same for XSSF.
 */
class XlsxCellValueTest {

    private Workbook wb;
    private Row row;
    private DataFormatter formatter;
    private FormulaEvaluator evaluator;

    @BeforeEach
    void setUp() {
        wb = new HSSFWorkbook();
        row = wb.createSheet("Terms").createRow(0);
        formatter = new DataFormatter(Locale.ROOT);
        evaluator = wb.getCreationHelper().createFormulaEvaluator();
    }

    @AfterEach
    void tearDown() throws Exception {
        wb.close();
    }

    private String read(int col) {
        return XlsxTermbaseHandler.getCellStringValue(row.getCell(col), formatter, evaluator);
    }

    @Test
    void integerNumericCell_hasNoDecimalPoint() {
        row.createCell(0).setCellValue(4052);
        assertEquals("4052", read(0));
    }

    @Test
    void fractionalNumericCell_keepsFraction() {
        row.createCell(0).setCellValue(2.4);
        assertEquals("2.4", read(0));
    }

    @Test
    void formulaCell_returnsComputedValueNotFormulaText() {
        row.createCell(0).setCellValue(2026);
        row.createCell(1).setCellFormula("A1*2");
        assertEquals("4052", read(1));
    }

    @Test
    void stringFormulaCell_returnsComputedText() {
        row.createCell(0).setCellFormula("CONCATENATE(\"mesh\",\"-\",\"size\")");
        assertEquals("mesh-size", read(0));
    }

    @Test
    void stringCell_isReturnedAsIs() {
        row.createCell(0).setCellValue("你好");
        assertEquals("你好", read(0));
    }

    @Test
    void booleanCell_isLowerCase() {
        row.createCell(0).setCellValue(true);
        assertEquals("true", read(0));
    }

    @Test
    void blankAndMissingCells_areNull() {
        row.createCell(0, CellType.BLANK);
        assertNull(read(0));
        assertNull(read(5));
    }

    @Test
    void decimalSeparatorDoesNotDependOnDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            DataFormatter f = new DataFormatter(Locale.ROOT);
            row.createCell(0).setCellValue(2.4);
            assertEquals("2.4", XlsxTermbaseHandler.getCellStringValue(row.getCell(0), f, evaluator));
        } finally {
            Locale.setDefault(saved);
        }
    }
}

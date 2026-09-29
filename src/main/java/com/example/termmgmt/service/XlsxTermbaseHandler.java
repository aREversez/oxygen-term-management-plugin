package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class XlsxTermbaseHandler {

    public static List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms = new ArrayList<>();
        String filePath = config.getFilePath();

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return terms;

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return terms;

            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int lastCell = headerRow.getLastCellNum();
            if (lastCell < 2) return terms;

            Cell sourceHeaderCell = headerRow.getCell(0);
            Cell targetHeaderCell = headerRow.getCell(1);
            String sourceLang = getCellStringValue(sourceHeaderCell, formatter, evaluator);
            sourceLang = (sourceLang != null && !sourceLang.trim().isEmpty()) ? sourceLang.trim() : "zh-cn";
            String targetLang = getCellStringValue(targetHeaderCell, formatter, evaluator);
            targetLang = (targetLang != null && !targetLang.trim().isEmpty()) ? targetLang.trim() : "en-us";
            config.setSourceLang(sourceLang);
            config.setTargetLang(targetLang);

            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                TermEntry entry = new TermEntry();
                String src = getCellStringValue(row.getCell(0), formatter, evaluator);
                String tgt = getCellStringValue(row.getCell(1), formatter, evaluator);
                entry.setSourceTerm(src != null ? src.trim() : null);
                entry.setTargetTerm(tgt != null ? tgt.trim() : null);
                terms.add(entry);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load XLSX: " + filePath, e);
        }
        return terms;
    }

    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        String filePath = config.getFilePath();

        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("Terms");

            String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-cn";
            String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-us";

            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue(sourceLang);
            headerRow.createCell(1).setCellValue(targetLang);

            for (int i = 0; i < terms.size(); i++) {
                TermEntry entry = terms.get(i);
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(entry.getSourceTerm() != null ? entry.getSourceTerm() : "");
                row.createCell(1).setCellValue(entry.getTargetTerm() != null ? entry.getTargetTerm() : "");
            }

            workbook.write(fos);
            workbook.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save XLSX: " + filePath, e);
        }
    }

    /**
     * Read a cell the way Excel shows it: a numeric 4052 is "4052" (not "4052.0") and a
     * formula yields its computed value (not the formula text). The locale is fixed so the
     * decimal separator does not depend on the operating system.
     *
     * @return the cell text, or null for blank and error cells
     */
    static String getCellStringValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING:
            case NUMERIC:
                return formatter.formatCellValue(cell, evaluator);
            case BOOLEAN:
                return cell.getCellType() == CellType.BOOLEAN
                    ? String.valueOf(cell.getBooleanCellValue())
                    : formatter.formatCellValue(cell, evaluator);
            default:
                return null;
        }
    }
}

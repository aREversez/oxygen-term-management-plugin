package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

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

            int lastCell = headerRow.getLastCellNum();
            if (lastCell < 2) return terms;

            Cell sourceHeaderCell = headerRow.getCell(0);
            Cell targetHeaderCell = headerRow.getCell(1);
            String sourceLang = sourceHeaderCell != null ? sourceHeaderCell.getStringCellValue().trim() : "zh-cn";
            String targetLang = targetHeaderCell != null ? targetHeaderCell.getStringCellValue().trim() : "en-us";
            config.setSourceLang(sourceLang);
            config.setTargetLang(targetLang);

            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                TermEntry entry = new TermEntry();
                entry.setSourceTerm(getCellStringValue(row.getCell(0)));
                entry.setTargetTerm(getCellStringValue(row.getCell(1)));
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

    private static String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:  return cell.getStringCellValue();
            case NUMERIC: return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA: return cell.getCellFormula();
            default:      return null;
        }
    }
}

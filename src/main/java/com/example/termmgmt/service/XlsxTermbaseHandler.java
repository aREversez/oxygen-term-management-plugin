package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.util.AtomicFileWriter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

            // Columns from the third on are not modelled as terms; remember their names and
            // keep each row's values so a save can write them back untouched.
            List<String> extraColumns = new ArrayList<>();
            for (int c = 2; c < lastCell; c++) {
                String name = getCellStringValue(headerRow.getCell(c), formatter, evaluator);
                extraColumns.add(name != null ? name.trim() : "");
            }
            config.setExtraColumns(extraColumns);
            int statusCol = CsvTermbaseHandler.findStatusColumn(extraColumns);

            for (int rowNum = 1; rowNum <= sheet.getLastRowNum(); rowNum++) {
                Row row = sheet.getRow(rowNum);
                if (row == null) continue;

                TermEntry entry = new TermEntry();
                String src = getCellStringValue(row.getCell(0), formatter, evaluator);
                String tgt = getCellStringValue(row.getCell(1), formatter, evaluator);
                entry.setSourceTerm(src != null ? src.trim() : null);
                entry.setTargetTerm(tgt != null ? tgt.trim() : null);
                Map<String, String> extras = new LinkedHashMap<>();
                for (int i = 0; i < extraColumns.size(); i++) {
                    String v = getCellStringValue(row.getCell(i + 2), formatter, evaluator);
                    // Cells missing from a short row come back as empty strings.
                    extras.put(extraColumns.get(i), v != null ? v : "");
                }
                entry.setExtraFields(extras);
                if (statusCol >= 0) {
                    String v = getCellStringValue(row.getCell(statusCol + 2), formatter, evaluator);
                    v = v != null ? v.trim() : "";
                    entry.setStatusRaw(v.isEmpty() ? null : v);
                }
                terms.add(entry);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load XLSX: " + filePath, e);
        }
        return terms;
    }

    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        String filePath = config.getFilePath();

        try {
            // Reopen the original workbook so other sheets, header-row styles and column
            // widths survive; only the first sheet's data rows are rewritten. The original
            // is fully read into memory before anything is written, and the serialized
            // result lands on a temp file that AtomicFileWriter moves over the target, so
            // the file is never read and written at the same time and a half-failed save
            // leaves the previous content intact.
            String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-cn";
            String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-us";
            List<String> extraColumns = new ArrayList<>(config.getExtraColumns());
            // Append "status" column if entries carry a status but no column exists yet.
            boolean appendStatus = CsvTermbaseHandler.findStatusColumn(extraColumns) < 0
                                    && CsvTermbaseHandler.hasStatusColumn(terms);
            if (appendStatus) {
                extraColumns.add(TermEntry.STATUS_FIELD);
            }

            Workbook workbook = null;
            File original = new File(filePath);
            if (original.exists() && original.length() > 0) {
                // 5.4: File exists and has content – must be parseable or we throw.
                try (InputStream is = new FileInputStream(original)) {
                    workbook = new XSSFWorkbook(is);
                } catch (Exception e) {
                    throw new IOException("XLSX: cannot open existing file " + filePath
                        + "; refusing to overwrite it. " + e.getMessage(), e);
                }
            }
            boolean freshWorkbook = workbook == null;
            if (freshWorkbook) {
                workbook = new XSSFWorkbook();
            }
            try {
                Sheet sheet = workbook.getNumberOfSheets() == 0
                    ? workbook.createSheet("Terms")
                    : workbook.getSheetAt(0);

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) {
                    headerRow = sheet.createRow(0);
                }
                setCell(headerRow, 0, sourceLang);
                setCell(headerRow, 1, targetLang);
                for (int i = 0; i < extraColumns.size(); i++) {
                    setCell(headerRow, i + 2, extraColumns.get(i));
                }

                // Drop the old data rows, then write the current list. Per-cell styles on
                // data rows are not preserved (documented limitation).
                for (int r = sheet.getLastRowNum(); r >= 1; r--) {
                    sheet.removeRow(sheet.getRow(r));
                }
                for (int i = 0; i < terms.size(); i++) {
                    TermEntry entry = terms.get(i);
                    Row row = sheet.createRow(i + 1);
                    setCell(row, 0, entry.getSourceTerm() != null ? entry.getSourceTerm() : "");
                    setCell(row, 1, entry.getTargetTerm() != null ? entry.getTargetTerm() : "");
                    for (int c = 0; c < extraColumns.size(); c++) {
                        String colName = extraColumns.get(c);
                        String value;
                        if (TermEntry.STATUS_FIELD.equalsIgnoreCase(colName)) {
                            value = entry.getStoredStatusValue();
                            if (value == null) {
                                value = entry.getExtraFields().get(colName);
                            }
                        } else {
                            value = entry.getExtraFields().get(colName);
                        }
                        setCell(row, c + 2, value != null ? value : "");
                    }
                }

                final Workbook toWrite = workbook;
                AtomicFileWriter.write(Path.of(filePath), out -> toWrite.write(out));
            } finally {
                workbook.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save XLSX: " + filePath, e);
        }
    }

    /** Write a string into the cell, creating it when missing; existing styles stay put. */
    private static void setCell(Row row, int index, String value) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            cell = row.createCell(index);
        }
        cell.setCellValue(value);
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

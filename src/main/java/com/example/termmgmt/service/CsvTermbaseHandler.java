package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.util.AtomicFileWriter;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CsvTermbaseHandler {

    public static List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms = new ArrayList<>();
        String filePath = config.getFilePath();

        try (Reader reader = new BufferedReader(new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            reader.mark(1);
            int firstChar = reader.read();
            if (firstChar != 0xFEFF) {
                reader.reset();
            }
            CSVReader csvReader = new CSVReader(reader);
            String[] headers = csvReader.readNext();
            if (headers == null || headers.length < 2) {
                return terms;
            }

            List<String> headerNames = new ArrayList<>();
            for (String h : headers) {
                headerNames.add(h != null ? h.trim() : "");
            }
            ColumnLayout.Resolution columns = ColumnLayout.resolve(headerNames, config);
            int srcCol = columns.sourceColumn;
            int tgtCol = columns.targetColumn;
            config.setLangColumns(srcCol, tgtCol);
            config.setAvailableLangs(columns.available);
            config.setSelectionFallback(columns.fellBack);
            config.setSourceLang(headerNames.get(srcCol));
            config.setTargetLang(headerNames.get(tgtCol));

            // Columns other than the source and target are not modelled as terms; remember their
            // names and keep each row's values so a save can write them back untouched.
            List<String> extraColumns = ColumnLayout.extrasOf(headerNames, srcCol, tgtCol);
            config.setExtraColumns(extraColumns);
            ColumnLayout layout = ColumnLayout.of(config);
            int statusCol = findStatusColumn(extraColumns);

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                TermEntry entry = new TermEntry();
                if (line.length > srcCol) {
                    String src = line[srcCol];
                    entry.setSourceTerm(src != null ? src.trim() : null);
                }
                if (line.length > tgtCol) {
                    String tgt = line[tgtCol];
                    entry.setTargetTerm(tgt != null ? tgt.trim() : null);
                }
                // Blank lines and rows with nothing in the first two columns are not entries.
                if (isBlank(entry.getSourceTerm()) && isBlank(entry.getTargetTerm())) {
                    continue;
                }
                Map<String, String> extras = new LinkedHashMap<>();
                for (int i = 0; i < extraColumns.size(); i++) {
                    int col = layout.extraPosition(i);
                    // Cells missing from a short row come back as empty strings.
                    extras.put(extraColumns.get(i), col < line.length && line[col] != null ? line[col] : "");
                }
                entry.setExtraFields(extras);
                if (statusCol >= 0) {
                    int col = layout.extraPosition(statusCol);
                    String v = col < line.length && line[col] != null ? line[col].trim() : "";
                    entry.setStatusRaw(v.isEmpty() ? null : v);
                }
                terms.add(entry);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load CSV: " + filePath, e);
        }

        return terms;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /** Index within extraColumns of the "status" column, case-insensitive; -1 when absent. */
    static int findStatusColumn(List<String> extraColumns) {
        for (int i = 0; i < extraColumns.size(); i++) {
            if (TermEntry.STATUS_FIELD.equalsIgnoreCase(extraColumns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Whether any entry carries a non-blank status value to write (cleared "" does not count). */
    static boolean hasStatusColumn(List<TermEntry> terms) {
        for (TermEntry entry : terms) {
            String v = entry.getStoredStatusValue();
            if (v != null && !v.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        String filePath = config.getFilePath();

        try {
            AtomicFileWriter.write(Path.of(filePath), out -> {
                Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                writer.write('\uFEFF');
                CSVWriter csvWriter = new CSVWriter(writer);

                String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-cn";
                String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-us";
                List<String> extraColumns = config.getExtraColumns();

                ColumnLayout layout = ColumnLayout.of(config);
                boolean appendStatus = findStatusColumn(extraColumns) < 0 && hasStatusColumn(terms);
                String[] headers = new String[layout.width() + (appendStatus ? 1 : 0)];
                headers[layout.sourceIndex()] = sourceLang;
                headers[layout.targetIndex()] = targetLang;
                for (int i = 0; i < extraColumns.size(); i++) {
                    headers[layout.extraPosition(i)] = extraColumns.get(i);
                }
                if (appendStatus) {
                    headers[headers.length - 1] = TermEntry.STATUS_FIELD;
                }
                csvWriter.writeNext(headers);

                for (TermEntry entry : terms) {
                    String[] row = new String[headers.length];
                    row[layout.sourceIndex()] = entry.getSourceTerm() != null ? entry.getSourceTerm() : "";
                    row[layout.targetIndex()] = entry.getTargetTerm() != null ? entry.getTargetTerm() : "";
                    int statusIdx = findStatusColumn(extraColumns);
                    for (int i = 0; i < extraColumns.size(); i++) {
                        String value;
                        if (i == statusIdx) {
                            value = entry.getStoredStatusValue();
                            if (value == null) {
                                // Fall back to extraFields for entries that carry the value
                                // there without having gone through setStatusRaw (legacy path).
                                value = entry.getExtraFields().get(extraColumns.get(i));
                            }
                        } else {
                            value = entry.getExtraFields().get(extraColumns.get(i));
                        }
                        row[layout.extraPosition(i)] = value != null ? value : "";
                    }
                    if (appendStatus) {
                        String value = entry.getStoredStatusValue();
                        row[row.length - 1] = value != null ? value : "";
                    }
                    csvWriter.writeNext(row);
                }
                csvWriter.flush();
                // Leave the underlying stream to AtomicFileWriter; closing the CSVWriter
                // here would close the temp file's stream before the move.
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to save CSV: " + filePath, e);
        }
    }
}

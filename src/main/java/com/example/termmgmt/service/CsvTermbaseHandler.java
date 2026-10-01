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

            String sourceLang = headers[0].trim();
            String targetLang = headers[1].trim();
            config.setSourceLang(sourceLang);
            config.setTargetLang(targetLang);

            // Columns from the third on are not modelled as terms; remember their names and
            // keep each row's values so a save can write them back untouched.
            List<String> extraColumns = new ArrayList<>();
            for (int i = 2; i < headers.length; i++) {
                extraColumns.add(headers[i].trim());
            }
            config.setExtraColumns(extraColumns);

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                TermEntry entry = new TermEntry();
                if (line.length > 0) {
                    String src = line[0];
                    entry.setSourceTerm(src != null ? src.trim() : null);
                }
                if (line.length > 1) {
                    String tgt = line[1];
                    entry.setTargetTerm(tgt != null ? tgt.trim() : null);
                }
                // Blank lines and rows with nothing in the first two columns are not entries.
                if (isBlank(entry.getSourceTerm()) && isBlank(entry.getTargetTerm())) {
                    continue;
                }
                Map<String, String> extras = new LinkedHashMap<>();
                for (int i = 0; i < extraColumns.size(); i++) {
                    int col = i + 2;
                    // Cells missing from a short row come back as empty strings.
                    extras.put(extraColumns.get(i), col < line.length && line[col] != null ? line[col] : "");
                }
                entry.setExtraFields(extras);
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

                String[] headers = new String[2 + extraColumns.size()];
                headers[0] = sourceLang;
                headers[1] = targetLang;
                for (int i = 0; i < extraColumns.size(); i++) {
                    headers[i + 2] = extraColumns.get(i);
                }
                csvWriter.writeNext(headers);

                for (TermEntry entry : terms) {
                    String[] row = new String[2 + extraColumns.size()];
                    row[0] = entry.getSourceTerm() != null ? entry.getSourceTerm() : "";
                    row[1] = entry.getTargetTerm() != null ? entry.getTargetTerm() : "";
                    for (int i = 0; i < extraColumns.size(); i++) {
                        String value = entry.getExtraFields().get(extraColumns.get(i));
                        row[i + 2] = value != null ? value : "";
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

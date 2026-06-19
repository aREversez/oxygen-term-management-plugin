package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                TermEntry entry = new TermEntry();
                if (line.length > 0) entry.setSourceTerm(line[0]);
                if (line.length > 1) entry.setTargetTerm(line[1]);
                terms.add(entry);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load CSV: " + filePath, e);
        }

        return terms;
    }

    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        String filePath = config.getFilePath();

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(filePath), StandardCharsets.UTF_8)) {
            writer.write('\uFEFF');
            CSVWriter csvWriter = new CSVWriter(writer);

            String sourceLang = config.getSourceLang() != null ? config.getSourceLang() : "zh-cn";
            String targetLang = config.getTargetLang() != null ? config.getTargetLang() : "en-us";

            String[] headers = new String[]{sourceLang, targetLang};
            csvWriter.writeNext(headers);

            for (TermEntry entry : terms) {
                csvWriter.writeNext(new String[]{
                    entry.getSourceTerm() != null ? entry.getSourceTerm() : "",
                    entry.getTargetTerm() != null ? entry.getTargetTerm() : ""
                });
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save CSV: " + filePath, e);
        }
    }
}

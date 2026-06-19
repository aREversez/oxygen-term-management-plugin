package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dispatcher that routes termbase operations to the appropriate
 * format-specific handler (CSV, XLSX, or TBX).
 */
public class TermbaseLoader {

    /**
     * Detect the format of a termbase file based on its extension.
     *
     * @param filePath the full path to the file
     * @return the detected Format enum
     * @throws IllegalArgumentException if the file extension is unrecognized
     */
    public static Format detectFormat(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".tbx")) {
            return Format.TBX;
        } else if (lower.endsWith(".xlsx")) {
            return Format.XLSX;
        } else if (lower.endsWith(".csv")) {
            return Format.CSV;
        }
        throw new IllegalArgumentException("Unsupported file format: " + filePath);
    }

    /**
     * Load all terms from a termbase file.
     * Routes to the appropriate handler based on file format.
     *
     * @param config the termbase configuration
     * @return a list of TermEntry objects
     * @throws RuntimeException if loading fails
     */
    public static List<TermEntry> loadTerms(TermbaseConfig config) {
        try {
            File file = new File(config.getFilePath());
            if (!file.exists()) {
                throw new IOException("File not found: " + file.getAbsolutePath());
            }

            switch (config.getFormat()) {
                case CSV:
                    return CsvTermbaseHandler.loadTerms(config);
                case XLSX:
                    return XlsxTermbaseHandler.loadTerms(config);
                case TBX:
                    return TbxTermbaseHandler.loadTerms(config);
                default:
                    throw new IOException("Unknown format: " + config.getFormat());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load termbase: " + config.getFilePath(), e);
        }
    }

    /**
     * Save terms back to a termbase file.
     * Routes to the appropriate handler based on file format.
     *
     * @param config  the termbase configuration
     * @param terms   the complete list of terms to save
     * @throws RuntimeException if saving fails
     */
    public static void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        try {
            switch (config.getFormat()) {
                case CSV:
                    CsvTermbaseHandler.saveTerms(config, terms);
                    break;
                case XLSX:
                    XlsxTermbaseHandler.saveTerms(config, terms);
                    break;
                case TBX:
                    TbxTermbaseHandler.saveTerms(config, terms);
                    break;
                default:
                    throw new IOException("Unknown format: " + config.getFormat());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save termbase: " + config.getFilePath(), e);
        }
    }
}

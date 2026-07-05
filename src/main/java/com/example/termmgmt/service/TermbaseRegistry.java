package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton registry that manages termbase configurations and cached term data.
 *
 * Responsibilities:
 * - Maintain a list of TermbaseConfig objects (paths, formats, enabled status)
 * - Cache loaded terms in memory for each enabled termbase
 * - Maintain a source-term index for O(1) lookup by source term text
 * - Provide access to enabled termbases and their terms
 * - Persist configurations via OptionsStorage
 *
 * Integration with Oxygen:
 * - Configurations are saved/loaded via OptionsStorage
 * - Serialized as a simple JSON-like string for persistence
 */
public class TermbaseRegistry {

    private static final String PERSISTENCE_KEY = "com.example.termmgmt.termbase-configs";

    private static TermbaseRegistry instance;

    private List<TermbaseConfig> configs;
    private Map<String, List<TermEntry>> termCache; // Map from file path to terms
    private Map<String, List<TermEntry>> sourceIndex; // Map from source term text → terms (case-insensitive key)

    private TermbaseRegistry() {
        this.configs = new ArrayList<>();
        this.termCache = new HashMap<>();
        this.sourceIndex = new HashMap<>();
    }

    /**
     * Get the singleton instance.
     *
     * @return the TermbaseRegistry instance
     */
    public static synchronized TermbaseRegistry getInstance() {
        if (instance == null) {
            instance = new TermbaseRegistry();
        }
        return instance;
    }

    // ---- Serialization (unchanged) ----

    private String serializeConfigs(List<TermbaseConfig> configs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (TermbaseConfig config : configs) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{");
            sb.append("\"path\":\"").append(config.getFilePath().replace("\\", "\\\\").replace("\"", "\\\"")).append("\",");
            sb.append("\"format\":\"").append(config.getFormat().name()).append("\",");
            sb.append("\"enabled\":").append(config.isEnabled());
            if (config.getSourceLang() != null) {
                sb.append(",\"sourceLang\":\"").append(config.getSourceLang()).append("\"");
            }
            if (config.getTargetLang() != null) {
                sb.append(",\"targetLang\":\"").append(config.getTargetLang()).append("\"");
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<TermbaseConfig> deserializeConfigs(String serialized) {
        List<TermbaseConfig> result = new ArrayList<>();
        if (serialized == null || serialized.isEmpty()) {
            return result;
        }
        serialized = serialized.trim();
        if (!serialized.startsWith("[") || !serialized.endsWith("]")) {
            return result;
        }
        String inner = serialized.substring(1, serialized.length() - 1);
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            if (depth == 0 && i + 1 < inner.length() && inner.charAt(i + 1) == ',') {
                String obj = inner.substring(start, i + 1);
                result.add(parseConfig(obj));
                start = i + 2;
            }
        }
        if (start < inner.length()) {
            String obj = inner.substring(start);
            result.add(parseConfig(obj));
        }
        return result;
    }

    private TermbaseConfig parseConfig(String json) {
        try {
            String path = extractString(json, "path");
            String format = extractString(json, "format");
            boolean enabled = extractBoolean(json, "enabled");
            if (path == null) return null;
            if (path.startsWith("." + File.separator)) {
                path = new File(System.getProperty("user.dir"), path).getAbsolutePath();
            }
            Format fmt = Format.CSV;
            if ("XLSX".equals(format)) fmt = Format.XLSX;
            else if ("TBX".equals(format)) fmt = Format.TBX;
            TermbaseConfig config = new TermbaseConfig(path, fmt, enabled);
            config.setSourceLang(extractString(json, "sourceLang"));
            config.setTargetLang(extractString(json, "targetLang"));
            return config;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        int quoteStart = json.indexOf('"', colon + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteStart < 0 || quoteEnd < 0 || quoteEnd <= quoteStart) return null;
        String val = json.substring(quoteStart + 1, quoteEnd);
        return val.replace("\\\\", "\\");
    }

    private boolean extractBoolean(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx + search.length());
        String rest = json.substring(colon + 1).trim();
        return rest.startsWith("true");
    }

    // ---- Config management ----

    public void loadConfigs() {
        try {
            WSOptionsStorage os = PluginWorkspaceProvider.getPluginWorkspace().getOptionsStorage();
            String serialized = os.getOption(PERSISTENCE_KEY, "");
            if (serialized != null && !serialized.isEmpty()) {
                configs = deserializeConfigs(serialized);
            } else {
                configs = new ArrayList<>();
            }
        } catch (Exception e) {
            System.err.println("Failed to load configs from OptionsStorage: " + e.getMessage());
        }
    }

    public void saveConfigs() {
        try {
            WSOptionsStorage os = PluginWorkspaceProvider.getPluginWorkspace().getOptionsStorage();
            String serialized = serializeConfigs(configs);
            os.setOption(PERSISTENCE_KEY, serialized);
        } catch (Exception e) {
            System.err.println("Failed to save configs to OptionsStorage: " + e.getMessage());
        }
    }

    public void setConfigs(List<TermbaseConfig> configs) {
        this.configs = new ArrayList<>(configs);
    }

    public List<TermbaseConfig> getConfigs() {
        return new ArrayList<>(configs);
    }

    public List<TermbaseConfig> getEnabledConfigs() {
        List<TermbaseConfig> enabled = new ArrayList<>();
        for (TermbaseConfig config : configs) {
            if (config.isEnabled()) {
                enabled.add(config);
            }
        }
        return enabled;
    }

    public TermbaseConfig getConfigByFilePath(String filePath) {
        if (filePath == null) return null;
        for (TermbaseConfig config : configs) {
            if (config.getFilePath().equals(filePath)) {
                return config;
            }
        }
        return null;
    }

    // ---- Term cache & source index ----

    public List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms = TermbaseLoader.loadTerms(config);
        termCache.put(config.getFilePath(), terms);
        rebuildSourceIndex();
        return terms;
    }

    public List<TermEntry> getTerms(TermbaseConfig config) {
        List<TermEntry> cached = termCache.get(config.getFilePath());
        if (cached != null) {
            return new ArrayList<>(cached);
        }
        try {
            List<TermEntry> terms = TermbaseLoader.loadTerms(config);
            termCache.put(config.getFilePath(), new ArrayList<>(terms));
            rebuildSourceIndex();
            return terms;
        } catch (Exception e) {
            System.err.println("Failed to load terms: " + config.getFilePath());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    public void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        TermbaseLoader.saveTerms(config, terms);
        termCache.put(config.getFilePath(), new ArrayList<>(terms));
        rebuildSourceIndex();
    }

    public void reloadConfig(String filePath) {
        for (TermbaseConfig config : configs) {
            if (config.getFilePath().equals(filePath)) {
                try {
                    loadTerms(config);
                } catch (Exception e) {
                    System.err.println("Failed to reload termbase: " + filePath);
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    public void clearCache() {
        termCache.clear();
        sourceIndex.clear();
    }

    public List<TermEntry> getAllTerms() {
        List<TermEntry> all = new ArrayList<>();
        for (List<TermEntry> terms : termCache.values()) {
            all.addAll(terms);
        }
        return all;
    }

    // ---- Source-term index ----

    /**
     * Rebuild the source-term index from all cached termbases.
     * Called automatically after load/save/reload operations.
     */
    public void rebuildSourceIndex() {
        sourceIndex.clear();
        for (Map.Entry<String, List<TermEntry>> cacheEntry : termCache.entrySet()) {
            String filePath = cacheEntry.getKey();
            for (TermEntry entry : cacheEntry.getValue()) {
                if (entry.getSourceTerm() == null || entry.getSourceTerm().trim().isEmpty()) continue;
                entry.setSourceFilePath(filePath);
                String key = entry.getSourceTerm().trim().toLowerCase();
                sourceIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
            }
        }
    }

    /**
     * Find all cached terms whose source term matches the given text
     * (case-insensitive, exact match).
     *
     * @param sourceText the source term text to look up
     * @return list of matching TermEntry objects, or empty list
     */
    public List<TermEntry> findTermsBySource(String sourceText) {
        if (sourceText == null || sourceText.trim().isEmpty()) return Collections.emptyList();
        List<TermEntry> result = sourceIndex.get(sourceText.trim().toLowerCase());
        return result != null ? new ArrayList<>(result) : Collections.emptyList();
    }
}

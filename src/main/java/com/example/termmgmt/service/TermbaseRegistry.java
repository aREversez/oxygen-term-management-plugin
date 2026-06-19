package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton registry that manages termbase configurations and cached term data.
 *
 * Responsibilities:
 * - Maintain a list of TermbaseConfig objects (paths, formats, enabled status)
 * - Cache loaded terms in memory for each enabled termbase
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

    private TermbaseRegistry() {
        this.configs = new ArrayList<>();
        this.termCache = new HashMap<>();
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

    /**
     * Serialize configs to a simple JSON-like string for OptionsStorage persistence.
     */
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
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize configs from a simple JSON-like string.
     */
    @SuppressWarnings("unchecked")
    private List<TermbaseConfig> deserializeConfigs(String serialized) {
        List<TermbaseConfig> result = new ArrayList<>();
        if (serialized == null || serialized.isEmpty()) {
            return result;
        }
        // Simple JSON parsing without external library
        serialized = serialized.trim();
        if (!serialized.startsWith("[") || !serialized.endsWith("]")) {
            return result;
        }
        String inner = serialized.substring(1, serialized.length() - 1);
        // Split by },{ to find individual objects
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
        // Parse the last object
        if (start < inner.length()) {
            String obj = inner.substring(start);
            result.add(parseConfig(obj));
        }
        return result;
    }

    /**
     * Parse a single config JSON object.
     */
    private TermbaseConfig parseConfig(String json) {
        try {
            String path = extractString(json, "path");
            String format = extractString(json, "format");
            boolean enabled = extractBoolean(json, "enabled");
            Format fmt = Format.CSV;
            if ("XLSX".equals(format)) fmt = Format.XLSX;
            else if ("TBX".equals(format)) fmt = Format.TBX;
            return new TermbaseConfig(path, fmt, enabled);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract a string value from a JSON object.
     */
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

    /**
     * Extract a boolean value from a JSON object.
     */
    private boolean extractBoolean(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx + search.length());
        String rest = json.substring(colon + 1).trim();
        return rest.startsWith("true");
    }

    /**
     * Load configurations from OptionsStorage.
     */
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

    /**
     * Save configurations to OptionsStorage.
     */
    public void saveConfigs() {
        try {
            WSOptionsStorage os = PluginWorkspaceProvider.getPluginWorkspace().getOptionsStorage();
            String serialized = serializeConfigs(configs);
            os.setOption(PERSISTENCE_KEY, serialized);
        } catch (Exception e) {
            System.err.println("Failed to save configs to OptionsStorage: " + e.getMessage());
        }
    }

    /**
     * Set the configuration list.
     *
     * @param configs the list of TermbaseConfig objects
     */
    public void setConfigs(List<TermbaseConfig> configs) {
        this.configs = new ArrayList<>(configs);
    }

    /**
     * Get the list of all configurations.
     *
     * @return an unmodifiable list of TermbaseConfig objects
     */
    public List<TermbaseConfig> getConfigs() {
        return new ArrayList<>(configs);
    }

    /**
     * Get the list of enabled configurations.
     *
     * @return an unmodifiable list of enabled TermbaseConfig objects
     */
    public List<TermbaseConfig> getEnabledConfigs() {
        List<TermbaseConfig> enabled = new ArrayList<>();
        for (TermbaseConfig config : configs) {
            if (config.isEnabled()) {
                enabled.add(config);
            }
        }
        return enabled;
    }

    /**
     * Load terms from a termbase file into memory.
     *
     * @param config the termbase configuration
     * @return the loaded terms
     */
    public List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms = TermbaseLoader.loadTerms(config);
        // Cache the terms
        termCache.put(config.getFilePath(), terms);
        return terms;
    }

    /**
     * Get cached terms for a termbase.
     *
     * @param config the termbase configuration
     * @return the cached terms, or loads them if not cached
     */
    public List<TermEntry> getTerms(TermbaseConfig config) {
        // Check cache first
        List<TermEntry> cached = termCache.get(config.getFilePath());
        if (cached != null) {
            return new ArrayList<>(cached);
        }

        // Load from file and cache
        try {
            List<TermEntry> terms = TermbaseLoader.loadTerms(config);
            termCache.put(config.getFilePath(), new ArrayList<>(terms));
            return terms;
        } catch (Exception e) {
            System.err.println("Failed to load terms: " + config.getFilePath());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Save terms back to a termbase file.
     *
     * @param config  the termbase configuration
     * @param terms   the complete list of terms to save
     */
    public void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        TermbaseLoader.saveTerms(config, terms);
        // Update cache
        termCache.put(config.getFilePath(), new ArrayList<>(terms));
    }

    /**
     * Reload a termbase from disk.
     *
     * @param filePath the file path to reload
     */
    public void reloadConfig(String filePath) {
        // Find the config for this file path
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

    /**
     * Clear all cached terms.
     */
    public void clearCache() {
        termCache.clear();
    }

    /**
     * Get all cached terms.
     *
     * @return an unmodifiable list of all cached terms
     */
    public List<TermEntry> getAllTerms() {
        List<TermEntry> all = new ArrayList<>();
        for (List<TermEntry> terms : termCache.values()) {
            all.addAll(terms);
        }
        return all;
    }
}

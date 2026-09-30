package com.example.termmgmt.service;

import javax.swing.JOptionPane;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.I18N;
import com.example.termmgmt.util.TermMatchUtils;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.Locale;

import javax.swing.SwingUtilities;

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

    // Replaced wholesale, never mutated in place; volatile gives safe publication to worker threads.
    private volatile List<TermbaseConfig> configs;
    private Map<String, List<TermEntry>> termCache; // Map from file path to terms
    private Map<String, List<TermEntry>> sourceIndex; // Map from source term text → terms (case-insensitive key)
    private Map<String, Pattern> patternCache; // Compiled match patterns keyed by source term text

    private List<Runnable> changeListeners;

    private TermbaseRegistry() {
        this.configs = new ArrayList<>();
        this.termCache = new HashMap<>();
        this.sourceIndex = new HashMap<>();
        this.patternCache = new HashMap<>();
        this.changeListeners = new ArrayList<>();
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

    // ---- Change listeners ----

    public synchronized void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    public synchronized void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    public void fireTermsChanged() {
        List<Runnable> copy;
        synchronized (this) {
            copy = new ArrayList<>(changeListeners);
        }
        for (Runnable r : copy) {
            r.run();
        }
    }

    // ---- Serialization (unchanged) ----

    private String serializeConfigs(List<TermbaseConfig> configs) {
        JsonArray arr = new JsonArray();
        for (TermbaseConfig config : configs) {
            JsonObject obj = new JsonObject();
            obj.addProperty("path", config.getFilePath());
            obj.addProperty("format", config.getFormat().name());
            obj.addProperty("enabled", config.isEnabled());
            if (config.getSourceLang() != null) {
                obj.addProperty("sourceLang", config.getSourceLang());
            }
            if (config.getTargetLang() != null) {
                obj.addProperty("targetLang", config.getTargetLang());
            }
            arr.add(obj);
        }
        return new GsonBuilder().disableHtmlEscaping().create().toJson(arr);
    }

    private List<TermbaseConfig> deserializeConfigs(String serialized) {
        List<TermbaseConfig> result = new ArrayList<>();
        if (serialized == null || serialized.trim().isEmpty()) return result;
        try {
            JsonArray arr = JsonParser.parseString(serialized).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonObject obj = arr.get(i).getAsJsonObject();
                String path = obj.get("path").getAsString();
                if (path.startsWith("." + File.separator)) {
                    path = new File(System.getProperty("user.dir"), path).getAbsolutePath();
                }
                String fmt = obj.get("format").getAsString();
                Format format = Format.CSV;
                if ("XLSX".equals(fmt)) format = Format.XLSX;
                else if ("TBX".equals(fmt)) format = Format.TBX;
                boolean enabled = obj.get("enabled").getAsBoolean();
                TermbaseConfig config = new TermbaseConfig(path, format, enabled);
                if (obj.has("sourceLang")) {
                    config.setSourceLang(obj.get("sourceLang").getAsString());
                }
                if (obj.has("targetLang")) {
                    config.setTargetLang(obj.get("targetLang").getAsString());
                }
                result.add(config);
            }
        } catch (Exception e) {
            System.err.println("Failed to deserialize configs: " + e.getMessage());
        }
        return result;
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
            JOptionPane.showMessageDialog(null,
                I18N.getString("msg.failed.load.configs", e.getMessage()),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    public void saveConfigs() {
        try {
            WSOptionsStorage os = PluginWorkspaceProvider.getPluginWorkspace().getOptionsStorage();
            String serialized = serializeConfigs(configs);
            os.setOption(PERSISTENCE_KEY, serialized);
        } catch (Exception e) {
            System.err.println("Failed to save configs to OptionsStorage: " + e.getMessage());
            JOptionPane.showMessageDialog(null,
                I18N.getString("msg.failed.save.configs", e.getMessage()),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
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

    // Thread safety: termCache, sourceIndex and patternCache are shared between the EDT and
    // SwingWorker threads, so every access goes through this object's monitor. File I/O is
    // deliberately done outside that monitor so a slow disk never blocks readers, and
    // listeners are notified outside it.
    //
    // Reading, writing and read-modify-write of one termbase file are additionally serialized
    // per file path (fileLock), so two edits made in quick succession each build on the
    // result of the previous one instead of overwriting it, and a reload never reads a file
    // that is half written. Lock order is always fileLock -> registry monitor.

    private final Map<String, Object> fileLocks = new HashMap<>();

    private synchronized Object fileLock(String filePath) {
        return fileLocks.computeIfAbsent(filePath, k -> new Object());
    }

    public List<TermEntry> loadTerms(TermbaseConfig config) {
        List<TermEntry> terms;
        synchronized (fileLock(config.getFilePath())) {
            terms = TermbaseLoader.loadTerms(config);
            synchronized (this) {
                termCache.put(config.getFilePath(), terms);
                rebuildSourceIndex();
            }
        }
        return terms;
    }

    public List<TermEntry> getTerms(TermbaseConfig config) {
        synchronized (this) {
            List<TermEntry> cached = termCache.get(config.getFilePath());
            if (cached != null) {
                return new ArrayList<>(cached);
            }
        }
        synchronized (fileLock(config.getFilePath())) {
            synchronized (this) {
                // Populated by another thread (e.g. a save) while we waited for the file lock.
                List<TermEntry> cached = termCache.get(config.getFilePath());
                if (cached != null) {
                    return new ArrayList<>(cached);
                }
            }
            List<TermEntry> terms = TermbaseLoader.loadTerms(config);
            synchronized (this) {
                termCache.put(config.getFilePath(), new ArrayList<>(terms));
                rebuildSourceIndex();
            }
            return terms;
        }
    }

    public void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        synchronized (fileLock(config.getFilePath())) {
            TermbaseLoader.saveTerms(config, terms);
            synchronized (this) {
                termCache.put(config.getFilePath(), new ArrayList<>(terms));
                rebuildSourceIndex();
            }
        }
        fireTermsChanged();
    }

    /**
     * Atomically read the latest terms of a termbase, apply {@code mutator} to a copy and
     * write the result. Use this instead of getTerms() + saveTerms() for any change made to
     * the current contents: the read-modify-write is serialized per file, so concurrent
     * changes are applied one after the other and none is lost.
     *
     * The mutator runs while the file lock is held; it must not block or call back into
     * operations on this termbase. It receives a private copy, and returns the list to save.
     */
    public void updateTerms(TermbaseConfig config, UnaryOperator<List<TermEntry>> mutator) {
        String filePath = config.getFilePath();
        synchronized (fileLock(filePath)) {
            List<TermEntry> current;
            synchronized (this) {
                List<TermEntry> cached = termCache.get(filePath);
                current = cached != null ? new ArrayList<>(cached) : null;
            }
            if (current == null) {
                current = new ArrayList<>(TermbaseLoader.loadTerms(config));
            }
            List<TermEntry> updated = Objects.requireNonNull(mutator.apply(current), "mutator returned null");
            TermbaseLoader.saveTerms(config, updated);
            synchronized (this) {
                termCache.put(filePath, new ArrayList<>(updated));
                rebuildSourceIndex();
            }
        }
        fireTermsChanged();
    }

    public void reloadConfig(String filePath) {
        for (TermbaseConfig config : configs) {
            if (config.getFilePath().equals(filePath)) {
                loadTerms(config);
                fireTermsChanged();
                break;
            }
        }
    }

    public synchronized void clearCache() {
        termCache.clear();
        sourceIndex.clear();
        patternCache.clear();
    }

    public synchronized Pattern getMatchPattern(String sourceTerm) {
        return patternCache.computeIfAbsent(sourceTerm, TermMatchUtils::buildMatchPattern);
    }

    public synchronized List<TermEntry> getAllTerms() {
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
    public synchronized void rebuildSourceIndex() {
        sourceIndex.clear();
        for (Map.Entry<String, List<TermEntry>> cacheEntry : termCache.entrySet()) {
            String filePath = cacheEntry.getKey();
            for (TermEntry entry : cacheEntry.getValue()) {
                if (entry.getSourceTerm() == null || entry.getSourceTerm().trim().isEmpty()) continue;
                entry.setSourceFilePath(filePath);
                String key = entry.getSourceTerm().trim().toLowerCase(Locale.ROOT);
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
    public synchronized List<TermEntry> findTermsBySource(String sourceText) {
        if (sourceText == null || sourceText.trim().isEmpty()) return Collections.emptyList();
        List<TermEntry> result = sourceIndex.get(sourceText.trim().toLowerCase(Locale.ROOT));
        return result != null ? new ArrayList<>(result) : Collections.emptyList();
    }
}

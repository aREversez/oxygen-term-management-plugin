package com.example.termmgmt.service;

import javax.swing.JOptionPane;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.I18N;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
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

    /** OptionsStorage key of the "case sensitive matching" preference ("true"/"false"). */
    public static final String CASE_SENSITIVE_OPTION_KEY = "com.example.termmgmt.case-sensitive";

    private static TermbaseRegistry instance;

    // Replaced wholesale, never mutated in place; volatile gives safe publication to worker threads.
    private volatile List<TermbaseConfig> configs;
    private Map<String, List<TermEntry>> termCache; // Map from file path to terms
    private Map<String, long[]> fileStamps; // file path → {lastModified, size} when cache was filled
    private Map<String, List<TermEntry>> sourceIndex; // Map from source term text → terms (case-insensitive key)

    // User option: distinguish upper/lower case when matching terms. Volatile because the
    // EDT writes it from the preferences page and scan workers read it.
    private volatile boolean caseSensitive;

    private List<Runnable> changeListeners;

    private TermbaseRegistry() {
        this.configs = new ArrayList<>();
        this.termCache = new HashMap<>();
        this.fileStamps = new HashMap<>();
        this.sourceIndex = new HashMap<>();
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

    // Thread safety: termCache, sourceIndex are shared between the EDT and
    // SwingWorker threads, so every access goes through this object's monitor. File I/O is
    // deliberately done outside that monitor so a slow disk never blocks readers, and
    // listeners are notified outside it.
    //
    // Reading, writing and read-modify-write of one termbase file are additionally serialized
    // per file path (fileLock), so two edits made in quick succession each build on the
    // result of the previous one instead of overwriting it, and a reload never reads a file
    // that is half written. Lock order is always fileLock -> registry monitor.

    private final Map<String, Object> fileLocks = new HashMap<>();

    // Runs updateTermsAsync() tasks strictly in submission order. A SwingWorker pool gives no
    // ordering guarantee, so two quick edits of the same entry could otherwise be applied in
    // reverse order and the later one dropped as "entry not found".
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "term-writer");
        t.setDaemon(true);
        return t;
    });

    private synchronized Object fileLock(String filePath) {
        return fileLocks.computeIfAbsent(filePath, k -> new Object());
    }

    public List<TermEntry> loadTerms(TermbaseConfig config) {
        return loadTerms(config, null);
    }

    /**
     * Package-private seam for tests: {@code midLoadProbe}, when non-null, runs after the file
     * has been read but before the post-load stamp is taken, so a test can simulate a
     * modification landing during the load window.
     */
    List<TermEntry> loadTerms(TermbaseConfig config, Runnable midLoadProbe) {
        List<TermEntry> terms;
        String filePath = config.getFilePath();
        synchronized (fileLock(filePath)) {
            // 7.3: Read stamp BEFORE loading; if it changes during load, don't cache
            // the (potentially stale) data with the post-load stamp.
            long[] preStamp = stampOf(filePath);
            terms = TermbaseLoader.loadTerms(config);
            if (midLoadProbe != null) midLoadProbe.run();
            long[] postStamp = stampOf(filePath);
            long[] stamp = chooseCachedStamp(preStamp, postStamp);
            synchronized (this) {
                termCache.put(filePath, terms);
                putStamp(filePath, stamp);
                rebuildSourceIndex();
            }
        }
        return terms;
    }

    /**
     * 7.3: Which stamp to record for a freshly loaded list. When the file did not change during
     * the read, pre and post match and either value records the cache as fresh. When they differ
     * the file was modified while it was being read, so the loaded content may be a torn read and
     * must NOT be trusted: recording the current (post) stamp would make the cache look fresh and
     * the next updateTerms would skip reloading and clobber that change. Returning the pre-load
     * stamp - which now differs from the file on disk - marks the cache stale so the next write
     * path reloads.
     */
    static long[] chooseCachedStamp(long[] pre, long[] post) {
        return java.util.Arrays.equals(pre, post) ? post : pre;
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
            // 7.3: Read stamp before loading.
            long[] preStamp = stampOf(config.getFilePath());
            List<TermEntry> terms = TermbaseLoader.loadTerms(config);
            long[] postStamp = stampOf(config.getFilePath());
            long[] stamp = chooseCachedStamp(preStamp, postStamp);
            synchronized (this) {
                termCache.put(config.getFilePath(), new ArrayList<>(terms));
                putStamp(config.getFilePath(), stamp);
                rebuildSourceIndex();
            }
            return terms;
        }
    }

    /**
     * Writes {@code terms} as the complete content of the termbase file and makes it the
     * cached list. This overwrites whatever is on disk and does NOT check whether the file
     * changed since it was loaded, so it is only safe where nothing else can have touched the
     * file (for example writing a brand-new termbase). Anything that edits an existing
     * termbase must go through {@link #updateTerms}/{@link #updateTermsAsync}, which re-read
     * a changed file before applying the change.
     */
    public void saveTerms(TermbaseConfig config, List<TermEntry> terms) {
        synchronized (fileLock(config.getFilePath())) {
            TermbaseLoader.saveTerms(config, terms);
            // Stamp after the write completed (the handlers move into place atomically),
            // so our own save is never mistaken for an external change later.
            long[] stamp = stampOf(config.getFilePath());
            synchronized (this) {
                termCache.put(config.getFilePath(), new ArrayList<>(terms));
                putStamp(config.getFilePath(), stamp);
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
    /**
     * Queue {@link #updateTerms} on a single background writer. Updates run in the order in
     * which they were submitted, so call this on the thread where the user's actions happen
     * (the EDT) to have them applied in that order. The returned future completes
     * exceptionally if the update fails; nothing is written in that case.
     */
    public CompletableFuture<Void> updateTermsAsync(TermbaseConfig config, UnaryOperator<List<TermEntry>> mutator) {
        return CompletableFuture.runAsync(() -> updateTerms(config, mutator), writer);
    }

    public void updateTerms(TermbaseConfig config, UnaryOperator<List<TermEntry>> mutator) {
        String filePath = config.getFilePath();
        synchronized (fileLock(filePath)) {
            long[] disk = stampOf(filePath);
            if (disk == null) {
                // Silently recreating a deleted termbase would lose whatever the user moved
                // or renamed; fail loudly instead.
                throw new RuntimeException("Termbase file no longer exists: " + filePath);
            }
            List<TermEntry> current;
            synchronized (this) {
                List<TermEntry> cached = termCache.get(filePath);
                long[] known = fileStamps.get(filePath);
                // Trust the cache only while the file on disk still matches what was loaded
                // or saved: an external edit (Excel, another editor) must not be overwritten.
                current = (cached != null && known != null && Arrays.equals(known, disk))
                    ? new ArrayList<>(cached) : null;
            }
            if (current == null) {
                current = new ArrayList<>(TermbaseLoader.loadTerms(config));
            }
            List<TermEntry> updated = Objects.requireNonNull(mutator.apply(current), "mutator returned null");
            TermbaseLoader.saveTerms(config, updated);
            long[] stamp = stampOf(filePath); // after the atomic move, not before
            synchronized (this) {
                termCache.put(filePath, new ArrayList<>(updated));
                putStamp(filePath, stamp);
                rebuildSourceIndex();
            }
        }
        fireTermsChanged();
    }

    /** {lastModified, size} of the file, or null when it does not exist (or cannot be read). */
    private static long[] stampOf(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        return new long[] { file.lastModified(), file.length() };
    }

    /** Call while holding the monitor; a missing stamp makes every cached list look stale. */
    private void putStamp(String filePath, long[] stamp) {
        if (stamp != null) {
            fileStamps.put(filePath, stamp);
        } else {
            fileStamps.remove(filePath);
        }
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

    /**
     * Step 4.2 / 7.1: check whether a cached termbase file was modified externally since the
     * last load or save. Returns true if the on-disk stamp differs from what the cache holds.
     * Reads the disk stamp outside the registry lock to avoid blocking other threads.
     * Does NOT reload anything; the caller decides whether to reload and notify.
     */
    public boolean isExternallyModified(String filePath) {
        long[] known;
        synchronized (this) {
            known = fileStamps.get(filePath);
        }
        if (known == null) return false; // not cached, nothing to compare
        long[] disk = stampOf(filePath);
        if (disk == null) return true;   // file deleted
        return !java.util.Arrays.equals(known, disk);
    }

    public synchronized void clearCache() {
        termCache.clear();
        fileStamps.clear();
        sourceIndex.clear();
    }

    /** Whether term matching currently distinguishes case. */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /** Set the case-sensitivity option; takes effect for patterns compiled from now on. */
    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
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

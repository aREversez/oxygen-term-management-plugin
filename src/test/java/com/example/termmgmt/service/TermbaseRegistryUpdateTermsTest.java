package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The registry caches loaded terms and updateTerms() applied a mutator on the cache. When
 * the file was edited by another application after the cache was filled, the save wrote the
 * stale cache back and silently dropped the external edit.
 */
class TermbaseRegistryUpdateTermsTest {

    @TempDir
    Path tempDir;

    private TermbaseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = TermbaseRegistry.getInstance();
        registry.clearCache();
    }

    private TermbaseConfig csvConfig(String name, String content) throws Exception {
        Path file = tempDir.resolve(name + ".csv");
        Files.writeString(file, content);
        return new TermbaseConfig(file.toString(), Format.CSV, true);
    }

    @Test
    void updateTerms_reloadsWhenFileChangedExternallyAndKeepsTheExternalEdit() throws Exception {
        TermbaseConfig config = csvConfig("external", "zh-cn,en-us\n网格,mesh\n");
        registry.loadTerms(config); // fills the cache

        // The user edits the file in Excel: new row added on disk.
        Files.writeString(Path.of(config.getFilePath()), "zh-cn,en-us\n网格,mesh\n节点,node\n");

        registry.updateTerms(config, terms -> {
            terms.add(new TermEntry("刚度", "stiffness"));
            return terms;
        });

        String saved = Files.readString(Path.of(config.getFilePath()));
        assertTrue(saved.contains("节点"), "external edit must not be overwritten");
        assertTrue(saved.contains("stiffness"), "the plugin's own change must be written");
        assertTrue(saved.contains("网格"));
    }

    @Test
    void updateTerms_consecutiveOwnSaves_doNotReloadOrLoseEitherChange() throws Exception {
        TermbaseConfig config = csvConfig("own_writes", "zh-cn,en-us\n网格,mesh\n");
        registry.loadTerms(config);

        registry.updateTerms(config, terms -> {
            terms.add(new TermEntry("A", "a"));
            return terms;
        });
        registry.updateTerms(config, terms -> {
            terms.add(new TermEntry("B", "b"));
            return terms;
        });

        String saved = Files.readString(Path.of(config.getFilePath()));
        assertTrue(saved.contains("网格"));
        assertTrue(saved.contains("A"));
        assertTrue(saved.contains("B"), "an edit queued after a save must build on the saved list");
    }

    @Test
    void updateTerms_throwsWhenFileWasDeleted() throws Exception {
        TermbaseConfig config = csvConfig("deleted", "zh-cn,en-us\n网格,mesh\n");
        registry.loadTerms(config);
        Files.delete(Path.of(config.getFilePath()));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> registry.updateTerms(config, terms -> terms));
        assertTrue(thrown.getMessage().contains("no longer exists"),
            "the error must say the file is gone, but was: " + thrown.getMessage());
    }

    @Test
    void updateTerms_editedEntryIsLocatedByValueAfterAStaleCacheReload() throws Exception {
        TermbaseConfig config = csvConfig("identity", "zh-cn,en-us\n网格,mesh\n节点,node\n");
        List<TermEntry> cached = new ArrayList<>(registry.loadTerms(config));
        TermEntry original = cached.get(0);

        // Touch the file so the stamp mismatches and updateTerms reloads: identities from the
        // old cache are gone, the mutator must still find the entry by its term values.
        Files.writeString(Path.of(config.getFilePath()), "zh-cn,en-us\n网格,mesh\n节点,node\n附加,extra\n");

        registry.updateTerms(config, terms -> {
            TermEntry edited = original.copy();
            edited.setTargetTerm("Mesh Element");
            com.example.termmgmt.util.TermEntryUtils.replaceEntry(terms, original, edited);
            return terms;
        });

        String saved = Files.readString(Path.of(config.getFilePath()));
        assertTrue(saved.contains("Mesh Element"), "the edit must land on the reloaded list");
        assertTrue(saved.contains("附加"), "the external row must survive");
    }

    /**
     * 7.3 (pure decision): when the file changed during the load, the recorded stamp must be
     * the stale pre-load value so the cache is not trusted; an unchanged load records a fresh one.
     * (The pre-fix code recorded the post-load stamp here, making the torn read look fresh.)
     */
    @Test
    void chooseCachedStamp_changedDuringLoad_recordsPreSoCacheIsStale() {
        long[] pre = { 1000L, 10L };
        long[] post = { 2000L, 20L };
        assertArrayEquals(pre, TermbaseRegistry.chooseCachedStamp(pre, post),
            "a change during load must record the stale pre-load stamp");
        // Unchanged load: pre equals post, the recorded stamp equals the file on disk (fresh).
        assertArrayEquals(pre, TermbaseRegistry.chooseCachedStamp(pre, pre),
            "an unchanged load records a fresh stamp");
    }

    /**
     * 7.3 (end-to-end): an edit that lands during the load window must mark the cache stale, so
     * the next updateTerms reloads instead of clobbering it with the half-read content.
     */
    @Test
    void loadTerms_fileModifiedDuringLoad_marksCacheStale_andNextUpdateReloads() throws Exception {
        TermbaseConfig config = csvConfig("midload", "zh-cn,en-us\n\u7f51\u683c,mesh\n");
        // The probe writes a new row after the file is read but before the post-load stamp,
        // deterministically simulating a modification landing mid-load.
        registry.loadTerms(config, () -> {
            try {
                Files.writeString(Path.of(config.getFilePath()),
                    "zh-cn,en-us\n\u7f51\u683c,mesh\n\u8282\u70b9,node\n");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(registry.isExternallyModified(config.getFilePath()),
            "a change landing during load must mark the cache stale");

        registry.updateTerms(config, terms -> {
            terms.add(new TermEntry("\u521a\u5ea6", "stiffness"));
            return terms;
        });
        String saved = Files.readString(Path.of(config.getFilePath()));
        assertTrue(saved.contains("node"), "the mid-load external row must survive");
        assertTrue(saved.contains("stiffness"), "the plugin edit must still be applied");
    }
}

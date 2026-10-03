package com.example.termmgmt.service;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import com.example.termmgmt.util.TermEntryUtils;
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

    /** First column of every data row of the saved CSV, quotes and BOM stripped. */
    private static List<String> savedSources(TermbaseConfig config) throws Exception {
        List<String> out = new ArrayList<>();
        String[] lines = Files.readString(Path.of(config.getFilePath())).replace("\uFEFF", "").split("\\R");
        for (int i = 1; i < lines.length; i++) {            // line 0 is the header
            if (lines[i].isBlank()) continue;
            String first = lines[i].split(",", 2)[0];
            out.add(first.replace("\"", "").trim());
        }
        return out;
    }

    // ---- 13: undo goes through updateTerms and re-inserts only what was deleted ----------

    @Test
    void undoDelete_afterALaterAddition_keepsTheAdditionAndRestoresTheDeletedTerm() throws Exception {
        TermbaseConfig config = csvConfig("undo_later_add", "zh-cn,en-us\nA,a\nB,b\nC,c\n");
        registry.loadTerms(config);

        List<TermEntryUtils.RemovedEntry> removed = new ArrayList<>();
        registry.updateTerms(config, terms -> {
            removed.addAll(TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(1))));
            return terms;
        });
        registry.updateTerms(config, terms -> {          // the user adds a term after deleting
            terms.add(new TermEntry("D", "d"));
            return terms;
        });
        registry.updateTerms(config, terms -> {          // undo
            TermEntryUtils.reinsertRemoved(terms, removed);
            return terms;
        });

        assertEquals(List.of("A", "B", "C", "D"), savedSources(config),
            "B is back between A and C, and the term added after the delete survived the undo");
    }

    @Test
    void undoDelete_afterAnExternalEdit_keepsTheExternalEdit() throws Exception {
        TermbaseConfig config = csvConfig("undo_external", "zh-cn,en-us\nA,a\nB,b\nC,c\n");
        registry.loadTerms(config);

        List<TermEntryUtils.RemovedEntry> removed = new ArrayList<>();
        registry.updateTerms(config, terms -> {
            removed.addAll(TermEntryUtils.removeEntriesRecording(terms, List.of(terms.get(1))));
            return terms;
        });

        // Another program adds a row to the file between the delete and the undo.
        Files.writeString(Path.of(config.getFilePath()), "zh-cn,en-us\nA,a\nC,c\nEXT,ext\n");

        registry.updateTerms(config, terms -> {
            TermEntryUtils.reinsertRemoved(terms, removed);
            return terms;
        });

        assertEquals(List.of("A", "B", "C", "EXT"), savedSources(config),
            "the external edit must not be overwritten by the undo, and the deleted term is restored");
    }

    // ---- 15: the staleness check must not do file I/O while holding the registry lock ----

    @Test
    void isExternallyModified_readsTheDiskStampOutsideTheRegistryLock() throws Exception {
        TermbaseConfig config = csvConfig("lock_free_stamp", "zh-cn,en-us\nA,a\n");
        registry.loadTerms(config);                       // cached, so a known stamp exists

        java.util.concurrent.atomic.AtomicBoolean heldLock = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        registry.setStampReader(path -> {
            reads.incrementAndGet();
            if (Thread.holdsLock(registry)) heldLock.set(true);
            return new long[] { 1L, 1L };
        });
        try {
            assertTrue(registry.isExternallyModified(config.getFilePath()), "a different stamp means modified");
        } finally {
            registry.setStampReader(null);
        }

        assertEquals(1, reads.get());
        assertFalse(heldLock.get(), "the disk stamp must be read without holding the registry monitor");
    }

    /** {lastModified, size} straight from the file, matching TermbaseRegistry.stampOf. */
    private static long[] realStamp(String path) {
        java.io.File f = new java.io.File(path);
        return f.exists() ? new long[] { f.lastModified(), f.length() } : null;
    }

    /**
     * A save holds the per-file lock while it writes the file and, only afterwards, refreshes the
     * recorded stamp. If the external-change probe ran without the lock, it could read the just-
     * written disk stamp against the not-yet-updated known stamp and falsely report our own save as
     * an external change - reloading and announcing "external changes detected" for no reason.
     * This simulates the probe landing exactly in that window and asserts it waits instead.
     */
    @Test
    void isExternallyModified_duringOwnSaveWindow_doesNotReportAnExternalChange() throws Exception {
        TermbaseConfig config = csvConfig("self_save_window", "zh-cn,en-us\nA,a\n");
        registry.loadTerms(config); // fills the cache with the real, current stamp

        java.util.concurrent.CountDownLatch saveAtStampRead = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch letSaveFinish = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        registry.setStampReader(path -> {
            int n = reads.incrementAndGet();
            if (n == 2) {
                // updateTerms' post-write stamp read: the file is now on disk with the new row but
                // the known stamp has NOT been updated yet, and the file lock is still held here.
                saveAtStampRead.countDown();
                try {
                    letSaveFinish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return realStamp(path);
        });

        java.util.concurrent.ExecutorService probe = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            registry.updateTermsAsync(config, terms -> {
                terms.add(new TermEntry("X", "x"));
                return terms;
            });
            assertTrue(saveAtStampRead.await(2, java.util.concurrent.TimeUnit.SECONDS),
                "the save must reach its post-write stamp read (the danger window)");

            java.util.concurrent.Future<Boolean> f =
                probe.submit(() -> registry.isExternallyModified(config.getFilePath()));
            // The probe has to wait for the save to release the file lock, so it must not have a
            // verdict yet - not true (the bug) and not a premature false either.
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> f.get(250, java.util.concurrent.TimeUnit.MILLISECONDS),
                "the probe must block while our own save still holds the file lock");

            letSaveFinish.countDown(); // save stores the fresh stamp and releases the lock
            assertFalse(f.get(2, java.util.concurrent.TimeUnit.SECONDS),
                "once our save finishes the on-disk stamp matches the cache: not an external change");
        } finally {
            probe.shutdownNow();
            registry.setStampReader(null);
        }
    }
}

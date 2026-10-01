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
}

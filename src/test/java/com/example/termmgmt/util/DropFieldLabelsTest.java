package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The export report names what a target format could not hold. The service reports plain English
 * names (it never touches the bundles); the panel renders them through {@link DropFieldLabels}.
 * This pins both halves: every name the service can emit maps to a key, and every key has a label
 * in every bundle, so no dropped field reaches a translated dialog as English.
 */
class DropFieldLabelsTest {

    private static final String[] BUNDLES = {
        "messages", "messages_en", "messages_zh", "messages_fr", "messages_de", "messages_ja"
    };

    private static final Path[] EMITTERS = {
        Path.of("src/main/java/com/example/termmgmt/service/TermbaseConverter.java"),
        Path.of("src/main/java/com/example/termmgmt/service/DitaGlossaryExporter.java")
    };

    private static Properties load(String bundle) throws Exception {
        try (InputStream in = DropFieldLabelsTest.class.getResourceAsStream("/i18n/" + bundle + ".properties")) {
            assertNotNull(in, bundle + ".properties must be on the classpath");
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    @Test
    void everyKeyAndTheLineTemplateHaveALabelInEveryBundle() throws Exception {
        for (String bundle : BUNDLES) {
            Properties p = load(bundle);
            Set<String> keys = new HashSet<>(DropFieldLabels.ALL_KEYS);
            keys.add("btn.export.report.line");
            for (String key : keys) {
                String value = p.getProperty(key);
                assertNotNull(value, bundle + " is missing " + key);
                assertFalse(value.isBlank(), bundle + " has an empty label for " + key);
            }
            String line = p.getProperty("btn.export.report.line");
            assertTrue(line.contains("{0}") && line.contains("{1}"),
                bundle + ": the report line needs both the field label and the count");
        }
    }

    /** Names are literals in the service; a new one without a label would show up as English. */
    @Test
    void everyFieldNameTheServiceEmitsHasALabel() throws Exception {
        Pattern literal = Pattern.compile("new DroppedField\\(\"([^\"]+)\"");
        int seen = 0;
        for (Path source : EMITTERS) {
            Matcher m = literal.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (m.find()) {
                seen++;
                assertNotNull(DropFieldLabels.keyOf(m.group(1)),
                    "no label key for dropped field \"" + m.group(1) + "\" emitted by " + source.getFileName());
            }
        }
        assertTrue(seen >= 9, "the scan should find the emitted names, found " + seen);
        // the dynamic one, built by TermbaseConverter.termNotesLabel
        assertNotNull(DropFieldLabels.keyOf("term notes"));
    }

    @Test
    void termNotesKeepTheirTypesAsDetail() {
        assertEquals(DropFieldLabels.KEY_TERM_NOTES, DropFieldLabels.keyOf("term notes (usage, grammar)"));
        assertEquals("usage, grammar", DropFieldLabels.detailOf("term notes (usage, grammar)"));
        assertNull(DropFieldLabels.detailOf("term notes"));
        assertNull(DropFieldLabels.detailOf("status"));
    }

    @Test
    void unknownNamesAreNotMapped() {
        assertNull(DropFieldLabels.keyOf("something new"));
        assertNull(DropFieldLabels.keyOf(null));
        assertNull(DropFieldLabels.detailOf(null));
    }
}

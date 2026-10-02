package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six resource files must carry the same keys. A tool that rewrites {@code .properties}
 * files can silently split a value at a literal {@code \n} and drop or mangle keys, which
 * only shows up as a missing label at runtime in one language.
 */
class I18nKeyParityTest {

    private static final String[] BUNDLES = {
        "messages", "messages_en", "messages_zh", "messages_fr", "messages_de", "messages_ja"
    };

    private static Properties load(String bundle) throws Exception {
        try (InputStream in = I18nKeyParityTest.class.getResourceAsStream("/i18n/" + bundle + ".properties")) {
            assertNotNull(in, bundle + ".properties must be on the classpath");
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    @Test
    void everyBundleHasEveryKeyOfTheDefaultBundle() throws Exception {
        Properties base = load("messages");
        for (String bundle : BUNDLES) {
            Properties other = load(bundle);
            for (String key : base.stringPropertyNames()) {
                assertTrue(other.containsKey(key), bundle + " is missing key " + key);
                assertFalse(other.getProperty(key).isBlank(), bundle + " has an empty value for " + key);
            }
            assertEquals(base.size(), other.size(), bundle + " has keys the default bundle lacks");
        }
    }

    /** 14: the TBX delete confirmation hint exists in every language. */
    @Test
    void tbxDeleteConfirmationNoteExistsInEveryBundle() throws Exception {
        for (String bundle : BUNDLES) {
            String note = load(bundle).getProperty("msg.confirm.delete.tbx.note");
            assertNotNull(note, bundle + " lacks msg.confirm.delete.tbx.note");
            assertFalse(note.isBlank());
        }
    }
}

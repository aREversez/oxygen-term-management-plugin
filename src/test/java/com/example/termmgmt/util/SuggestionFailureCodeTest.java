package com.example.termmgmt.util;

import com.example.termmgmt.service.SuggestionFailures;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failure reason travels from the service to the UI as {@code code:<i18n-key>[|<detail>]} so the
 * "these terms got no suggestion" notice can name the reason in the user's language instead of
 * hardcoded English. This pins the two halves together: every code the service can emit has a label
 * in every bundle, and the code format round-trips.
 */
class SuggestionFailureCodeTest {

    private static final String[] BUNDLES = {
        "messages", "messages_en", "messages_zh", "messages_fr", "messages_de", "messages_ja"
    };

    private static Properties load(String bundle) throws Exception {
        try (InputStream in = SuggestionFailureCodeTest.class.getResourceAsStream("/i18n/" + bundle + ".properties")) {
            assertNotNull(in, bundle + ".properties must be on the classpath");
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    @Test
    void everyFailureCodeHasALabelInEveryBundle() throws Exception {
        for (String bundle : BUNDLES) {
            Properties p = load(bundle);
            for (String key : SuggestionFailures.KEYS) {
                String value = p.getProperty(key);
                assertNotNull(value, bundle + " is missing " + key);
                assertTrue(value.isBlank() == false, bundle + " has an empty label for " + key);
            }
        }
    }

    @Test
    void aCodeRoundTripsToKeyAndDetail() {
        String code = SuggestionFailures.code(SuggestionFailures.KEY_TRUNCATED, "finish_reason=length");
        assertEquals("code:ai.fail.truncated|finish_reason=length", code);
        assertEquals(SuggestionFailures.KEY_TRUNCATED, SuggestionFailures.keyOf(code));
        assertEquals("finish_reason=length", SuggestionFailures.detailOf(code));
        // Rendered for the UI: the key is replaced by its label, the detail stays as it is.
        assertEquals("ai.fail.truncated (finish_reason=length)", SuggestionFailures.render(code));
    }

    @Test
    void aCodeWithoutADetailIsJustItsKey() {
        String code = SuggestionFailures.code(SuggestionFailures.KEY_NO_ANSWER);
        assertEquals(SuggestionFailures.KEY_NO_ANSWER, SuggestionFailures.keyOf(code));
        assertNull(SuggestionFailures.detailOf(code));
        assertEquals(SuggestionFailures.KEY_NO_ANSWER, SuggestionFailures.render(code));
    }

    /** Reasons that are already display text (HTTP failures, timeouts) must pass through. */
    @Test
    void plainTextReasonsAreNotTouched() {
        String text = "HTTP 429 from http://api.example.com/v1/chat/completions";
        assertNull(SuggestionFailures.keyOf(text));
        assertEquals(text, SuggestionFailures.render(text));
        assertNull(SuggestionFailures.keyOf(null));
        assertNull(SuggestionFailures.render(null));
    }
}

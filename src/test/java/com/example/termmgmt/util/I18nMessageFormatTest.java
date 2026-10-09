package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value written MessageFormat-style ({@code {0}}) is rendered with {@link java.text.MessageFormat},
 * where a single apostrophe opens a quoted section: everything after it is literal, so a
 * {@code {0}} behind an apostrophe is shown as the text "{0}" and the apostrophe itself vanishes.
 * French is the language that trips over this ("Échec de l'exportation : {0}" lost the error
 * detail), so every bundle is rendered here and checked, instead of trusting the translator.
 */
class I18nMessageFormatTest {

    private static final String[] BUNDLES = {
        "messages", "messages_en", "messages_zh", "messages_fr", "messages_de", "messages_ja"
    };

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d)\\}");

    private static Properties load(String bundle) throws Exception {
        try (InputStream in = I18nMessageFormatTest.class.getResourceAsStream("/i18n/" + bundle + ".properties")) {
            assertNotNull(in, bundle + ".properties must be on the classpath");
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    @Test
    void everyPlaceholderIsFilledWhenTheMessageIsRendered() throws Exception {
        List<String> problems = new ArrayList<>();
        Object[] args = new Object[10];
        for (int i = 0; i < args.length; i++) {
            args[i] = "<<" + i + ">>";
        }
        for (String bundle : BUNDLES) {
            Properties p = load(bundle);
            for (String key : new TreeSet<>(p.stringPropertyNames())) {
                String value = p.getProperty(key);
                Matcher m = PLACEHOLDER.matcher(value);
                java.util.Set<Integer> indexes = new TreeSet<>();
                while (m.find()) {
                    indexes.add(m.group(1).charAt(0) - '0');
                }
                if (indexes.isEmpty()) {
                    continue;
                }
                String rendered = MessageUtils.formatSafely(value, args);
                for (int index : indexes) {
                    if (!rendered.contains("<<" + index + ">>")) {
                        problems.add(bundle + " " + key + ": {" + index + "} is not substituted -> " + rendered);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "unrendered placeholders (write an apostrophe as '' in a "
            + "MessageFormat-style value):\n" + String.join("\n", problems));
    }

    /**
     * A single apostrophe behind the last placeholder leaves every placeholder filled but still
     * drops the apostrophe ("n'existe" -> "nexiste"), so the rule is checked on the raw value too.
     */
    @Test
    void messageFormatValuesNeverHoldASingleApostrophe() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String bundle : BUNDLES) {
            Properties p = load(bundle);
            for (String key : new TreeSet<>(p.stringPropertyNames())) {
                String value = p.getProperty(key);
                if (!PLACEHOLDER.matcher(value).find()) {
                    continue;
                }
                if (value.replace("''", "").contains("'")) {
                    problems.add(bundle + " " + key + ": " + value);
                }
            }
        }
        assertTrue(problems.isEmpty(), "single apostrophe in a MessageFormat-style value:\n"
            + String.join("\n", problems));
    }
}

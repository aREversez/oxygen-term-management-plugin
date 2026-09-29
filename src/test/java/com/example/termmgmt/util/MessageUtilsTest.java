package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageUtilsTest {

    @Test
    void formatSafely_formatsNormally() {
        assertEquals("Found 3 of 10", MessageUtils.formatSafely("Found %d of %d", 3, 10));
        assertEquals("Hello 世界", MessageUtils.formatSafely("Hello %s", "世界"));
    }

    @Test
    void formatSafely_returnsPatternWhenNoArguments() {
        assertEquals("100% done", MessageUtils.formatSafely("100% done"));
    }

    @Test
    void formatSafely_stray_percent_doesNotThrow() {
        assertEquals("Loaded 100% of %s", MessageUtils.formatSafely("Loaded 100% of %s", "x"));
    }

    @Test
    void formatSafely_missingArgument_doesNotThrow() {
        assertEquals("%s and %s", MessageUtils.formatSafely("%s and %s", "only-one"));
    }

    @Test
    void formatSafely_wrongArgumentType_doesNotThrow() {
        assertEquals("%d items", MessageUtils.formatSafely("%d items", "many"));
    }

    @Test
    void formatSafely_nullPattern_returnsNull() {
        assertNull(MessageUtils.formatSafely(null, "x"));
    }
}

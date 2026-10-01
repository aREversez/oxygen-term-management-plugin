package com.example.termmgmt.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pattern cache must not hand out a pattern compiled under the previous
 * case-sensitivity setting after the option is toggled.
 */
class TermbaseRegistryMatchPatternTest {

    private final TermbaseRegistry registry = TermbaseRegistry.getInstance();

    @AfterEach
    void restoreDefaultOption() {
        registry.setCaseSensitive(false);
    }

    @Test
    void getMatchPattern_reflectsCaseSensitiveOptionAfterToggle() {
        registry.setCaseSensitive(false);
        assertTrue(registry.getMatchPattern("FEA").matcher("fea").find());

        registry.setCaseSensitive(true);
        assertFalse(
            registry.getMatchPattern("FEA").matcher("fea").find(),
            "cached pattern from the insensitive setting leaked through");
        assertTrue(registry.getMatchPattern("FEA").matcher("FEA").find());

        registry.setCaseSensitive(false);
        assertTrue(registry.getMatchPattern("FEA").matcher("fea").find());
    }
}

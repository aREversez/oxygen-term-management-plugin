package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MarkupMasker must blank out markup while keeping every character position:
 * length identical, text content untouched.
 */
class MarkupMaskerTest {

    private static void assertSameLengthAndSpaces(String masked, int from, int to) {
        for (int i = from; i < to; i++) {
            assertEquals(' ', masked.charAt(i), "expected blank at " + i);
        }
    }

    @Test
    void mask_plainTextIsUnchanged() {
        String in = "no markup here";
        assertEquals(in, MarkupMasker.mask(in));
    }

    @Test
    void mask_tagWithQuotedAngleBracket_isFullyBlanked() {
        String in = "<p class=\"a>b\">hello</p>";
        String out = MarkupMasker.mask(in);
        assertEquals(in.length(), out.length());
        assertEquals("hello", out.substring(15, 20));
        assertSameLengthAndSpaces(out, 0, 15);
        assertSameLengthAndSpaces(out, 20, in.length());
    }

    @Test
    void mask_comment_isFullyBlanked() {
        String in = "<!-- <weird> note -->visible";
        String out = MarkupMasker.mask(in);
        assertEquals(in.length(), out.length());
        assertEquals("visible", out.substring(21));
        assertSameLengthAndSpaces(out, 0, 21);
    }

    @Test
    void mask_processingInstruction_isFullyBlanked() {
        String in = "<?xml version=\"1.0\"?>body";
        String out = MarkupMasker.mask(in);
        assertEquals(in.length(), out.length());
        assertEquals("body", out.substring(21));
        assertSameLengthAndSpaces(out, 0, 21);
    }

    @Test
    void mask_doctypeWithInternalSubset_blanksUpToTheClosingBracketGreater() {
        // The '>' inside the quoted attribute must not end the declaration, and the
        // subset's closing '>' only ends it after ']'.
        String in = "<!DOCTYPE r [<!ENTITY x \"y\">]>tail";
        String out = MarkupMasker.mask(in);
        assertEquals(in.length(), out.length());
        assertEquals("tail", out.substring(30));
        assertSameLengthAndSpaces(out, 0, 30);
    }

    @Test
    void mask_cdata_blanksMarkersButKeepsContent() {
        String in = "a<![CDATA[b > c]]>d";
        String out = MarkupMasker.mask(in);
        assertEquals(in.length(), out.length());
        assertEquals("b > c", out.substring(10, 15));
        assertEquals('a', out.charAt(0));
        assertEquals('d', out.charAt(18));
        assertSameLengthAndSpaces(out, 1, 10);
        assertSameLengthAndSpaces(out, 15, 18);
    }

    @Test
    void mask_isIdempotent() {
        String in = "<t a=\">\">x</t><!--c--><![CDATA[y]]>";
        String once = MarkupMasker.mask(in);
        assertEquals(once, MarkupMasker.mask(once));
    }
}

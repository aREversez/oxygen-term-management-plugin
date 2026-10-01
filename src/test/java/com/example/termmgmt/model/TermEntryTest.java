package com.example.termmgmt.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TermEntryTest {

    @Test
    void getExtraFields_neverReturnsNull() {
        assertNotNull(new TermEntry("a", "b").getExtraFields());
        TermEntry e = new TermEntry("a", "b");
        e.setExtraFields(null);
        assertNotNull(e.getExtraFields());
    }

    @Test
    void copy_carriesAllFieldsIncludingExtrasAndEntryId() {
        TermEntry original = new TermEntry("刚度", "stiffness", "C:/tb.csv");
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("domain", "mechanics");
        extras.put("note", "NBR term");
        original.setExtraFields(extras);
        original.setEntryId("myEntry42");

        TermEntry copy = original.copy();
        assertEquals("刚度", copy.getSourceTerm());
        assertEquals("stiffness", copy.getTargetTerm());
        assertEquals("C:/tb.csv", copy.getSourceFilePath());
        assertEquals("myEntry42", copy.getEntryId());
        assertEquals(extras, copy.getExtraFields());
    }

    @Test
    void copy_extraFieldsIsDeep_soLaterEditsDoNotLeakIntoTheOriginal() {
        TermEntry original = new TermEntry("a", "b");
        original.getExtraFields().put("status", "preferred");

        TermEntry copy = original.copy();
        copy.getExtraFields().put("status", "deprecated");
        copy.setEntryId("id1");

        assertEquals("preferred", original.getExtraFields().get("status"));
        assertNull(original.getEntryId());
    }

    @Test
    void toString_equalsBehaviour_unchangedByExtraFields() {
        TermEntry a = new TermEntry("x", "y");
        a.getExtraFields().put("note", "n");
        assertEquals("TermEntry{source='x', target='y'}", a.toString());
    }
}

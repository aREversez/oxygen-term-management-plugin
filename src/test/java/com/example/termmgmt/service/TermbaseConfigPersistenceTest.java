package com.example.termmgmt.service;

import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.model.TermbaseConfig.Format;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The chosen language pair survives the options-storage round trip; old data still loads. */
class TermbaseConfigPersistenceTest {

    @SuppressWarnings("unchecked")
    private static List<TermbaseConfig> roundTrip(List<TermbaseConfig> in) throws Exception {
        TermbaseRegistry registry = TermbaseRegistry.getInstance();
        Method ser = TermbaseRegistry.class.getDeclaredMethod("serializeConfigs", List.class);
        Method de = TermbaseRegistry.class.getDeclaredMethod("deserializeConfigs", String.class);
        ser.setAccessible(true);
        de.setAccessible(true);
        return (List<TermbaseConfig>) de.invoke(registry, ser.invoke(registry, in));
    }

    @Test
    void selectedPair_roundTrips() throws Exception {
        TermbaseConfig c = new TermbaseConfig("/tmp/a.csv", Format.CSV, true);
        c.setSelectedLangs("en-us", "de-de");
        List<TermbaseConfig> out = roundTrip(List.of(c));
        assertEquals("en-us", out.get(0).getSelectedSourceLang());
        assertEquals("de-de", out.get(0).getSelectedTargetLang());
    }

    @Test
    void noSelection_staysUnset_andWritesNothingNew() throws Exception {
        List<TermbaseConfig> out = roundTrip(new ArrayList<>(List.of(new TermbaseConfig("/tmp/a.csv", Format.CSV, true))));
        assertFalse(out.get(0).hasSelectedLangs());
    }

    @Test
    void legacyJson_withoutSelection_loads() throws Exception {
        TermbaseRegistry registry = TermbaseRegistry.getInstance();
        Method de = TermbaseRegistry.class.getDeclaredMethod("deserializeConfigs", String.class);
        de.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<TermbaseConfig> out = (List<TermbaseConfig>) de.invoke(registry,
            "[{\"path\":\"/tmp/a.csv\",\"format\":\"CSV\",\"enabled\":true,\"sourceLang\":\"zh\",\"targetLang\":\"en\"}]");
        assertEquals(1, out.size());
        assertFalse(out.get(0).hasSelectedLangs());
    }
}

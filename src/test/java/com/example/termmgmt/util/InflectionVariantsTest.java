package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InflectionVariantsTest {

    private static void assertHas(String term, String... expected) {
        List<String> v = InflectionVariants.variantsOf(term);
        for (String e : expected) {
            assertTrue(v.contains(e), term + " should produce " + e + " but produced " + v);
        }
    }

    private static void assertLacks(String term, String... unexpected) {
        List<String> v = InflectionVariants.variantsOf(term);
        for (String u : unexpected) {
            assertFalse(v.contains(u), term + " should not produce " + u + " but produced " + v);
        }
    }

    @Test
    void regularNoun_plural() {
        assertHas("grid", "grids");
        assertHas("node", "nodes");
        assertHas("element", "elements");
    }

    @Test
    void sibilantEnding_takesEs() {
        assertHas("mesh", "meshes");
        assertHas("stress", "stresses");
        assertHas("box", "boxes");
        assertHas("match", "matches");
        assertHas("buzz", "buzzes");
        assertLacks("mesh", "meshs");
    }

    @Test
    void consonantY_becomesIes_vowelYJustTakesS() {
        assertHas("study", "studies", "studied", "studying");
        assertHas("assembly", "assemblies");
        assertHas("display", "displays", "displayed", "displaying");
        assertLacks("display", "displaies", "displaied");
    }

    @Test
    void silentE_dropsBeforeIngAndTakesDInPast() {
        assertHas("solve", "solves", "solved", "solving");
        assertHas("compute", "computes", "computed", "computing");
        assertLacks("solve", "solveed", "solveing");
    }

    @Test
    void doubleE_keepsBothBeforeIng() {
        assertHas("agree", "agrees", "agreed", "agreeing");
    }

    @Test
    void ie_becomesYing() {
        assertHas("tie", "ties", "tied", "tying");
    }

    @Test
    void shortConsonantVowelConsonant_doublesAndAlsoKeepsThePlainForm() {
        assertHas("grid", "gridded", "gridding", "grided", "griding");
        assertHas("run", "runned", "running", "runing");
        assertHas("map", "mapped", "mapping");
        assertHas("plot", "plotted", "plotting");
    }

    @Test
    void finalWXY_neverDouble() {
        assertLacks("flow", "flowwed", "flowwing");
        assertLacks("fix", "fixxed", "fixxing");
        assertHas("flow", "flowed", "flowing");
        assertHas("fix", "fixed", "fixing", "fixes");
    }

    @Test
    void doubleConsonant_doesNotDoubleAgain() {
        assertLacks("mesh", "meshhed", "meshhing");
        assertHas("mesh", "meshed", "meshing");
        assertLacks("load", "loadded");
        assertHas("load", "loaded", "loading", "loads");
    }

    @Test
    void oEnding_getsBothSAndEs() {
        assertHas("hero", "heros", "heroes");
        assertHas("video", "videos");
        assertLacks("video", "videoes");
    }

    @Test
    void fEnding_getsAVesPlural() {
        assertHas("shelf", "shelves", "shelfs");
        assertHas("life", "lives", "lifes");
    }

    @Test
    void irregularPlurals_useTheTable() {
        assertEquals(List.of("matrices"), InflectionVariants.variantsOf("matrix"));
        assertHas("vertex", "vertices");
        assertHas("index", "indices", "indexes");
        assertHas("axis", "axes");
        assertHas("analysis", "analyses");
        assertHas("basis", "bases");
        assertHas("child", "children");
        assertHas("datum", "data");
        assertHas("criterion", "criteria");
        assertLacks("matrix", "matrixes", "matrixed", "matrixing");
        assertLacks("axis", "axises", "axised");
    }

    @Test
    void irregularPlural_keepsTheCaseOfTheOriginal() {
        assertEquals(List.of("Matrices"), InflectionVariants.variantsOf("Matrix"));
        assertEquals(List.of("MATRICES"), InflectionVariants.variantsOf("MATRIX"));
    }

    @Test
    void onlyTheLastWordVaries() {
        assertEquals(List.of("mesh sizes", "mesh sized", "mesh sizing"), InflectionVariants.variantsOf("mesh size").subList(0, 3));
        assertHas("mesh size", "mesh sizes");
        assertLacks("mesh size", "meshes size", "meshed size");
    }

    @Test
    void hyphenatedCompound_variesTheTailAfterTheHyphen() {
        assertHas("finite-element", "finite-elements");
        assertHas("pre-process", "pre-processes", "pre-processed", "pre-processing");
    }

    @Test
    void acronym_getsOnlyTheSPlural() {
        assertEquals(List.of("CPUs"), InflectionVariants.variantsOf("CPU"));
        assertEquals(List.of("CAEs"), InflectionVariants.variantsOf("CAE"));
        assertEquals(List.of("a CPUs"), InflectionVariants.variantsOf("a CPU"));
    }

    @Test
    void mixedCase_keepsTheStem() {
        assertHas("Grid", "Grids", "Gridded");
    }

    @Test
    void tooShortOrNotLetters_yieldNothing() {
        assertTrue(InflectionVariants.variantsOf("FE").isEmpty());
        assertTrue(InflectionVariants.variantsOf("ab").isEmpty());
        assertTrue(InflectionVariants.variantsOf("mesh2").isEmpty());
        assertTrue(InflectionVariants.variantsOf("3D").isEmpty());
        assertTrue(InflectionVariants.variantsOf("mesh.").isEmpty());
        assertTrue(InflectionVariants.variantsOf("R&amp;D").isEmpty());
        assertTrue(InflectionVariants.variantsOf("").isEmpty());
        assertTrue(InflectionVariants.variantsOf(null).isEmpty());
    }

    @Test
    void cjkTerm_yieldsNothing_andCjkPrefixIsKept() {
        assertTrue(InflectionVariants.variantsOf("网格").isEmpty());
        assertHas("网格 mesh", "网格 meshes");
    }

    @Test
    void wordGluedToANonLetterPrefix_isLeftAlone() {
        assertTrue(InflectionVariants.variantsOf("x2mesh").isEmpty());
        assertTrue(InflectionVariants.variantsOf("网mesh").isEmpty());
    }

    @Test
    void neverIncludesTheTermItself_orDuplicates() {
        for (String t : new String[] {"grid", "mesh", "study", "solve", "tie", "series", "CPU", "Matrix"}) {
            List<String> v = InflectionVariants.variantsOf(t);
            assertFalse(v.contains(t), t);
            assertEquals(v.size(), v.stream().distinct().count(), t + " " + v);
        }
    }

    @Test
    void uncountableIrregulars_mapToThemselves_andSoYieldNothing() {
        assertTrue(InflectionVariants.variantsOf("series").isEmpty());
        assertTrue(InflectionVariants.variantsOf("species").isEmpty());
    }

    @Test
    void resultIsStableAcrossCalls() {
        assertEquals(InflectionVariants.variantsOf("mesh size"), InflectionVariants.variantsOf("mesh size"));
    }
}

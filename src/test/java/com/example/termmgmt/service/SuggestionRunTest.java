package com.example.termmgmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every term of a run must end up translated, failed (with a reason) or counted as not requested.
 * A term the model returned nothing for used to be dropped without a trace.
 */
class SuggestionRunTest {

    private static final TranslationSuggester.Config CONFIG =
        new TranslationSuggester.Config("http://localhost:8080/v1", "m", "k", true);

    private static String reply(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + content + "}}]}";
    }

    /** Answers "T:<term>" for every term, except those listed in {@code special}. */
    private static HttpTransport byTerm(java.util.Map<String, String> special) {
        return (url, headers, body) -> {
            for (var e : special.entrySet()) {
                if (body.contains("\"content\":\"" + e.getKey() + "\"")) {
                    return e.getValue();
                }
            }
            String term = body.substring(body.lastIndexOf("\"content\":\"") + 11);
            term = term.substring(0, term.indexOf('"'));
            return reply("\"T:" + term + "\"");
        };
    }

    private static SuggestionRun.Outcome run(List<String> terms, HttpTransport t) {
        return SuggestionRun.run(terms, "en", "zh", CONFIG, t, () -> false, null);
    }

    @Test
    void everyTermTranslated_noOmissions() {
        SuggestionRun.Outcome o = run(List.of("alpha", "beta"), byTerm(java.util.Map.of()));
        assertEquals("T:alpha", o.translations().get(0));
        assertEquals("T:beta", o.translations().get(1));
        assertEquals(0, o.omitted());
        assertNull(o.firstError());
    }

    @Test
    void aTermTheModelReturnsNothingFor_isReportedNotDropped() {
        SuggestionRun.Outcome o = run(List.of("alpha", "beta", "gamma"), byTerm(java.util.Map.of(
            "beta", reply("\"\""))));
        assertEquals(List.of(0, 2), new ArrayList<>(o.translations().keySet()));
        assertEquals(List.of(1), new ArrayList<>(o.failures().keySet()));
        assertEquals(1, o.omitted());
        assertEquals(0, o.unprocessed());
        assertFalse(o.aborted());
    }

    @Test
    void aNullContent_isReportedWithAReason() {
        SuggestionRun.Outcome o = run(List.of("alpha", "beta"), byTerm(java.util.Map.of(
            "alpha", reply("null"))));
        assertEquals(List.of(0), new ArrayList<>(o.failures().keySet()));
        assertTrue(o.failures().get(0).toLowerCase().contains("empty"), o.failures().get(0));
        assertEquals("T:beta", o.translations().get(1));
    }

    @Test
    void aFailingEndpoint_stopsAfterThreeAndCountsTheRestAsNotRequested() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport down = (url, headers, body) -> {
            calls.incrementAndGet();
            throw new IOException("HTTP 401 from http://x/v1/chat/completions");
        };
        SuggestionRun.Outcome o = run(List.of("a", "b", "c", "d", "e"), down);
        assertEquals(3, calls.get());
        assertTrue(o.aborted());
        assertEquals(3, o.failures().size());
        assertEquals(2, o.unprocessed());
        assertEquals(5, o.omitted());
        assertTrue(o.firstError().contains("401"));
    }

    @Test
    void cancelling_stopsBeforeTheNextRequest_andIsNotAnAbort() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport t = (url, headers, body) -> {
            calls.incrementAndGet();
            return reply("\"ok\"");
        };
        SuggestionRun.Outcome o = SuggestionRun.run(List.of("a", "b", "c"), "en", "zh", CONFIG, t,
            () -> calls.get() >= 1, null);
        assertEquals(1, calls.get());
        assertEquals(1, o.translations().size());
        assertEquals(2, o.unprocessed());
        assertFalse(o.aborted());
    }

    @Test
    void progressIsReportedBeforeEachRequest() {
        List<String> seen = new ArrayList<>();
        SuggestionRun.run(List.of("alpha", "beta"), "en", "zh", CONFIG, byTerm(java.util.Map.of()),
            () -> false, (done, total, current) -> seen.add(done + "/" + total + ":" + current));
        assertEquals(List.of("0/2:alpha", "1/2:beta", "2/2:"), seen);
    }
}

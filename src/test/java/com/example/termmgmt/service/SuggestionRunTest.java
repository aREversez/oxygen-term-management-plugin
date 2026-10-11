package com.example.termmgmt.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every term of a run ends up translated, failed (with a reason) or counted as not requested, and
 * the termbase references are sent once per batch of terms instead of once per term.
 */
class SuggestionRunTest {

    private static final TranslationSuggester.Config CONFIG =
        new TranslationSuggester.Config("http://localhost:8080/v1", "m", "k", true);

    private static final List<TranslationSuggester.Reference> NO_REFS = List.of();

    private static String reply(String contentJson) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + contentJson + "}}]}";
    }

    private static String text(String s) {
        return new Gson().toJson(s);
    }

    /** A model fake: {@code answer} maps a term to its translation, or null for "returns nothing". */
    private static final class FakeModel implements HttpTransport {
        final Function<String, String> answer;
        final List<String> systemMessages = new ArrayList<>();
        final List<String> userMessages = new ArrayList<>();
        int batchRequests;
        int singleRequests;

        FakeModel(Function<String, String> answer) {
            this.answer = answer;
        }

        @Override
        public String post(String url, Map<String, String> headers, String body) {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String system = root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            String user = root.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
            systemMessages.add(system);
            userMessages.add(user);
            if (system.startsWith("You translate terminology")) {
                batchRequests++;
                JsonObject numbered = JsonParser.parseString(user).getAsJsonObject();
                JsonObject out = new JsonObject();
                for (String number : numbered.keySet()) {
                    String t = answer.apply(numbered.get(number).getAsString());
                    if (t != null) {
                        out.addProperty(number, t);
                    }
                }
                return reply(text(out.toString()));
            }
            singleRequests++;
            String t = answer.apply(user);
            return reply(t == null ? "\"\"" : text(t));
        }

        int requests() {
            return batchRequests + singleRequests;
        }
    }

    private static List<String> terms(int n) {
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            terms.add("term" + i);
        }
        return terms;
    }

    private static SuggestionRun.Outcome run(List<String> terms, HttpTransport t,
                                             List<TranslationSuggester.Reference> refs) {
        return SuggestionRun.run(terms, "en", "zh", refs, CONFIG, t, () -> false, null);
    }

    // ------------------------------------------------------------------ batching

    @Test
    void everyTermTranslated_inOneRequest_noOmissions() {
        FakeModel model = new FakeModel(t -> "T:" + t);
        SuggestionRun.Outcome o = run(List.of("alpha", "beta"), model, NO_REFS);
        assertEquals("T:alpha", o.translations().get(0));
        assertEquals("T:beta", o.translations().get(1));
        assertEquals(0, o.omitted());
        assertNull(o.firstError());
        assertEquals(1, model.requests());
    }

    @Test
    void manyTerms_goInBatches_notOneRequestPerTerm() {
        FakeModel model = new FakeModel(t -> "T:" + t);
        SuggestionRun.Outcome o = run(terms(45), model, NO_REFS);
        assertEquals(45, o.translations().size());
        assertEquals("T:term44", o.translations().get(44));
        assertEquals(3, model.requests(), "45 terms = batches of 20, 20 and 5");
    }

    @Test
    void referencesAreSentOncePerBatch_notOncePerTerm() {
        List<TranslationSuggester.Reference> refs = List.of(
            new TranslationSuggester.Reference("hydraulic pump", "液压泵"),
            new TranslationSuggester.Reference("check valve", "单向阀"));
        FakeModel model = new FakeModel(t -> "T:" + t);
        run(terms(45), model, refs);

        assertEquals(3, model.systemMessages.size());
        for (String system : model.systemMessages) {
            assertTrue(system.contains("- hydraulic pump => 液压泵"), system);
            assertTrue(system.contains("- check valve => 单向阀"), system);
        }
        for (String user : model.userMessages) {
            assertFalse(user.contains("液压泵"), "references belong in the system message only");
        }
        assertEquals(model.systemMessages.get(0), model.systemMessages.get(1),
            "an identical prefix across batches lets the provider cache it");
    }

    // ------------------------------------------------------------------ omissions

    @Test
    void aTermTheModelSkipped_isAskedAgainOnItsOwn() {
        AtomicInteger asked = new AtomicInteger();
        FakeModel model = new FakeModel(t -> {
            if (t.equals("beta") && asked.getAndIncrement() == 0) return null;   // skipped in the batch only
            return "T:" + t;
        });
        SuggestionRun.Outcome o = run(List.of("alpha", "beta", "gamma"), model, NO_REFS);
        assertEquals("T:beta", o.translations().get(1));
        assertEquals(0, o.omitted());
        assertEquals(1, model.batchRequests);
        assertEquals(1, model.singleRequests);
    }

    @Test
    void aTermTheModelNeverAnswers_isReportedNotDropped() {
        FakeModel model = new FakeModel(t -> t.equals("beta") ? null : "T:" + t);
        SuggestionRun.Outcome o = run(List.of("alpha", "beta", "gamma"), model, NO_REFS);
        assertEquals(List.of(0, 2), new ArrayList<>(o.translations().keySet()));
        assertEquals(List.of(1), new ArrayList<>(o.failures().keySet()));
        assertEquals(1, o.omitted());
        assertEquals(0, o.unprocessed());
        assertFalse(o.aborted());
    }

    @Test
    void aReplyThatIsNotTheRequestedJson_fallsBackToOneRequestPerTerm() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport chatty = (url, headers, body) -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String system = root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            calls.incrementAndGet();
            return system.startsWith("You translate terminology")
                ? reply("\"Sure! Here are the translations you asked for.\"")
                : reply("\"ok\"");
        };
        SuggestionRun.Outcome o = run(List.of("alpha", "beta"), chatty, NO_REFS);
        assertEquals(2, o.translations().size());
        assertEquals(3, calls.get(), "one batch request, then one per term");
    }

    @Test
    void answersWithUnknownNumbers_doNotLandOnTheWrongTerm() {
        HttpTransport shifted = (url, headers, body) -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String system = root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            return system.startsWith("You translate terminology")
                ? reply(text("{\"2\":\"B\",\"7\":\"nowhere\"}"))
                : reply("\"\"");
        };
        SuggestionRun.Outcome o = run(List.of("alpha", "beta"), shifted, NO_REFS);
        assertEquals(Map.of(1, "B"), o.translations());
        assertEquals(List.of(0), new ArrayList<>(o.failures().keySet()));
    }

    @Test
    void aTruncatedBatchReply_keepsTheAnswersItGot_andAsksOnlyForTheRest() {
        // A reply cut off mid-JSON used to void the whole batch, exploding into one full-size
        // request per term. Now the closed pairs survive and only the gaps are re-asked.
        AtomicInteger singles = new AtomicInteger();
        HttpTransport truncated = (url, headers, body) -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String system = root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            if (system.startsWith("You translate terminology")) {
                return "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":"
                    + text("{\"1\": \"A\", \"2\": \"B\"}") + "}}]}";
            }
            singles.incrementAndGet();
            return reply("\"late\"");
        };
        SuggestionRun.Outcome o = run(List.of("t1", "t2", "t3"), truncated, NO_REFS);
        assertEquals("A", o.translations().get(0));
        assertEquals("B", o.translations().get(1));
        assertEquals("late", o.translations().get(2));
        assertEquals(1, singles.get(), "only the term the cut reply never reached is asked again");
    }

    @Test
    void aFallbackRequest_carriesATrimmedReferenceBlock_notTheFullOne() {
        List<TranslationSuggester.Reference> refs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            refs.add(new TranslationSuggester.Reference("source term " + i, "target term " + i));
        }
        AtomicInteger asked = new AtomicInteger();
        FakeModel model = new FakeModel(t -> {
            if (t.equals("term1") && asked.getAndIncrement() == 0) return null;   // skipped in the batch
            return "T:" + t;
        });
        run(List.of("term0", "term1", "term2"), model, refs);
        assertEquals(1, model.singleRequests);
        String fallbackSystem = model.systemMessages.get(model.systemMessages.size() - 1);
        long pairs = fallbackSystem.lines().filter(l -> l.startsWith("- ")).count();
        assertTrue(pairs <= SuggestionRun.FALLBACK_MAX_REFERENCE_PAIRS,
            "a fallback request re-sends the references the batch already paid for: " + pairs + " pairs");
        assertTrue(model.systemMessages.get(0).lines().filter(l -> l.startsWith("- ")).count() > pairs,
            "the batch itself keeps the full reference block");
    }

    @Test
    void translationsAreOrderedByTermIndex_evenWhenAFallbackFillsAnEarlierGap() {
        // term0 is only answered by a fallback request (the batch skips it), so insertion order
        // would list it last; the review dialog must still follow the table.
        AtomicInteger asked = new AtomicInteger();
        FakeModel model = new FakeModel(t -> {
            if (t.equals("term0") && asked.getAndIncrement() == 0) return null;
            return "T:" + t;
        });
        SuggestionRun.Outcome o = run(List.of("term0", "term1", "term2"), model, NO_REFS);
        assertEquals(List.of(0, 1, 2), new ArrayList<>(o.translations().keySet()));
    }

    @Test
    void cancellingDuringTheFallbackPhase_stopsBeforeTheNextFallback() {
        AtomicInteger singles = new AtomicInteger();
        HttpTransport skipping = (url, headers, body) -> {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String system = root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
            if (system.startsWith("You translate terminology")) {
                return reply(text("{}"));   // answered nothing: every term falls back
            }
            singles.incrementAndGet();
            return reply("\"x\"");
        };
        SuggestionRun.Outcome o = SuggestionRun.run(terms(5), "en", "zh", NO_REFS, CONFIG, skipping,
            () -> singles.get() >= 1, null);
        assertEquals(1, singles.get(), "cancel is honoured between fallback requests too");
        assertEquals(1, o.translations().size());
        assertEquals(4, o.unprocessed());
        assertFalse(o.aborted());
    }

    @Test
    void aLastBatchOfOne_isStillRequested() {
        FakeModel model = new FakeModel(t -> "T:" + t);
        SuggestionRun.Outcome o = run(terms(SuggestionRun.BATCH_SIZE * 2 + 1), model, NO_REFS);
        assertEquals(terms(SuggestionRun.BATCH_SIZE * 2 + 1).size(), o.translations().size());
        assertEquals(3, model.batchRequests, "20 + 20 + 1");
        assertEquals("T:term40", o.translations().get(40));
    }

    @Test
    void progressKeepsMovingWhileFallbacksRun() {
        AtomicInteger asked = new AtomicInteger();
        FakeModel model = new FakeModel(t -> {
            if (t.equals("term1") && asked.getAndIncrement() == 0) return null;
            return "T:" + t;
        });
        List<String> seen = new ArrayList<>();
        SuggestionRun.run(List.of("term0", "term1", "term2"), "en", "zh", NO_REFS, CONFIG, model,
            () -> false, (done, total, current) -> seen.add(done + "/" + total + ":" + current));
        assertTrue(seen.contains("2/3:term1"), "a fallback term must not stall the bar: " + seen);
    }

    // ------------------------------------------------------------------ failing endpoint, cancel, progress

    @Test
    void aFailingEndpoint_stopsAfterThreeRequests_andCountsTheRestAsNotRequested() {
        AtomicInteger calls = new AtomicInteger();
        HttpTransport down = (url, headers, body) -> {
            calls.incrementAndGet();
            throw new IOException("HTTP 401 from http://x/v1/chat/completions");
        };
        SuggestionRun.Outcome o = run(terms(100), down, NO_REFS);
        assertEquals(3, calls.get());
        assertTrue(o.aborted());
        assertEquals(60, o.failures().size());
        assertEquals(40, o.unprocessed());
        assertEquals(100, o.omitted());
        assertTrue(o.firstError().contains("401"));
    }

    @Test
    void cancelling_stopsBeforeTheNextRequest_andIsNotAnAbort() {
        FakeModel model = new FakeModel(t -> "T:" + t);
        SuggestionRun.Outcome o = SuggestionRun.run(terms(45), "en", "zh", NO_REFS, CONFIG, model,
            () -> model.requests() >= 1, null);
        assertEquals(1, model.requests());
        assertEquals(20, o.translations().size());
        assertEquals(25, o.unprocessed());
        assertFalse(o.aborted());
    }

    @Test
    void progressIsReportedBeforeEachBatch() {
        List<String> seen = new ArrayList<>();
        SuggestionRun.run(terms(25), "en", "zh", NO_REFS, CONFIG, new FakeModel(t -> "T:" + t),
            () -> false, (done, total, current) -> seen.add(done + "/" + total + ":" + current));
        assertEquals(List.of("0/25:term0", "20/25:term20", "25/25:"), seen);
    }

    // ------------------------------------------------------------------ choosing the references

    private static TranslationSuggester.Reference ref(String s, String t) {
        return new TranslationSuggester.Reference(s, t);
    }

    @Test
    void selectReferences_spreadsTheChoiceOverTheWholeTermbase() {
        List<TranslationSuggester.Reference> pool = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pool.add(ref("s" + i, "t" + i));
        }
        List<TranslationSuggester.Reference> picked = SuggestionRun.selectReferences(pool, 5, 10_000);
        assertEquals(List.of("s0", "s20", "s40", "s60", "s80"),
            picked.stream().map(TranslationSuggester.Reference::source).toList());
    }

    @Test
    void selectReferences_skipsDuplicatesSentencesAndMultilineEntries() {
        List<TranslationSuggester.Reference> picked = SuggestionRun.selectReferences(List.of(
            ref("pump", "泵"), ref("pump", "泵浦"), ref("a\nb", "x"), ref("x".repeat(81), "y"),
            ref("", "z"), ref("valve", " "), ref("seal", "密封件")), 10, 10_000);
        assertEquals(List.of("pump", "seal"),
            picked.stream().map(TranslationSuggester.Reference::source).toList());
    }

    @Test
    void selectReferences_staysWithinTheCharacterBudget() {
        List<TranslationSuggester.Reference> pool = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            pool.add(ref("source term " + i, "target term " + i));
        }
        List<TranslationSuggester.Reference> picked = SuggestionRun.selectReferences(pool, 50, 200);
        int chars = picked.stream().mapToInt(r -> r.source().length() + r.target().length() + 6).sum();
        assertTrue(chars <= 200, "chars=" + chars);
        assertFalse(picked.isEmpty());
    }

    @Test
    void selectReferences_emptyPoolOrNoBudget_givesNothing() {
        assertTrue(SuggestionRun.selectReferences(List.of(), 20, 2000).isEmpty());
        assertTrue(SuggestionRun.selectReferences(List.of(ref("a", "b")), 0, 2000).isEmpty());
    }
}

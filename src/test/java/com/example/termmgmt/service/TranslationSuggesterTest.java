package com.example.termmgmt.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TranslationSuggester} using a fake {@link HttpTransport}.
 * No real network or Oxygen SDK required.
 */
class TranslationSuggesterTest {

    // ------------------------------------------------------------------ Fake transport

    private static HttpTransport fakeTransport(String response) {
        return (url, headers, body) -> response;
    }

    private static HttpTransport throwingTransport(IOException ex) {
        return (url, headers, body) -> { throw ex; };
    }

    private static final String VALID_RESPONSE =
        "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"cloud computing\"}}]}";

    private static final TranslationSuggester.Config ENABLED_CONFIG =
        new TranslationSuggester.Config("http://localhost:8080/v1", "llama3", "sk-test-key-123", true);

    // ------------------------------------------------------------------ Happy path

    @Test
    void suggest_happyPath_returnsTranslation() throws IOException {
        SuggestionResult result = TranslationSuggester.suggest(
            "云计算", "zh-CN", "en-US", ENABLED_CONFIG, fakeTransport(VALID_RESPONSE));
        assertTrue(result.success());
        assertEquals("cloud computing", result.translation());
    }

    // ------------------------------------------------------------------ Disabled

    @Test
    void suggest_disabled_returnsEmpty() throws IOException {
        TranslationSuggester.Config disabled =
            new TranslationSuggester.Config("http://localhost:8080/v1", "llama3", null, false);
        SuggestionResult result = TranslationSuggester.suggest(
            "测试", "zh-CN", "en-US", disabled, fakeTransport(VALID_RESPONSE));
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("disabled"));
    }

    // ------------------------------------------------------------------ Config incomplete

    @Test
    void suggest_noUrl_fails() throws IOException {
        TranslationSuggester.Config bad =
            new TranslationSuggester.Config("", "llama3", null, true);
        SuggestionResult result = TranslationSuggester.suggest(
            "测试", "zh-CN", "en-US", bad, fakeTransport(VALID_RESPONSE));
        assertFalse(result.success());
    }

    // ------------------------------------------------------------------ Network error

    @Test
    void suggest_nonOkStatus_returnsError() {
        HttpTransport throwing = throwingTransport(new IOException("HTTP 429 from server"));
        SuggestionResult result = assertDoesNotThrow(() ->
            TranslationSuggester.suggest("测试", "zh-CN", "en-US", ENABLED_CONFIG, throwing));
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("429"));
    }

    // ------------------------------------------------------------------ Malformed JSON response

    @Test
    void parseResponse_malformedJson_graceful() {
        SuggestionResult result = TranslationSuggester.parseResponse("not json at all {{{");
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("parse"));
    }

    @Test
    void parseResponse_noChoices_graceful() {
        String json = "{\"id\":\"chatcmpl-123\",\"object\":\"chat.completion\"}";
        SuggestionResult result = TranslationSuggester.parseResponse(json);
        assertFalse(result.success());
    }

    @Test
    void parseResponse_emptyContent_graceful() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        SuggestionResult result = TranslationSuggester.parseResponse(json);
        assertFalse(result.success());
    }

    // ------------------------------------------------------------------ Replies without a usable translation

    private static String reply(String contentJson, String finishReason) {
        return "{\"choices\":[{\"finish_reason\":\"" + finishReason + "\",\"message\":{\"content\":"
            + contentJson + "}}]}";
    }

    @Test
    void parseResponse_nullContent_failsWithAReason_notAParseError() {
        SuggestionResult r = TranslationSuggester.parseResponse(reply("null", "stop"));
        assertFalse(r.success());
        assertFalse(r.errorMessage().contains("Failed to parse"), r.errorMessage());
    }

    @Test
    void parseResponse_thinkingIsStripped() {
        SuggestionResult r = TranslationSuggester.parseResponse(
            reply("\"<think>maybe a pump?\\nor a seal</think>\\n\\n轴承\"", "stop"));
        assertTrue(r.success());
        assertEquals("轴承", r.translation());
    }

    @Test
    void parseResponse_aReplyThatIsOnlyThinking_isAFailure() {
        assertFalse(TranslationSuggester.parseResponse(reply("\"<think>hmm\"", "length")).success());
        assertFalse(TranslationSuggester.parseResponse(reply("\"<think>hmm</think>\"", "stop")).success());
    }

    @Test
    void parseResponse_aClosingThinkTagWithoutAnOpeningOne() {
        SuggestionResult r = TranslationSuggester.parseResponse(
            reply("\"reasoning...</think>轴承\"", "stop"));
        assertTrue(r.success());
        assertEquals("轴承", r.translation());
    }

    @Test
    void parseResponse_cutOffByTheTokenLimit_isNotAcceptedAsATranslation() {
        SuggestionResult r = TranslationSuggester.parseResponse(reply("\"轴\"", "length"));
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("length"), r.errorMessage());
    }

    // ------------------------------------------------------------------ Batch request / reply

    private static String content(String c) {
        return "{\"choices\":[{\"message\":{\"content\":" + new com.google.gson.Gson().toJson(c) + "}}]}";
    }

    @Test
    void buildBatchRequest_numbersTheTerms_andKeepsTheReferencesInTheSystemMessage() {
        String json = TranslationSuggester.buildBatchRequest(java.util.List.of("pump", "seal"), "en", "zh", "m",
            java.util.List.of(new TranslationSuggester.Reference("valve", "阀")));
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String system = root.getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        String user = root.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("- valve => 阀"));
        assertFalse(user.contains("阀"));
        com.google.gson.JsonObject numbered = com.google.gson.JsonParser.parseString(user).getAsJsonObject();
        assertEquals("pump", numbered.get("1").getAsString());
        assertEquals("seal", numbered.get("2").getAsString());
    }

    @Test
    void buildBatchRequest_withoutReferences_hasNoReferenceSection() {
        String json = TranslationSuggester.buildBatchRequest(java.util.List.of("pump"), "en", "zh", "m",
            java.util.List.of());
        assertFalse(json.contains("Reference translations"));
    }

    @Test
    void buildRequest_withReferences_addsThemToTheSystemMessage_andWithoutKeepsTheOldPrompt() {
        String plain = TranslationSuggester.buildRequest("pump", "en", "zh", "m");
        assertEquals(plain, TranslationSuggester.buildRequest("pump", "en", "zh", "m", java.util.List.of()));
        String with = TranslationSuggester.buildRequest("pump", "en", "zh", "m",
            java.util.List.of(new TranslationSuggester.Reference("valve", "阀")));
        String system = com.google.gson.JsonParser.parseString(with).getAsJsonObject()
            .getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(system.contains("- valve => 阀"), system);
    }

    @Test
    void parseBatchResponse_readsTheNumberedAnswers() {
        TranslationSuggester.BatchResult r = TranslationSuggester.parseBatchResponse(
            content("{\"1\": \"泵\", \"2\": \" 密封件 \"}"), 2);
        assertNull(r.error());
        assertEquals(Map.of(1, "泵", 2, "密封件"), r.translations());
    }

    @Test
    void parseBatchResponse_toleratesACodeFenceAndThinking() {
        TranslationSuggester.BatchResult r = TranslationSuggester.parseBatchResponse(
            content("<think>domain: hydraulics</think>\n```json\n{\"1\":\"泵\"}\n```"), 1);
        assertEquals(Map.of(1, "泵"), r.translations());
    }

    @Test
    void parseBatchResponse_ignoresUnknownNumbersBlankAndNonTextValues() {
        TranslationSuggester.BatchResult r = TranslationSuggester.parseBatchResponse(
            content("{\"0\":\"a\",\"1\":\"\",\"2\":\"B\",\"3\":\"c\",\"x\":\"d\",\"2.5\":\"e\"}"), 2);
        assertEquals(Map.of(2, "B"), r.translations());
    }

    @Test
    void parseBatchResponse_notJson_isAnErrorNotARequestFailure() {
        TranslationSuggester.BatchResult r = TranslationSuggester.parseBatchResponse(content("Sure, here you go"), 2);
        assertTrue(r.translations().isEmpty());
        assertNotNull(r.error());
        assertFalse(r.requestFailed());
    }

    @Test
    void suggestBatch_aFailedRequest_isReportedAsRequestFailed() {
        TranslationSuggester.BatchResult r = TranslationSuggester.suggestBatch(java.util.List.of("a"), "en", "zh",
            java.util.List.of(), ENABLED_CONFIG, throwingTransport(new IOException("HTTP 401 from x")));
        assertTrue(r.requestFailed());
        assertTrue(r.error().contains("401"));
    }

    // ------------------------------------------------------------------ URL building

    @Test
    void buildUrl_withV1_appendsChatCompletions() {
        assertEquals("http://localhost:8080/v1/chat/completions",
            TranslationSuggester.buildUrl("http://localhost:8080/v1"));
    }

    @Test
    void buildUrl_withTrailingSlash() {
        assertEquals("http://localhost:8080/v1/chat/completions",
            TranslationSuggester.buildUrl("http://localhost:8080/v1/"));
    }

    @Test
    void buildUrl_fullPath_passthrough() {
        assertEquals("http://host/v1/chat/completions",
            TranslationSuggester.buildUrl("http://host/v1/chat/completions"));
    }

    @Test
    void buildUrl_bareHost_addsV1Path() {
        assertEquals("http://localhost:11434/v1/chat/completions",
            TranslationSuggester.buildUrl("http://localhost:11434"));
    }

    // ------------------------------------------------------------------ Scheme validation (via OpenAiHttpTransport)

    @Test
    void transport_rejectsNonHttpScheme() {
        OpenAiHttpTransport transport = new OpenAiHttpTransport();
        IOException ex = assertThrows(IOException.class, () ->
            transport.post("ftp://evil.com/file", Map.of(), "{}"));
        assertTrue(ex.getMessage().contains("http/https"));
    }

    @Test
    void transport_rejectsFileScheme() {
        OpenAiHttpTransport transport = new OpenAiHttpTransport();
        IOException ex = assertThrows(IOException.class, () ->
            transport.post("file:///etc/passwd", Map.of(), "{}"));
        assertTrue(ex.getMessage().contains("http/https"));
    }

    // ------------------------------------------------------------------ API key not in logs

    @Test
    void suggest_apiKeyNotInLogs() {
        String secret = "sk-super-secret-key-999";
        TranslationSuggester.Config config =
            new TranslationSuggester.Config("http://localhost:8080/v1", "model", secret, true);

        // Capture stderr
        java.io.ByteArrayOutputStream errBuf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream origErr = System.err;
        System.setErr(new java.io.PrintStream(errBuf));
        try {
            // Force an error so it logs
            HttpTransport failing = throwingTransport(new IOException("connection refused"));
            TranslationSuggester.suggest("x", "en", "de", config, failing);
        } catch (IOException ignored) {
        } finally {
            System.setErr(origErr);
        }
        String logged = errBuf.toString();
        assertFalse(logged.contains(secret), "API key must never appear in stderr log");
    }

    // ------------------------------------------------------------------ Request body structure

    @Test
    void buildRequest_containsModelAndMessages() {
        String json = TranslationSuggester.buildRequest("hello", "en-US", "zh-CN", "gpt-4");
        assertTrue(json.contains("\"model\":\"gpt-4\""));
        assertTrue(json.contains("Translate the following term from en-US to zh-CN"));
        assertTrue(json.contains("\"content\":\"hello\""));
    }

    // ------------------------------------------------------------------ RunGuard (serial batch runs)

    @Test
    void runGuard_abortsAfterThreeFailuresInARow() {
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        guard.recordFailure("a");
        guard.recordFailure("b");
        assertFalse(guard.shouldAbort());
        guard.recordFailure("c");
        assertTrue(guard.shouldAbort());
    }

    @Test
    void runGuard_aSuccessResetsTheStreak() {
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        guard.recordFailure("a");
        guard.recordFailure("b");
        guard.recordSuccess();
        guard.recordFailure("c");
        guard.recordFailure("d");
        assertFalse(guard.shouldAbort(), "failures separated by a success are not a streak");
    }

    @Test
    void runGuard_keepsTheFirstErrorAndNeverInventsOne() {
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        assertNull(guard.firstError());
        guard.recordFailure("HTTP 401 from http://localhost:8080/v1/chat/completions");
        guard.recordFailure("later error");
        assertEquals("HTTP 401 from http://localhost:8080/v1/chat/completions", guard.firstError());
    }

    @Test
    void runGuard_blankMessageStillCountsAsAnError() {
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        guard.recordFailure(null);
        assertEquals("unknown error", guard.firstError());
    }

    @Test
    void runGuard_unreachableEndpoint_stopsAfterThreeRequestsAndKeepsTheReason() throws IOException {
        int[] calls = {0};
        HttpTransport down = (url, headers, body) -> {
            calls[0]++;
            throw new IOException("HTTP 401 from http://localhost:8080/v1/chat/completions");
        };
        TranslationSuggester.RunGuard guard = new TranslationSuggester.RunGuard();
        // The loop the Terminology panel runs over up to 200 blank entries.
        for (int i = 0; i < 200 && !guard.shouldAbort(); i++) {
            SuggestionResult r = TranslationSuggester.suggest("term" + i, "zh-CN", "en-US", ENABLED_CONFIG, down);
            if (r.success()) guard.recordSuccess(); else guard.recordFailure(r.errorMessage());
        }
        assertEquals(TranslationSuggester.RunGuard.MAX_CONSECUTIVE_FAILURES, calls[0]);
        assertTrue(guard.firstError().contains("HTTP 401"));
    }
}

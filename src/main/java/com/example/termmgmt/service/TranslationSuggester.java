package com.example.termmgmt.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls an OpenAI-compatible chat-completions API to suggest translations for source terms
 * that have no target translation yet.
 *
 * <p>Security and default:
 * <ul>
 *   <li>Disabled by default ({@link Config#enabled()} must be true).</li>
 *   <li>API key is never written to logs.</li>
 *   <li>Only http/https URLs are allowed (enforced by {@link OpenAiHttpTransport}).</li>
 *   <li>Response size limited to 1 MB (enforced by transport).</li>
 * </ul>
 */
public final class TranslationSuggester {

    private TranslationSuggester() {}

    /** AI translation configuration, persisted via OptionsStorage. */
    public record Config(String apiUrl, String model, String apiKey, boolean enabled) {
        public boolean isValid() {
            return apiUrl != null && !apiUrl.isBlank()
                && model != null && !model.isBlank();
        }
    }

    /** An approved source/target pair from the termbase, sent as context so the model can tell the domain. */
    public record Reference(String source, String target) {}

    /**
     * Outcome of one batch request. {@code translations} maps the 1-based number of each term
     * to its translation (only the numbers the model answered); {@code requestFailed} is true when
     * the request itself failed (HTTP error, timeout) as opposed to the model answering badly.
     */
    public record BatchResult(java.util.Map<Integer, String> translations, String error,
                              boolean requestFailed) {}

    /**
     * Bookkeeping for a serial run of suggestion requests. It keeps the first error, so a run that
     * produced nothing can tell the user why, and says when to stop: several failures in a row
     * mean the address, key or model is wrong, and asking for the remaining terms (up to a couple
     * of hundred, each possibly waiting out the request timeout) would only repeat the failure.
     * Not thread-safe; one run uses one guard from one thread.
     */
    public static final class RunGuard {
        /** Consecutive failed requests after which a run is abandoned. */
        public static final int MAX_CONSECUTIVE_FAILURES = 3;

        private int consecutiveFailures;
        private String firstError;

        public void recordSuccess() {
            consecutiveFailures = 0;
        }

        public void recordFailure(String message) {
            consecutiveFailures++;
            if (firstError == null) {
                firstError = (message == null || message.isBlank()) ? "unknown error" : message;
            }
        }

        public boolean shouldAbort() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }

        /** The first failure of the run, or null when no request has failed. */
        public String firstError() {
            return firstError;
        }
    }

    /**
     * Request a translation suggestion for a single source term.
     *
     * @param sourceTerm the source-language term
     * @param sourceLang BCP-47 source language tag
     * @param targetLang BCP-47 target language tag
     * @param config     API configuration (must be enabled and valid)
     * @param transport  HTTP transport (injectable for testing)
     * @return the suggestion result
     */
    public static SuggestionResult suggest(String sourceTerm,
                                           String sourceLang,
                                           String targetLang,
                                           Config config,
                                           HttpTransport transport) throws IOException {
        return suggest(sourceTerm, sourceLang, targetLang, java.util.List.of(), config, transport);
    }

    /** As {@link #suggest(String, String, String, Config, HttpTransport)}, with termbase pairs as context. */
    public static SuggestionResult suggest(String sourceTerm,
                                           String sourceLang,
                                           String targetLang,
                                           java.util.List<Reference> references,
                                           Config config,
                                           HttpTransport transport) throws IOException {
        if (config == null || !config.enabled()) {
            return SuggestionResult.fail(SuggestionFailures.code(SuggestionFailures.KEY_DISABLED));
        }
        if (!config.isValid()) {
            return SuggestionResult.fail(SuggestionFailures.code(SuggestionFailures.KEY_INCOMPLETE_CONFIG));
        }

        String url = buildUrl(config.apiUrl());
        String requestBody = buildRequest(sourceTerm, sourceLang, targetLang, config.model(), references);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + config.apiKey());
        }

        try {
            String response = transport.post(url, headers, requestBody);
            return parseResponse(response);
        } catch (IOException e) {
            // Log without exposing API key
            System.err.println("TranslationSuggester: request to " + OpenAiHttpTransport.sanitizeUrl(url)
                + " model=" + config.model() + " failed: " + e.getMessage());
            return SuggestionResult.fail(e.getMessage());
        }
    }

    /**
     * Build the full endpoint URL from a base API URL.
     * Appends /v1/chat/completions if the base doesn't already end with it.
     */
    static String buildUrl(String base) {
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    /**
     * Reply budget for one batch request. A batch answer is roughly twenty times as long as a
     * single one, and a provider that stops at its own default limit cuts it off mid-JSON, which
     * used to throw away every answer of the batch. A batch therefore asks for
     * {@value #BATCH_REPLY_TOKEN_BASE} plus {@value #BATCH_REPLY_TOKENS_PER_TERM} tokens per term:
     * a term is a couple of tokens, the rest covers the separators and a model that reasons inside
     * the answer. Sent as a number so a provider that ignores it loses nothing.
     */
    static final int BATCH_REPLY_TOKEN_BASE = 200;
    static final int BATCH_REPLY_TOKENS_PER_TERM = 30;

    static int batchReplyTokens(int termCount) {
        return BATCH_REPLY_TOKEN_BASE + BATCH_REPLY_TOKENS_PER_TERM * termCount;
    }

    /**
     * Build the JSON request body for the chat completions endpoint.
     */
    static String buildRequest(String sourceTerm, String sourceLang, String targetLang, String model) {
        return buildRequest(sourceTerm, sourceLang, targetLang, model, java.util.List.of());
    }

    static String buildRequest(String sourceTerm, String sourceLang, String targetLang, String model,
                               java.util.List<Reference> references) {
        String system = "Translate the following term from " + sourceLang + " to " + targetLang
            + ". Reply with only the translation, nothing else.";
        if (references != null && !references.isEmpty()) {
            system += "\nThe term comes from a termbase. Infer its subject domain from the reference "
                + "translations below and translate it as it is used in that domain, keeping their "
                + "wording and style. The references are context only; do not return them.\n"
                + referenceLines(references);
        }
        return chatBody(model, system, sourceTerm);
    }

    /**
     * One request for several terms. The reference translations are part of the (identical across
     * batches) system message and are sent once per batch instead of once per term; the user
     * message is a JSON object of number to term, and the reply must be a JSON object of number to
     * translation, so an answer can be matched to its term and a missing one is detectable.
     */
    static String buildBatchRequest(java.util.List<String> terms, String sourceLang, String targetLang,
                                    String model, java.util.List<Reference> references) {
        boolean hasRefs = references != null && !references.isEmpty();
        StringBuilder system = new StringBuilder()
            .append("You translate terminology from ").append(sourceLang).append(" to ").append(targetLang)
            .append(".\nThe numbered terms in the user message (a JSON object of number to term) all come ")
            .append("from the same termbase. Infer the subject domain from ")
            .append(hasRefs ? "the reference translations below and " : "")
            .append("the terms themselves, and translate each term as it is used in that domain")
            .append(hasRefs ? ", keeping the wording and style of the references" : "")
            .append(".\n");
        if (hasRefs) {
            system.append("Reference translations (approved entries from the same termbase, context only; ")
                .append("do not return them):\n").append(referenceLines(references)).append('\n');
        }
        system.append("Reply with only a JSON object that maps each term's number to its translation, ")
            .append("for example {\"1\": \"...\", \"2\": \"...\"}. Include every number. No explanations.");

        JsonObject numbered = new JsonObject();
        for (int i = 0; i < terms.size(); i++) {
            numbered.addProperty(String.valueOf(i + 1), terms.get(i));
        }
        return chatBody(model, system.toString(), new Gson().toJson(numbered), batchReplyTokens(terms.size()));
    }

    private static String referenceLines(java.util.List<Reference> references) {
        StringBuilder out = new StringBuilder();
        for (Reference r : references) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("- ").append(r.source()).append(" => ").append(r.target());
        }
        return out.toString();
    }

    private static String chatBody(String model, String system, String user) {
        return chatBody(model, system, user, null);
    }

    /** As {@link #chatBody(String, String, String)}, with a ceiling on the reply length. */
    private static String chatBody(String model, String system, String user, Integer maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.1);
        if (maxTokens != null) {
            body.addProperty("max_tokens", maxTokens.intValue());
        }

        JsonArray messages = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", system);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", user);
        messages.add(userMsg);

        body.add("messages", messages);
        return new Gson().toJson(body);
    }

    /**
     * Ask for several terms at once. Never throws: a failed request is reported in the result.
     * Terms the model did not answer are simply absent from {@link BatchResult#translations()}.
     */
    public static BatchResult suggestBatch(java.util.List<String> terms,
                                           String sourceLang,
                                           String targetLang,
                                           java.util.List<Reference> references,
                                           Config config,
                                           HttpTransport transport) {
        if (config == null || !config.enabled()) {
            return new BatchResult(java.util.Map.of(),
                SuggestionFailures.code(SuggestionFailures.KEY_DISABLED), true);
        }
        if (!config.isValid()) {
            return new BatchResult(java.util.Map.of(),
                SuggestionFailures.code(SuggestionFailures.KEY_INCOMPLETE_CONFIG), true);
        }
        String url = buildUrl(config.apiUrl());
        String requestBody = buildBatchRequest(terms, sourceLang, targetLang, config.model(), references);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            headers.put("Authorization", "Bearer " + config.apiKey());
        }
        try {
            String response = transport.post(url, headers, requestBody);
            return parseBatchResponse(response, terms.size());
        } catch (IOException e) {
            System.err.println("TranslationSuggester: batch request to " + OpenAiHttpTransport.sanitizeUrl(url)
                + " model=" + config.model() + " failed: " + e.getMessage());
            return new BatchResult(java.util.Map.of(), e.getMessage(), true);
        }
    }

    /**
     * Reads a batch reply: a JSON object of number to translation, possibly wrapped in a code fence
     * or prose. Numbers outside 1..count, blank values and values that are not JSON strings are
     * ignored, so what comes back is only answers that can be matched to a term.
     *
     * <p>A reply that was cut off by the token limit, or that cannot be parsed as one object (prose
     * around more than one object), does not void the batch: every complete {@code "number": "text"}
     * answer is kept and only the terms without one are asked again.
     */
    static BatchResult parseBatchResponse(String jsonResponse, int count) {
        Reply reply = readReply(jsonResponse);
        if (reply.error() != null) {
            return new BatchResult(java.util.Map.of(), reply.error(), false);
        }
        String text = reply.text();
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        java.util.Map<Integer, String> answers = null;
        if (open >= 0 && close > open) {
            try {
                answers = readAnswers(
                    JsonParser.parseString(text.substring(open, close + 1)).getAsJsonObject(), count);
            } catch (Exception malformed) {
                answers = null;   // not one JSON object after all; salvage what is readable below
            }
        } else if (open < 0) {
            return new BatchResult(java.util.Map.of(),
                SuggestionFailures.code(SuggestionFailures.KEY_NOT_A_JSON_OBJECT), false);
        }
        if (answers == null) {
            answers = salvageAnswers(text, count);
            if (answers.isEmpty()) {
                return new BatchResult(java.util.Map.of(),
                    reply.truncated() ? TRUNCATED_REPLY
                    : SuggestionFailures.code(SuggestionFailures.KEY_NOT_VALID_JSON), false);
            }
        }
        return new BatchResult(answers, null, false);
    }

    /** Collects the valid number-to-text entries of a parsed answer object (sorted by number). */
    private static java.util.Map<Integer, String> readAnswers(JsonObject parsed, int count) {
        java.util.Map<Integer, String> out = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonElement> e : parsed.entrySet()) {
            int number;
            try {
                number = Integer.parseInt(e.getKey().trim());
            } catch (NumberFormatException nfe) {
                continue;
            }
            JsonElement v = e.getValue();
            if (number < 1 || number > count || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                continue;   // a boolean or a number is not a translation
            }
            String t = v.getAsString().trim();
            if (!t.isEmpty()) {
                out.put(number, t);
            }
        }
        return out;
    }

    private static final String TRUNCATED_REPLY = SuggestionFailures.code(
        SuggestionFailures.KEY_TRUNCATED, "finish_reason=length");

    /** A complete {@code "number": "translation"} pair, for rescuing answers from a broken reply. */
    private static final Pattern NUMBERED_ANSWER =
        Pattern.compile("\"(\\d{1,9})\"\\s*:\\s*(\"(?:[^\"\\\\\\u0000-\\u001F]|\\\\.)*\")");

    /**
     * Picks out the complete answers from text that is not parseable as one JSON object — a reply
     * cut off mid-value keeps every pair closed before the cut. Values are decoded JSON strings, so
     * an escape at the cut point simply makes that pair unreadable and it is skipped.
     */
    private static java.util.Map<Integer, String> salvageAnswers(String text, int count) {
        java.util.Map<Integer, String> out = new java.util.TreeMap<>();
        Matcher m = NUMBERED_ANSWER.matcher(text);
        Gson gson = new Gson();
        while (m.find()) {
            try {
                int number = Integer.parseInt(m.group(1));
                if (number < 1 || number > count) {
                    continue;
                }
                String value = gson.fromJson(m.group(2), String.class).trim();
                if (!value.isEmpty()) {
                    out.putIfAbsent(number, value);
                }
            } catch (Exception skipped) {
                // an answer that cannot be decoded is an answer the model did not give
            }
        }
        return out;
    }

    /**
     * Parse the API response JSON to extract the suggested translation.
     *
     * <p>Every way a reply can come back without a usable translation is reported with its own
     * reason (it is shown to the user), instead of collapsing into a vague parse error:
     * a {@code null} content (reasoning models put their text elsewhere, or the reply is a refusal),
     * a reply that consists only of thinking, and a reply cut off by the token limit. A single
     * truncated translation cannot be offered as-is, so it stays a failure here; the batch parser
     * instead keeps the answers that did come through.
     */
    static SuggestionResult parseResponse(String jsonResponse) {
        Reply reply = readReply(jsonResponse);
        if (reply.error() != null) {
            return SuggestionResult.fail(reply.error());
        }
        if (reply.truncated()) {
            return SuggestionResult.fail(TRUNCATED_REPLY);   // code, rendered by the UI
        }
        return SuggestionResult.ok(reply.text());
    }

    /** Text of the first choice (thinking removed, trimmed), the reason there is none, truncation flag. */
    private record Reply(String text, String error, boolean truncated) {}

    private static Reply readReply(String jsonResponse) {
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return new Reply(null, SuggestionFailures.code(SuggestionFailures.KEY_NO_CHOICES), false);
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = first.getAsJsonObject("message");
            if (message == null) {
                return new Reply(null, SuggestionFailures.code(SuggestionFailures.KEY_NO_MESSAGE), false);
            }
            boolean truncated = "length".equals(finishReason(first));
            String text = replyText(first, message);
            if (text == null || text.isBlank()) {
                return new Reply(null, emptyReason(first), truncated);
            }
            return new Reply(text.trim(), null, truncated);
        } catch (Exception e) {
            return new Reply(null, SuggestionFailures.code(SuggestionFailures.KEY_PARSE, e.getMessage()), false);
        }
    }

    /** The reply text with any reasoning removed; null when the message carries no text. */
    static String replyText(JsonObject choice, JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonPrimitive()) {
            return null;   // JSON null, absent, or a structured value we do not understand
        }
        return stripThinking(content.getAsString());
    }

    private static String finishReason(JsonObject choice) {
        JsonElement f = choice.get("finish_reason");
        return f != null && f.isJsonPrimitive() ? f.getAsString() : null;
    }

    private static String emptyReason(JsonObject choice) {
        String finish = finishReason(choice);
        if ("length".equals(finish)) {
            return SuggestionFailures.code(SuggestionFailures.KEY_TOKEN_LIMIT_EMPTY, "finish_reason=length");
        }
        if ("content_filter".equals(finish)) {
            return SuggestionFailures.code(SuggestionFailures.KEY_CONTENT_FILTER);
        }
        return SuggestionFailures.code(SuggestionFailures.KEY_EMPTY);
    }

    /**
     * Drops {@code <think>...</think>} blocks that reasoning models put in the content. A block
     * that never closes (the reply was cut off while thinking) removes everything after it, and a
     * closing tag with no opening one (some servers strip it) removes everything before it.
     */
    static String stripThinking(String text) {
        if (text == null) {
            return null;
        }
        String out = text.replaceAll("(?is)<think>.*?</think>", "");
        int open = out.toLowerCase(java.util.Locale.ROOT).indexOf("<think>");
        if (open >= 0) {
            out = out.substring(0, open);
        }
        int close = out.toLowerCase(java.util.Locale.ROOT).lastIndexOf("</think>");
        if (close >= 0) {
            out = out.substring(close + "</think>".length());
        }
        return out;
    }
}

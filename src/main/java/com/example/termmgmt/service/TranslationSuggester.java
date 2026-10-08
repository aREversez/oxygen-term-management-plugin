package com.example.termmgmt.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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
        if (config == null || !config.enabled()) {
            return SuggestionResult.fail("AI translation is disabled");
        }
        if (!config.isValid()) {
            return SuggestionResult.fail("AI translation config incomplete (url and model required)");
        }

        String url = buildUrl(config.apiUrl());
        String requestBody = buildRequest(sourceTerm, sourceLang, targetLang, config.model());

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
     * Build the JSON request body for the chat completions endpoint.
     */
    static String buildRequest(String sourceTerm, String sourceLang, String targetLang, String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("temperature", 0.1);

        JsonArray messages = new JsonArray();

        // System message
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content",
            "Translate the following term from " + sourceLang + " to " + targetLang
            + ". Reply with only the translation, nothing else.");
        messages.add(systemMsg);

        // User message
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", sourceTerm);
        messages.add(userMsg);

        body.add("messages", messages);
        return new Gson().toJson(body);
    }

    /**
     * Parse the API response JSON to extract the suggested translation.
     */
    static SuggestionResult parseResponse(String jsonResponse) {
        try {
            JsonObject root = JsonParser.parseString(jsonResponse).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return SuggestionResult.fail("No choices in API response");
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            JsonObject message = first.getAsJsonObject("message");
            if (message == null) {
                return SuggestionResult.fail("No message in choice");
            }
            String content = message.get("content").getAsString();
            if (content == null || content.isBlank()) {
                return SuggestionResult.fail("Empty translation in response");
            }
            return SuggestionResult.ok(content.trim());
        } catch (Exception e) {
            return SuggestionResult.fail("Failed to parse API response: " + e.getMessage());
        }
    }
}

package com.example.termmgmt.service;

/**
 * Result of a translation suggestion request.
 */
public record SuggestionResult(boolean success, String translation, String errorMessage) {

    public static SuggestionResult ok(String translation) {
        return new SuggestionResult(true, translation, null);
    }

    public static SuggestionResult fail(String errorMessage) {
        return new SuggestionResult(false, null, errorMessage);
    }
}

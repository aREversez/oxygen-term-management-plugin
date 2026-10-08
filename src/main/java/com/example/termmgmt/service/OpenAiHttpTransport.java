package com.example.termmgmt.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Production {@link HttpTransport} backed by {@code java.net.http.HttpClient} (Java 11+).
 *
 * <p>Security constraints:
 * <ul>
 *   <li>Only {@code http} and {@code https} schemes are allowed.</li>
 *   <li>Response body is capped at 1 MB.</li>
 *   <li>Request timeout: 30 seconds.</li>
 *   <li>The API key appears only in the Authorization header; never in log output.</li>
 * </ul>
 */
public final class OpenAiHttpTransport implements HttpTransport {

    static final int MAX_RESPONSE_BYTES = 1_048_576; // 1 MB
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    public OpenAiHttpTransport() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /** Constructor for testing with a pre-configured client. */
    OpenAiHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public String post(String url, Map<String, String> headers, String jsonBody) throws IOException {
        // Scheme validation
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + sanitizeUrl(url), e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Only http/https schemes are allowed, got: " + scheme);
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        // Add custom headers (Authorization, etc.)
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if (!"Content-Type".equalsIgnoreCase(h.getKey())) {
                    reqBuilder.header(h.getKey(), h.getValue());
                }
            }
        }

        try {
            HttpResponse<byte[]> response = client.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofByteArray());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                // Log without exposing the API key
                throw new IOException("HTTP " + status + " from " + sanitizeUrl(url));
            }

            byte[] body = response.body();
            if (body != null && body.length > MAX_RESPONSE_BYTES) {
                throw new IOException("Response exceeds 1 MB limit (" + body.length + " bytes)");
            }
            return body != null ? new String(body, StandardCharsets.UTF_8) : "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Strip query/fragment from URL for safe logging (may contain key in query param).
     */
    static String sanitizeUrl(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?..." : url;
    }
}

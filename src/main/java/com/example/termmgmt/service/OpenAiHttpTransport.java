package com.example.termmgmt.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 *   <li>Redirects are never followed: the Authorization header (API key) must not be
 *       replayed to a redirect target the user did not configure.</li>
 *   <li>Response body is capped at 1 MB, enforced while reading (an oversized response
 *       is abandoned instead of being buffered in full first).</li>
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
            // NEVER, not NORMAL: NORMAL would forward the Authorization header (the API
            // key) to a redirect target chosen by the remote server.
            .followRedirects(HttpClient.Redirect.NEVER)
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
            // Streamed cap: read at most MAX_RESPONSE_BYTES + 1; a body larger than the
            // limit is abandoned without ever being fully buffered.
            HttpResponse<InputStream> response = client.send(reqBuilder.build(),
                HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                try (InputStream ignored = response.body()) {
                    // Drain/close so the connection can be reused.
                }
                if (status >= 300 && status < 400) {
                    // Redirects are not followed by design; report them as such.
                    throw new IOException("HTTP redirect (" + status + ") from " + sanitizeUrl(url)
                        + " is not followed; configure the final endpoint URL");
                }
                // Log without exposing the API key
                throw new IOException("HTTP " + status + " from " + sanitizeUrl(url));
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(64 * 1024, MAX_RESPONSE_BYTES));
            IOException[] readError = new IOException[1];
            drainBounded(response.body(), buffer, readError);
            if (readError[0] != null) {
                throw readError[0];
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    /** Copies the response stream into {@code out}, failing on more than {@link #MAX_RESPONSE_BYTES}. */
    private static void drainBounded(InputStream in, ByteArrayOutputStream out, IOException[] error) {
        try (InputStream stream = in) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = stream.read(chunk)) != -1) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    error[0] = new IOException("Response exceeds 1 MB limit (" + total + " bytes)");
                    return;
                }
                out.write(chunk, 0, n);
            }
        } catch (IOException e) {
            if (error[0] == null) {
                error[0] = e;
            }
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

package com.example.termmgmt.service;

import java.io.IOException;
import java.util.Map;

/**
 * Abstraction over HTTP POST for testability.
 * Production code uses {@link OpenAiHttpTransport}; unit tests supply a fake implementation.
 */
public interface HttpTransport {

    /**
     * POST a JSON body to the given URL with the supplied headers.
     *
     * @param url      fully-qualified URL (must be http or https)
     * @param headers  request headers (e.g. Content-Type, Authorization)
     * @param jsonBody the JSON request body
     * @return the response body as a string
     * @throws IOException on network failure or non-2xx HTTP status
     */
    String post(String url, Map<String, String> headers, String jsonBody) throws IOException;
}

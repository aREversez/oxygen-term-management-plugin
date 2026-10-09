package com.example.termmgmt.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The real transport against throwaway local servers: no redirect is followed, the size cap holds. */
class OpenAiHttpTransportTest {

    private static HttpServer server() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.start();
        return s;
    }

    private static String url(HttpServer s, String path) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + path;
    }

    @Test
    void aSuccessfulPost_returnsTheBody_andSendsTheAuthorizationHeader() throws Exception {
        HttpServer s = server();
        AtomicReference<String> auth = new AtomicReference<>();
        s.createContext("/ok", ex -> {
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"ok\":true}".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        try {
            String r = new OpenAiHttpTransport().post(url(s, "/ok"),
                Map.of("Authorization", "Bearer k"), "{}");
            assertEquals("{\"ok\":true}", r);
            assertEquals("Bearer k", auth.get());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aRedirect_isNotFollowed_soTheKeyNeverReachesAnotherHost() throws Exception {
        HttpServer other = server();
        AtomicInteger hits = new AtomicInteger();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        other.createContext("/", ex -> {
            hits.incrementAndGet();
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        HttpServer api = server();
        api.createContext("/v1", ex -> {
            ex.getResponseHeaders().add("Location", url(other, "/steal"));
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        try {
            IOException e = assertThrows(IOException.class, () -> new OpenAiHttpTransport()
                .post(url(api, "/v1"), Map.of("Authorization", "Bearer secret"), "{}"));
            assertTrue(e.getMessage().contains("307"), e.getMessage());
            assertEquals(0, hits.get(), "the redirect target must not be contacted");
            assertNull(seenAuth.get());
        } finally {
            api.stop(0);
            other.stop(0);
        }
    }

    @Test
    void aResponseOverOneMegabyte_isRefused() throws Exception {
        HttpServer s = server();
        s.createContext("/big", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked, length unknown up front
            try (OutputStream o = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'a');
                for (int i = 0; i < 20; i++) o.write(chunk); // 1.25 MB
            } catch (IOException ignored) {
                // the client hung up once it had read enough
            }
        });
        try {
            IOException e = assertThrows(IOException.class,
                () -> new OpenAiHttpTransport().post(url(s, "/big"), Map.of(), "{}"));
            assertTrue(e.getMessage().contains("1 MB"), e.getMessage());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aResponseJustUnderTheLimit_isAccepted() throws Exception {
        HttpServer s = server();
        s.createContext("/edge", ex -> {
            byte[] body = new byte[OpenAiHttpTransport.MAX_RESPONSE_BYTES];
            java.util.Arrays.fill(body, (byte) 'b');
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        try {
            String r = new OpenAiHttpTransport().post(url(s, "/edge"), Map.of(), "{}");
            assertEquals(OpenAiHttpTransport.MAX_RESPONSE_BYTES, r.length());
        } finally {
            s.stop(0);
        }
    }
}

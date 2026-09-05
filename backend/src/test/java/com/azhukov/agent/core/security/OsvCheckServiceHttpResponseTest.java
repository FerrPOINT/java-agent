package com.azhukov.agent.core.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEBT-1 regression: non-2xx OSV responses must never be parsed as a clean verdict.
 * Fail-open stays fail-open (null = allow), but via the explicit error path,
 * not by silently reading an error body without a "vulns" array.
 */
class OsvCheckServiceHttpResponseTest {

    private HttpServer server;
    private OsvCheckService service;

    @BeforeEach
    void startStubServer() throws Exception {
        // service is created per-test after stub() binds the port.
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private void stub(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query", exchange -> {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        service = new OsvCheckService(true, HttpClient.newHttpClient(),
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/query");
    }

    @Test
    void serverErrorWithJsonBodyIsFailOpenNotClean() throws Exception {
        // 429 with a JSON error body that has no "vulns" array.
        // Before the fix this parsed as zero advisories → silently "clean".
        stub(429, "{\"error\":{\"code\":429,\"message\":\"rate limited\"}}");
        String result = service.checkPackageForMalware("npx", List.of("some-package"));
        assertThat(result).as("non-2xx must stay fail-open (allow), not assert cleanness").isNull();
    }

    @Test
    void malwareAdvisoryBlocks() throws Exception {
        stub(200, "{\"vulns\":[{\"id\":\"MAL-2026-0001\",\"summary\":\"known malware\"}]}");
        String result = service.checkPackageForMalware("npx", List.of("some-package"));
        assertThat(result).isNotNull();
        assertThat(result).contains("MAL-2026-0001");
        assertThat(result).startsWith("BLOCKED");
    }

    @Test
    void cleanResponseAllows() throws Exception {
        stub(200, "{\"vulns\":[]}");
        String result = service.checkPackageForMalware("npx", List.of("some-package"));
        assertThat(result).isNull();
    }
}

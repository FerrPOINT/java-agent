package com.azhukov.agent.tools.vision;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.ImageShrinkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisionAnalyzeToolTest {

    private ModelClient modelClient;
    private ImageShrinkerService imageShrinker;
    private com.azhukov.agent.core.security.UrlSafety urlSafety;
    private VisionAnalyzeTool tool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        modelClient = mock(ModelClient.class);
        imageShrinker = mock(ImageShrinkerService.class);
        urlSafety = mock(com.azhukov.agent.core.security.UrlSafety.class);
        when(urlSafety.checkUrl(anyString())).thenReturn(null);
        when(imageShrinker.shrinkIfNeeded(anyString())).thenAnswer(inv -> inv.getArgument(0));
        tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);
    }

    @Test
    void redirectLoopStopsAfterBoundedAttempts() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/loop", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/loop";
            var result = tool.execute("{\"image_url\":\"" + url + "\"}", null, null);
            assertThat(result.success()).isFalse();
            assertThat(hits.get()).isEqualTo(6);
            verifyNoInteractions(modelClient);
        } finally { server.stop(0); }
    }

    @Test
    void remoteRedirectToAllowedAddressReachesModel() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/image");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/image", exchange -> {
            byte[] data = {1, 2, 3};
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.start();
        try {
            when(modelClient.analyzeImage(eq("AQID"), anyString())).thenReturn("ok");
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThat(tool.execute("{\"image_url\":\"" + base + "/start\"}", null, null).success()).isTrue();
            verify(urlSafety).checkUrl(base + "/start");
            verify(urlSafety).checkUrl(base + "/image");
            verify(modelClient).analyzeImage(eq("AQID"), anyString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteRedirectToBlockedAddressDoesNotFetchTarget() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var targetHits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/blocked");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/blocked", exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 3);
            try (var out = exchange.getResponseBody()) { out.write(new byte[]{1, 2, 3}); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            when(urlSafety.checkUrl(base + "/blocked")).thenReturn("unsafe or private URL");
            var result = tool.execute("{\"image_url\":\"" + base + "/start\"}", null, null);
            assertThat(result.success()).isFalse();
            assertThat(targetHits.get()).isZero();
            verifyNoInteractions(modelClient);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void execute_withNullImage_returnsFail() {
        ToolResult result = tool.execute("{\"image_url\":null}", Message.assistant("test", 0), Session.create("test", "noop", "noop"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Image path or URL is required");
    }

    @Test
    void execute_withBlankImage_returnsFail() {
        ToolResult result = tool.execute("{\"image_url\":\"  \"}", Message.assistant("test", 0), Session.create("test", "noop", "noop"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Image path or URL is required");
    }

    @Test
    void execute_withLocalFile_returnsOk() throws Exception {
        Path imgFile = tempDir.resolve("test.png");
        Files.write(imgFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        when(modelClient.analyzeImage(anyString(), anyString())).thenReturn("A test image");

        ToolResult result = tool.execute(
            "{\"image_url\":\"" + imgFile.toString().replace("\\", "\\\\") + "\",\"question\":\"What is this?\"}",
            Message.assistant("test", 0), Session.create("test", "noop", "noop"));

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("A test image");
        verify(modelClient).analyzeImage(anyString(), eq("What is this?"));
    }

    @Test
    void execute_withNonExistentFile_returnsFail() {
        ToolResult result = tool.execute(
            "{\"image_url\":\"/nonexistent/file.png\"}",
            Message.assistant("test", 0), Session.create("test", "noop", "noop"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Vision analyze failed");
        assertThat(result.error()).contains("File not found");
    }

    @Test
    void execute_withDefaultPrompt_whenQuestionNull() throws Exception {
        Path imgFile = tempDir.resolve("test.jpg");
        Files.write(imgFile, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
        when(modelClient.analyzeImage(anyString(), anyString())).thenReturn("Default description");

        ToolResult result = tool.execute(
            "{\"image_url\":\"" + imgFile.toString().replace("\\", "\\\\") + "\"}",
            Message.assistant("test", 0), Session.create("test", "noop", "noop"));

        assertThat(result.success()).isTrue();
        verify(modelClient).analyzeImage(anyString(), eq("Describe this image"));
    }

    @Test
    void execute_withModelClientError_returnsFail() throws Exception {
        Path imgFile = tempDir.resolve("test.png");
        Files.write(imgFile, new byte[]{0x42});
        when(modelClient.analyzeImage(anyString(), anyString())).thenThrow(new RuntimeException("API timeout"));

        ToolResult result = tool.execute(
            "{\"image_url\":\"" + imgFile.toString().replace("\\", "\\\\") + "\"}",
            Message.assistant("test", 0), Session.create("test", "noop", "noop"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Vision analyze failed");
        assertThat(result.error()).contains("API timeout");
    }

    @Test
    void execute_shrinkerCalledBeforeModelClient() throws Exception {
        Path imgFile = tempDir.resolve("test.png");
        Files.write(imgFile, new byte[]{0x42});
        when(modelClient.analyzeImage(anyString(), anyString())).thenReturn("shrunk result");
        when(imageShrinker.shrinkIfNeeded(anyString())).thenReturn("shrunk-base64");

        tool.execute(
            "{\"image_url\":\"" + imgFile.toString().replace("\\", "\\\\") + "\"}",
            Message.assistant("test", 0), Session.create("test", "noop", "noop"));

        verify(imageShrinker).shrinkIfNeeded(anyString());
        verify(modelClient).analyzeImage(eq("shrunk-base64"), anyString());
    }

    @Test
    void ssrfUnsafeUrlIsBlockedBeforeFetch() {
        when(urlSafety.checkUrl("http://169.254.169.254/latest/meta-data")).thenReturn("unsafe or private URL");
        var result = tool.execute(
            "{\"image\":\"http://169.254.169.254/latest/meta-data\",\"prompt\":\"describe\"}",
            null, null);
        org.assertj.core.api.Assertions.assertThat(result.success()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.error())
            .contains("blocked");
    }
}

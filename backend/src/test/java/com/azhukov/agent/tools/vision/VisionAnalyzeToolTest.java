package com.azhukov.agent.tools.vision;

import com.azhukov.agent.core.client.ModelClient;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.service.ImageShrinkerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class VisionAnalyzeToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=";
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(PNG_BASE64);

    @Mock
    private ModelClient modelClient;

    @Mock
    private ImageShrinkerService imageShrinker;

    @Mock
    private UrlSafety urlSafety;

    @BeforeEach
    void allowIdentityShrinkByDefault() {
        lenient().when(imageShrinker.shrinkIfNeeded(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void malformedToolArgumentsReturnStructuredError() throws Exception {
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute("{", null, null);

        assertThat(result.success()).isFalse();
        JsonNode json = JSON.readTree(result.content());
        assertThat(json.path("success").asBoolean()).isFalse();
        assertThat(json.path("error").asText()).contains("Invalid tool arguments");
        assertThat(result.error()).isEqualTo(json.path("error").asText());
        verifyNoInteractions(imageShrinker, modelClient, urlSafety);
    }

    @Test
    void missingImageUrlReturnsJsonError() {
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute("{}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("image_url is required");
        assertThat(result.content()).contains("\"success\":false");
        assertThat(result.content()).contains("\"image_url is required\"");
        verifyNoInteractions(imageShrinker, modelClient, urlSafety);
    }

    @Test
    void dataImageUrlIsDecodedBeforeVisionAnalysis() {
        when(modelClient.analyzeImage(PNG_BASE64, "describe")).thenReturn("ok");
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image\":\"data:image/png;base64," + PNG_BASE64 + "\",\"prompt\":\"describe\"}",
            null,
            null
        );

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("ok");
        verify(imageShrinker).shrinkIfNeeded(PNG_BASE64);
        verify(modelClient).analyzeImage(PNG_BASE64, "describe");
        verifyNoInteractions(urlSafety);
    }

    @Test
    void dataImageUrlWithNonImagePayloadIsRejected() {
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image\":\"data:image/png;base64,VEVYVA==\",\"prompt\":\"describe\"}",
            null,
            null
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("source is not a recognized image");
        verifyNoInteractions(imageShrinker, modelClient, urlSafety);
    }

    @Test
    void httpImageUrlIsRejectedWhenUrlSafetyBlocksIt() {
        when(urlSafety.checkUrl("http://127.0.0.1/private.png")).thenReturn("URL blocked by safety policy");
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image\":\"http://127.0.0.1/private.png\",\"prompt\":\"describe\"}",
            null,
            null
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("URL blocked by safety policy");
        verifyNoInteractions(imageShrinker, modelClient);
    }

    @Test
    void httpImageUrlDownloadsThroughManualRedirectSafeClient() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG_BYTES.length);
            try (var body = exchange.getResponseBody()) {
                body.write(PNG_BYTES);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image.png";
            when(urlSafety.checkUrl(url)).thenReturn(null);
            when(modelClient.analyzeImage(PNG_BASE64, "describe")).thenReturn("ok");
            VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

            ToolResult result = tool.execute(
                "{\"image\":\"" + url + "\",\"prompt\":\"describe\"}",
                null,
                null
            );

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("ok");
            verify(imageShrinker).shrinkIfNeeded(PNG_BASE64);
            verify(modelClient).analyzeImage(PNG_BASE64, "describe");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fileUriImageIsDecodedBeforeVisionAnalysis(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("image.png");
        Files.write(file, PNG_BYTES);
        when(modelClient.analyzeImage(PNG_BASE64, "describe")).thenReturn("ok");
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image_url\":\"" + file.toUri() + "\",\"question\":\"describe\"}",
            null,
            null
        );

        assertThat(result.success()).isTrue();
        assertThat(result.content()).isEqualTo("ok");
        verify(imageShrinker).shrinkIfNeeded(PNG_BASE64);
        verify(modelClient).analyzeImage(PNG_BASE64, "describe");
        verifyNoInteractions(urlSafety);
    }

    @Test
    void localTextFileIsRejectedBeforeVisionAnalysis(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("not-image.txt");
        Files.writeString(file, "plain text", StandardCharsets.UTF_8);
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image\":\"" + file.toString().replace("\\", "\\\\") + "\",\"prompt\":\"describe\"}",
            null,
            null
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("source is not a recognized image");
        verifyNoInteractions(imageShrinker, modelClient, urlSafety);
    }

    @Test
    void localImageOverLimitIsRejectedBeforeReadAllBytes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("huge.png");
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(50L * 1024 * 1024 + 1);
        }
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image\":\"" + file.toString().replace("\\", "\\\\") + "\",\"prompt\":\"describe\"}",
            null,
            null
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Image too large");
        verifyNoInteractions(imageShrinker, modelClient, urlSafety);
    }

    @Test
    void regionCropsImageBeforeVisionAnalysis(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("two-pixels.png");
        Files.write(file, twoPixelPng());
        when(modelClient.analyzeImage(anyString(), eq("describe"))).thenReturn("ok");
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image_url\":\"" + file.toString().replace("\\", "\\\\") + "\","
                + "\"question\":\"describe\",\"region\":[1,0,2,1]}",
            null,
            null
        );

        assertThat(result.success()).isTrue();
        ArgumentCaptor<String> imageCaptor = ArgumentCaptor.forClass(String.class);
        verify(imageShrinker).shrinkIfNeeded(imageCaptor.capture());
        BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(imageCaptor.getValue())));

        assertThat(cropped.getWidth()).isEqualTo(1);
        assertThat(cropped.getHeight()).isEqualTo(1);
        assertThat(cropped.getRGB(0, 0) & 0x00FF_FFFF).isEqualTo(Color.BLUE.getRGB() & 0x00FF_FFFF);
    }

    @Test
    void invalidRegionIsRejectedBeforeVisionAnalysis(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("image.png");
        Files.write(file, PNG_BYTES);
        VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

        ToolResult result = tool.execute(
            "{\"image_url\":\"" + file.toString().replace("\\", "\\\\") + "\","
                + "\"question\":\"describe\",\"region\":[1,0,1,1]}",
            null,
            null
        );

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("crops to zero area");
        verifyNoInteractions(modelClient);
    }

    @Test
    void httpImageRedirectTargetIsRejectedWhenUrlSafetyBlocksIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            int port = server.getAddress().getPort();
            String source = "http://127.0.0.1:" + port + "/redirect";
            String target = "http://127.0.0.1:" + port + "/private.png";
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().set("Location", target);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            when(urlSafety.checkUrl(source)).thenReturn(null);
            when(urlSafety.checkUrl(target)).thenReturn("URL blocked by safety policy");
            VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

            ToolResult result = tool.execute(
                "{\"image\":\"" + source + "\",\"prompt\":\"describe\"}",
                null,
                null
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Redirect blocked: URL blocked by safety policy");
            verifyNoInteractions(imageShrinker, modelClient);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpImageStreamOverLimitIsRejectedBeforeVisionAnalysis() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[16 * 1024];
            try (var body = exchange.getResponseBody()) {
                long remaining = 50L * 1024 * 1024 + 1;
                while (remaining > 0) {
                    int size = (int) Math.min(chunk.length, remaining);
                    body.write(chunk, 0, size);
                    remaining -= size;
                }
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/large.png";
            when(urlSafety.checkUrl(url)).thenReturn(null);
            VisionAnalyzeTool tool = new VisionAnalyzeTool(modelClient, imageShrinker, urlSafety);

            ToolResult result = tool.execute(
                "{\"image\":\"" + url + "\",\"prompt\":\"describe\"}",
                null,
                null
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Image too large");
            verifyNoInteractions(imageShrinker, modelClient);
        } finally {
            server.stop(0);
        }
    }

    private byte[] twoPixelPng() throws Exception {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        image.setRGB(1, 0, Color.BLUE.getRGB());
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }
}

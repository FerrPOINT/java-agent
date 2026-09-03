package com.azhukov.agent.tools.imagegen;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.imagegen.ImageGenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageGenToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ObjectProvider<ImageGenProvider> providerProvider;

    @Mock
    private ImageGenProvider provider;

    private ImageGenTool tool;

    @BeforeEach
    void setUp() {
        tool = new ImageGenTool(providerProvider);
    }

    @Test
    void generate_malformedToolArgumentsReturnsStructuredError() throws Exception {
        ToolResult result = tool.execute("{", null, null);

        assertThat(result.success()).isFalse();
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("image").isNull()).isTrue();
        assertThat(response.get("error").asText()).contains("Invalid tool arguments");
        assertThat(response.get("error_type").asText()).isEqualTo("ValueError");
        assertThat(result.error()).isEqualTo(response.get("error").asText());
        verifyNoInteractions(providerProvider, provider);
    }

    @Test
    void generate_imageSavedAndReturnsHermesJson() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] imageBytes = "fake-png-data".getBytes();
        when(provider.generate(eq("a cat"), any())).thenReturn(imageBytes);

        String args = """
            {"prompt":"a cat","aspect_ratio":"1:1"}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isTrue();
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isTrue();
        assertThat(response.get("modality").asText()).isEqualTo("text");
        assertThat(response.get("upscaled").asBoolean()).isFalse();

        Path savedPath = Path.of(response.get("image").asText());
        assertThat(savedPath.getFileName().toString()).startsWith("img_");
        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.readAllBytes(savedPath)).isEqualTo(imageBytes);

        Files.deleteIfExists(savedPath);
        verify(provider).generate("a cat", "square");
    }

    @Test
    void generate_defaultAspectRatioMatchesHermesLandscape() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] imageBytes = "fake-png-data".getBytes();
        when(provider.generate(eq("a cat"), eq("landscape"))).thenReturn(imageBytes);

        ToolResult result = tool.execute("{\"prompt\":\"a cat\"}", null, null);

        assertThat(result.success()).isTrue();
        verify(provider).generate("a cat", "landscape");
        Files.deleteIfExists(generatedImagePath(result));
    }

    @Test
    void generate_invalidAspectRatioFallsBackToLandscape() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] imageBytes = "fake-png-data".getBytes();
        when(provider.generate(eq("a cat"), eq("landscape"))).thenReturn(imageBytes);

        ToolResult result = tool.execute("{\"prompt\":\"a cat\",\"aspect_ratio\":\"4:3\"}", null, null);

        assertThat(result.success()).isTrue();
        verify(provider).generate("a cat", "landscape");
        Files.deleteIfExists(generatedImagePath(result));
    }

    @Test
    void generate_noProvider_returnsFailureJson() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(null);

        String args = """
            {"prompt":"a cat"}
        """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("image").isNull()).isTrue();
        assertThat(response.get("error").asText()).contains("not enabled");
        assertThat(response.get("error_type").asText()).isEqualTo("ValueError");
        assertThat(result.error()).contains("not enabled");
    }

    @Test
    void generate_missingPrompt_returnsFail() throws Exception {
        String args = """
            {"prompt":""}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("prompt is required for image generation");
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("prompt is required for image generation");
    }

    @Test
    void generate_withSourceImage_returnsHonestUnsupportedEditingJson() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);

        ToolResult result = tool.execute("""
            {"prompt":"make it cinematic","image_url":"https://example.com/source.png"}
            """, null, null);

        assertThat(result.success()).isFalse();
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("image").isNull()).isTrue();
        assertThat(response.get("error").asText()).contains("Image editing is not supported");
        assertThat(response.get("error_type").asText()).isEqualTo("ValueError");
        assertThat(result.error()).contains("Image editing is not supported");
        verify(provider, never()).generate(any(), any());
    }

    @Test
    void generate_withReferenceImages_returnsHonestUnsupportedEditingJson() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);

        ToolResult result = tool.execute("""
            {"prompt":"same style","reference_image_urls":["https://example.com/ref.png"]}
            """, null, null);

        assertThat(result.success()).isFalse();
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("image").isNull()).isTrue();
        assertThat(response.get("error").asText()).contains("Image editing is not supported");
        assertThat(response.get("error_type").asText()).isEqualTo("ValueError");
        assertThat(result.error()).contains("Image editing is not supported");
        verify(provider, never()).generate(any(), any());
    }

    @Test
    void generate_withUpscaleTrue_returnsHonestUnsupportedJson() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);

        ToolResult result = tool.execute("""
            {"prompt":"make it large","upscale":true}
            """, null, null);

        assertThat(result.success()).isFalse();
        JsonNode response = MAPPER.readTree(result.content());
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("image").isNull()).isTrue();
        assertThat(response.get("error").asText()).contains("upscale is not supported");
        assertThat(response.get("error_type").asText()).isEqualTo("ValueError");
        assertThat(result.error()).contains("upscale is not supported");
        verify(provider, never()).generate(any(), any());
    }

    private Path generatedImagePath(ToolResult result) throws Exception {
        return Path.of(MAPPER.readTree(result.content()).get("image").asText());
    }
}

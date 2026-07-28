package com.azhukov.agent.tools.imagegen;

import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.imagegen.ImageGenProvider;
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
    void generate_imageSavedAndReturnsMediaPath() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] imageBytes = "fake-png-data".getBytes();
        when(provider.generate(eq("a cat"), any())).thenReturn(imageBytes);

        String args = """
            {"prompt":"a cat","aspect_ratio":"1:1"}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("MEDIA:");
        assertThat(result.content()).contains("/tmp/");

        // Verify file was created
        String mediaLine = result.content().lines()
            .filter(l -> l.startsWith("MEDIA:"))
            .findFirst()
            .orElseThrow();
        Path savedPath = Path.of(mediaLine.substring("MEDIA:".length()));
        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.readAllBytes(savedPath)).isEqualTo(imageBytes);

        // Cleanup
        Files.deleteIfExists(savedPath);
    }

    @Test
    void generate_noProvider_returnsFail() {
        when(providerProvider.getIfAvailable()).thenReturn(null);

        String args = """
            {"prompt":"a cat"}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not enabled");
    }

    @Test
    void generate_missingPrompt_returnsFail() {
        String args = """
            {"prompt":""}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("prompt is required");
    }
}
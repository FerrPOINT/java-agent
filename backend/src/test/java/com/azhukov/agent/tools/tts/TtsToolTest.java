package com.azhukov.agent.tools.tts;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.tts.TtsProvider;
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
class TtsToolTest {

    @Mock
    private ObjectProvider<TtsProvider> providerProvider;

    @Mock
    private TtsProvider provider;

    private TtsTool tool;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        tool = new TtsTool(providerProvider, properties);
    }

    @Test
    void synthesize_audioSavedAndReturnsMediaPath() throws Exception {
        when(providerProvider.getIfAvailable()).thenReturn(provider);
        byte[] audioBytes = "fake-mp3-data".getBytes();
        when(provider.synthesize(eq("Hello world"), any())).thenReturn(audioBytes);

        String args = """
            {"text":"Hello world"}
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
        assertThat(Files.readAllBytes(savedPath)).isEqualTo(audioBytes);

        // Cleanup
        Files.deleteIfExists(savedPath);
    }

    @Test
    void synthesize_noProvider_returnsFail() {
        when(providerProvider.getIfAvailable()).thenReturn(null);

        String args = """
            {"text":"Hello"}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not enabled");
    }

    @Test
    void synthesize_missingText_returnsFail() {
        String args = """
            {"text":""}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("text is required");
    }
}
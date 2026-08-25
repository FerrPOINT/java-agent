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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TtsToolTest {

    @Mock
    private TtsProvider provider;

    @Mock
    private TtsProvider alternateProvider;

    private TtsTool tool;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        lenient().when(provider.name()).thenReturn("edge");
        tool = new TtsTool(List.of(provider), properties);
    }

    @Test
    void synthesize_audioSavedAndReturnsMediaPath() throws Exception {
        byte[] audioBytes = "fake-mp3-data".getBytes();
        when(provider.synthesize(eq("Hello world"), any(), any(), any())).thenReturn(audioBytes);

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
    void synthesize_outputPathUsesRequestedFile() throws Exception {
        byte[] audioBytes = "custom-output".getBytes();
        when(provider.synthesize(eq("Hello"), any(), any(), any())).thenReturn(audioBytes);
        Path requested = Files.createTempDirectory("tts-output-").resolve("nested/custom.mp3");

        ToolResult result = tool.execute("{\"text\":\"Hello\",\"output_path\":\"" + requested + "\"}", null, null);

        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("MEDIA:" + requested.toAbsolutePath());
        assertThat(Files.readAllBytes(requested)).isEqualTo(audioBytes);
        Files.deleteIfExists(requested);
        Files.deleteIfExists(requested.getParent());
        Files.deleteIfExists(requested.getParent().getParent());
    }

    @Test
    void synthesize_usesRequestedProviderAndClampsSpeed() throws Exception {
        lenient().when(alternateProvider.name()).thenReturn("openai");
        tool = new TtsTool(List.of(provider, alternateProvider), new AgentProperties());
        when(alternateProvider.synthesize(eq("Hello"), any(), eq(4.0), any())).thenReturn("audio".getBytes());

        ToolResult result = tool.execute("{\"text\":\"Hello\",\"provider\":\"openai\",\"speed\":99}", null, null);

        assertThat(result.success()).isTrue();
        verify(alternateProvider).synthesize(eq("Hello"), any(), eq(4.0), any());
        verify(provider, never()).synthesize(any(), any(), any(), any());
        Path mediaPath = Path.of(result.content().lines().filter(line -> line.startsWith("MEDIA:")).findFirst().orElseThrow().substring(6));
        Files.deleteIfExists(mediaPath);
    }

    @Test
    void synthesize_rejectsTraversalOutputPath() {
        ToolResult result = tool.execute("{\"text\":\"Hello\",\"output_path\":\"tmp/../secret.mp3\"}", null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("traversal");
    }

    @Test
    void synthesize_noProvider_returnsFail() {
        tool = new TtsTool(List.of(), new AgentProperties());

        String args = """
            {"text":"Hello"}
            """;
        ToolResult result = tool.execute(args, null, null);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("unavailable");
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
package com.azhukov.agent.tools.tts;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Message;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.service.tts.TtsProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TtsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private TtsProvider provider;

    @Mock
    private TtsProvider alternateProvider;

    @Mock
    private com.azhukov.agent.service.AttachmentArtifactService artifacts;
    private final com.azhukov.agent.core.model.Session session =
        com.azhukov.agent.core.model.Session.create("owner", "provider", "model");
    private static final String ARTIFACT = "MEDIA:artifact:att_0123456789abcdef0123456789abcdef";
    private TtsTool tool;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        lenient().when(provider.name()).thenReturn("edge");
        tool = new TtsTool(List.of(provider), properties, new com.azhukov.agent.core.security.DefaultFileSafety(properties), artifacts);
    }

    @Test
    void telegramPublishesAudioWithoutResolvingALocalOutputPath() throws Exception {
        var telegram = session.withMetadata("platform", "telegram");
        byte[] audio = "audio".getBytes();
        when(provider.synthesize(eq("Hello"), any(), any(), any())).thenReturn(audio);
        when(artifacts.publishGenerated(telegram, "audio", "audio/mpeg", "voice.mp3", audio)).thenReturn(ARTIFACT);
        var policy = org.mockito.Mockito.mock(com.azhukov.agent.core.security.FileSafety.class);
        var remoteTool = new TtsTool(List.of(provider), new AgentProperties(), policy, artifacts);

        var result = remoteTool.execute("{\"text\":\"Hello\"}", null, telegram);

        assertThat(result.success()).isTrue();
        var response = json(result);
        assertThat(response.has("file_path")).isFalse();
        assertThat(response.path("file_paths").isEmpty()).isTrue();
        assertThat(response.path("media_tag").asText()).isEqualTo(ARTIFACT);
        assertThat(response.path("delivery_file_count").asInt()).isOne();
        org.mockito.Mockito.verifyNoInteractions(policy);
    }

    @Test
    void synthesize_malformedToolArgumentsReturnsStructuredError() throws Exception {
        ToolResult result = tool.execute("{", null, session);

        assertThat(result.success()).isFalse();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("Invalid tool arguments");
        assertThat(result.error()).isEqualTo(response.get("error").asText());
        verify(provider, never()).synthesize(any(), any(), any(), any());
    }

    @Test
    void synthesize_audioSavedAndReturnsHermesJson() throws Exception {
        byte[] audioBytes = "fake-mp3-data".getBytes();
        when(provider.synthesize(eq("Hello world"), any(), any(), any())).thenReturn(audioBytes);

        String args = """
            {"text":"Hello world"}
            """;
        ToolResult result = tool.execute(args, null, session);

        assertThat(result.success()).isTrue();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isTrue();
        assertThat(response.get("provider").asText()).isEqualTo("edge");
        assertThat(response.get("voice_compatible").asBoolean()).isFalse();
        assertThat(response.get("chunk_count").asInt()).isEqualTo(1);
        assertThat(response.get("delivery_file_count").asInt()).isEqualTo(1);
        assertThat(response.get("combined_chunks").asBoolean()).isFalse();

        Path savedPath = Path.of(response.get("file_path").asText());
        assertThat(savedPath.getFileName().toString()).startsWith("tts_");
        assertThat(Files.exists(savedPath)).isTrue();
        assertThat(Files.readAllBytes(savedPath)).isEqualTo(audioBytes);
        assertThat(response.get("media_tag").asText()).isEmpty();

        Files.deleteIfExists(savedPath);
    }

    @Test
    void synthesize_cleansNonspokenBlocksAndMarkdownBeforeProviderCall() throws Exception {
        byte[] audioBytes = "clean-audio".getBytes();
        when(provider.synthesize(eq("Hello world"), any(), any(), any())).thenReturn(audioBytes);

        ToolResult result = tool.execute("""
            {"text":"<think>hidden chain</think> **Hello** [world](https://example.com)"}
            """, null, session);

        assertThat(result.success()).isTrue();
        verify(provider).synthesize(eq("Hello world"), any(), any(), any());
        Path mediaPath = primaryFilePath(result);
        Files.deleteIfExists(mediaPath);
    }

    @Test
    void synthesize_rejectsTextThatBecomesEmptyAfterCleanup() throws Exception {
        ToolResult result = tool.execute("""
            {"text":"<think>hidden chain</think>"}
            """, null, session);

        assertThat(result.success()).isFalse();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("empty after TTS cleanup");
        assertThat(result.error()).contains("empty after TTS cleanup");
        verify(provider, never()).synthesize(any(), any(), any(), any());
    }

    @Test
    void synthesize_outputPathUsesRequestedFile() throws Exception {
        byte[] audioBytes = "custom-output".getBytes();
        when(provider.synthesize(eq("Hello"), any(), any(), any())).thenReturn(audioBytes);
        Path requested = Files.createTempDirectory("tts-output-").resolve("custom.mp3");

        ToolResult result = tool.execute("{\"text\":\"Hello\",\"output_path\":\"" + jsonPath(requested) + "\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode response = json(result);
        Path actualPath = Path.of(response.get("file_path").asText());
        assertThat(actualPath).isEqualTo(requested.toAbsolutePath().normalize());
        assertThat(response.get("media_tag").asText()).isEmpty();
        assertThat(Files.readAllBytes(requested)).isEqualTo(audioBytes);
        Files.deleteIfExists(requested);
        Files.deleteIfExists(requested.getParent());
    }

    @Test
    void synthesize_usesRequestedProviderAndClampsSpeed() throws Exception {
        lenient().when(alternateProvider.name()).thenReturn("openai");
        tool = new TtsTool(List.of(provider, alternateProvider), new AgentProperties(), new com.azhukov.agent.core.security.DefaultFileSafety(new AgentProperties()), artifacts);
        when(alternateProvider.synthesize(eq("Hello"), any(), eq(4.0), any())).thenReturn("audio".getBytes());

        ToolResult result = tool.execute("{\"text\":\"Hello\",\"provider\":\"openai\",\"speed\":99}", null, session);

        assertThat(result.success()).isTrue();
        verify(alternateProvider).synthesize(eq("Hello"), any(), eq(4.0), any());
        verify(provider, never()).synthesize(any(), any(), any(), any());
        Files.deleteIfExists(primaryFilePath(result));
    }

    @Test
    void synthesize_longOpenAiInputSplitsIntoProviderSafeChunks() throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getTts().setProvider("openai");
        when(provider.name()).thenReturn("openai");
        when(provider.synthesize(any(), any(), any(), any())).thenReturn("audio".getBytes());
        tool = new TtsTool(List.of(provider), properties, new com.azhukov.agent.core.security.DefaultFileSafety(properties), artifacts);

        String longText = "hello ".repeat(900);
        ToolResult result = tool.execute("{\"text\":\"" + longText + "\"}", null, session);

        assertThat(result.success()).isTrue();
        JsonNode response = json(result);
        List<Path> filePaths = filePaths(response);
        assertThat(filePaths).hasSize(2);
        assertThat(response.get("chunk_count").asInt()).isEqualTo(2);
        assertThat(response.get("delivery_file_count").asInt()).isEqualTo(2);
        assertThat(response.get("media_tag").asText()).isEmpty();

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(provider, times(2)).synthesize(textCaptor.capture(), any(), any(), any());
        assertThat(textCaptor.getAllValues()).allSatisfy(chunk -> assertThat(chunk).hasSizeLessThanOrEqualTo(4096));

        for (Path filePath : filePaths) {
            Files.deleteIfExists(filePath);
        }
    }

    @Test
    void synthesize_preservesReplacementAfterLaterChunkFails(@TempDir Path dir) throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.getTts().setProvider("openai");
        when(provider.name()).thenReturn("openai");
        when(provider.synthesize(any(), any(), any(), any()))
            .thenReturn("chunk-one".getBytes())
            .thenAnswer(call -> {
                Path replacement = Files.writeString(dir.resolve("replacement.mp3"), "another process");
                Files.move(replacement, dir.resolve("voice.chunk001.mp3"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                throw new RuntimeException("boom");
            });
        tool = new TtsTool(List.of(provider), properties, new com.azhukov.agent.core.security.DefaultFileSafety(properties), artifacts);

        Path requested = dir.resolve("voice.mp3");
        String longText = "hello ".repeat(900);
        ToolResult result = tool.execute("{\"text\":\"" + longText + "\",\"output_path\":\"" + jsonPath(requested) + "\"}", null, session);

        assertThat(result.success()).isFalse();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("boom");
        assertThat(result.error()).contains("boom");
        assertThat(Files.readString(dir.resolve("voice.chunk001.mp3"))).isEqualTo("another process");
        assertThat(Files.exists(dir.resolve("voice.chunk002.mp3"))).isFalse();
    }

    @Test
    void rejectsProtectedPathBeforeSynthesis() throws Exception {
        ToolResult result = tool.execute("""
            {"text":"Hello","output_path":"/artifacts/voice.mp3"}
            """, null, session);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Access denied");
        verify(provider, never()).synthesize(any(), any(), any(), any());
    }

    @Test
    void existingOutputIsNeverOverwrittenOrRemoved(@TempDir Path dir) throws Exception {
        when(provider.synthesize(any(), any(), any(), any())).thenReturn("new".getBytes());
        Path existing = Files.writeString(dir.resolve("voice.mp3"), "original");
        ToolResult result = tool.execute("{\"text\":\"Hello\",\"output_path\":\"" + jsonPath(existing) + "\"}", null, session);

        assertThat(result.success()).isFalse();
        assertThat(Files.readString(existing)).isEqualTo("original");
    }

    @Test
    void synthesize_rejectsTraversalOutputPath() throws Exception {
        ToolResult result = tool.execute("{\"text\":\"Hello\",\"output_path\":\"tmp/../secret.mp3\"}", null, session);

        assertThat(result.success()).isFalse();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("traversal");
        assertThat(result.error()).contains("traversal");
    }

    @Test
    void synthesize_noProvider_returnsFailureJson() throws Exception {
        tool = new TtsTool(List.of(), new AgentProperties(), new com.azhukov.agent.core.security.DefaultFileSafety(new AgentProperties()), artifacts);

        String args = """
            {"text":"Hello"}
            """;
        ToolResult result = tool.execute(args, null, session);

        assertThat(result.success()).isFalse();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("unavailable");
        assertThat(result.error()).contains("unavailable");
    }

    @Test
    void synthesize_missingText_returnsFailureJson() throws Exception {
        String args = """
            {"text":""}
            """;
        ToolResult result = tool.execute(args, null, session);

        assertThat(result.success()).isFalse();
        JsonNode response = json(result);
        assertThat(response.get("success").asBoolean()).isFalse();
        assertThat(response.get("error").asText()).contains("Text is required");
        assertThat(result.error()).contains("Text is required");
    }

    private JsonNode json(ToolResult result) throws Exception {
        return MAPPER.readTree(result.content());
    }

    private Path primaryFilePath(ToolResult result) throws Exception {
        return Path.of(json(result).get("file_path").asText());
    }

    private List<Path> filePaths(JsonNode response) {
        List<Path> paths = new ArrayList<>();
        response.get("file_paths").forEach(path -> paths.add(Path.of(path.asText())));
        return paths;
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}

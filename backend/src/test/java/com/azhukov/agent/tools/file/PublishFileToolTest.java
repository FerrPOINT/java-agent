package com.azhukov.agent.tools.file;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.Session;
import com.azhukov.agent.core.model.ToolResult;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.AttachmentArtifactService;
import com.azhukov.agent.tools.terminal.TerminalTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublishFileToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REFERENCE = "MEDIA:artifact:att_0123456789abcdef0123456789abcdef";

    @TempDir Path temporary;
    @Mock AttachmentArtifactService artifacts;
    private AgentProperties properties;
    private Path workspace;
    private Session session;
    private PublishFileTool tool;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectory(temporary.resolve("workspace"));
        properties = new AgentProperties();
        properties.getSecurity().setFileSafetyEnabled(true);
        properties.getSecurity().setAllowedPaths(List.of(workspace.toString()));
        session = Session.create("user-1", "noop", "test")
            .withMetadata(TerminalTool.META_WORKDIR, workspace.toString());
        tool = new PublishFileTool(properties, new DefaultFileSafety(properties), artifacts);
    }

    @AfterEach
    void clearCaller() {
        UserContext.clear();
    }

    private ToolResult publish(String path) throws Exception {
        return tool.execute(JSON.writeValueAsString(Map.of("path", path)), null, session);
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.docx", "archive.zip", "report name.pdf", "отчёт.pdf"})
    void publishesOnlyTheSelectedBytesWithTheCurrentSession(String name) throws Exception {
        byte[] bytes = ("selected bytes for " + name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(workspace.resolve(name), bytes);
        String safeName = AttachmentArtifactService.sanitizeFileName(name);
        when(artifacts.publishGenerated(eq(session), eq("document"), any(String.class), eq(safeName), any(byte[].class)))
            .thenReturn(REFERENCE);

        var result = publish(name);

        assertThat(result.success()).as(result.error()).isTrue();
        var body = JSON.readTree(result.content());
        assertThat(body.path("media_tag").asText()).isEqualTo(REFERENCE);
        assertThat(body.path("file_name").asText()).isEqualTo(safeName);
        assertThat(body.path("size_bytes").asInt()).isEqualTo(bytes.length);
        var captured = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(artifacts).publishGenerated(eq(session), eq("document"), any(String.class), eq(safeName), captured.capture());
        assertThat(captured.getValue()).isEqualTo(bytes);
        assertThat(Files.readAllBytes(workspace.resolve(name))).isEqualTo(bytes);
    }

    @Test
    void deniedPathCannotPublishOutsideBytes() throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside.zip"), "private outside bytes");
        assertThat(publish(outside.toString()).success()).isFalse();
        verifyNoInteractions(artifacts);
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    @Test
    void symlinkChecksTheActualTargetAndAllowsAnOwnedNeighbor() throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside.zip"), "private outside bytes");
        Files.createSymbolicLink(workspace.resolve("alias.zip"), outside);
        var denied = publish("alias.zip");
        assertThat(denied.success()).isFalse();
        assertThat(denied.error()).contains("outside allowed");
        verifyNoInteractions(artifacts);

        Path owned = Files.writeString(workspace.resolve("owned.zip"), "owned bytes");
        Files.createSymbolicLink(workspace.resolve("owned-alias.zip"), owned);
        when(artifacts.publishGenerated(eq(session), eq("document"), any(String.class),
                eq("owned-alias.zip"), any(byte[].class))).thenReturn(REFERENCE);
        assertThat(publish("owned-alias.zip").success()).isTrue();
    }

    @Test
    void privateArtifactStoreStaysDeniedWhenGeneralPathAllowlistingIsDisabled() throws Exception {
        properties.getSecurity().setFileSafetyEnabled(false);
        var result = publish("/artifacts/other-owner/private.zip");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Reading this path is not allowed");
        verifyNoInteractions(artifacts);
    }

    @Test
    void oversizedSparseFileIsRejectedBeforePublication() throws Exception {
        Path file = workspace.resolve("large.zip");
        try (var channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(AttachmentArtifactService.MAX_ATTACHMENT_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        var result = publish("large.zip");
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("20 MiB");
        verifyNoInteractions(artifacts);
    }

    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    @Test
    void fifoIsRefusedWithoutBlocking() throws Exception {
        Path fifo = workspace.resolve("pipe.zip");
        assertThat(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()).isZero();
        var result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(2), () -> publish("pipe.zip"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not a regular file");
        verifyNoInteractions(artifacts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"path\":\"\"}", "null", "{", "{\"path\":\"missing.zip\"}"})
    void invalidOrMissingFileDoesNotPublish(String arguments) {
        assertThat(tool.execute(arguments, null, session).success()).isFalse();
        verifyNoInteractions(artifacts);
    }

    @Test
    void missingSessionAndExpiredOriginalAuthorityDoNotPublish() throws Exception {
        Files.writeString(workspace.resolve("report.zip"), "content");
        String arguments = JSON.writeValueAsString(Map.of("path", "report.zip"));
        assertThat(tool.execute(arguments, null, null).error()).contains("active session");
        var proof = new com.pdlc.auth.HumanApplicationAccess("user-1", "user", Set.of("java-agent"),
            7, true, Instant.now().minusSeconds(1), System.nanoTime() - 1);
        UserContext.setCentral(proof);
        var result = tool.execute(arguments, null, session);
        assertThat(result.success()).isFalse();
        assertThat(UserContext.centralAccess()).isSameAs(proof);
        verifyNoInteractions(artifacts);
    }
}

package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ContextReference;
import com.azhukov.agent.core.model.ReferenceType;
import com.azhukov.agent.core.skill.SkillManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class DefaultContextReferenceServiceTest {

    @Mock
    private SkillManager skillManager;

    private AgentProperties properties;
    private DefaultContextReferenceService service;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(System.getProperty("java.io.tmpdir"));
        service = new DefaultContextReferenceService(properties, skillManager);
        service.initHttpClient();
    }

    @Test
    void resolve_urlReference() {
        List<ContextReference> refs = service.resolve(List.of("https://example.com"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.URL);
        assertThat(refs.get(0).source()).isEqualTo("https://example.com");
    }

    @Test
    void resolve_skillReference() {
        List<ContextReference> refs = service.resolve(List.of("skill://my-skill"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.SKILL);
        assertThat(refs.get(0).source()).isEqualTo("my-skill");
    }

    @Test
    void resolve_diffReference() {
        List<ContextReference> refs = service.resolve(List.of("@diff"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.DIFF);
        assertThat(refs.get(0).success()).isTrue();
    }

    @Test
    void resolve_stagedReference() {
        List<ContextReference> refs = service.resolve(List.of("@staged"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.STAGED);
    }

    @Test
    void resolve_gitReference() {
        List<ContextReference> refs = service.resolve(List.of("@git:3"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.GIT);
        assertThat(refs.get(0).source()).isEqualTo("3");
    }

    @Test
    void resolve_gitReference_defaultCount() {
        List<ContextReference> refs = service.resolve(List.of("@git"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.GIT);
    }

    @Test
    void resolve_folderReference() {
        List<ContextReference> refs = service.resolve(List.of("@folder:src/main"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FOLDER);
        assertThat(refs.get(0).source()).isEqualTo("src/main");
    }

    @Test
    void resolve_folderUriReference() {
        List<ContextReference> refs = service.resolve(List.of("folder://src/main"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FOLDER);
    }

    @Test
    void resolve_unknownReference() {
        List<ContextReference> refs = service.resolve(List.of("@unknown_type:something"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.UNKNOWN);
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void resolve_emptyString_skipped() {
        assertThat(service.resolve(List.of("", "  "))).isEmpty();
    }

    @Test
    void resolve_fileReference(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test content");
        properties.getCore().setWorkingDirectory(tempDir.toString());
        List<ContextReference> refs = service.resolve(List.of(file.toString()));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FILE);
    }

    @Test
    void resolve_fileUriReference() {
        List<ContextReference> refs = service.resolve(List.of("file:///tmp/somefile.txt"));
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).type()).isEqualTo(ReferenceType.FILE);
    }

    // ── Sensitive path blocking tests ──────────────────────────────

    @Test
    void loadFile_sensitiveSshFile_blocked() {
        String sshPath = System.getProperty("user.home") + "/.ssh/id_rsa";
        ContextReference ref = new ContextReference(ReferenceType.FILE, sshPath, "id_rsa", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("denied");
        assertThat(result.get()).contains("sensitive");
    }

    @Test
    void loadFile_sensitiveAwsDir_blocked() {
        String awsPath = System.getProperty("user.home") + "/.aws/credentials";
        ContextReference ref = new ContextReference(ReferenceType.FILE, awsPath, "credentials", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("denied");
    }

    @Test
    void loadFile_envFile_blocked(@TempDir Path tempDir) throws Exception {
        // Create an .env file in the working directory
        properties.getCore().setWorkingDirectory(tempDir.toString());
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "SECRET_KEY=abc123");
        ContextReference ref = new ContextReference(ReferenceType.FILE, envFile.toString(), ".env", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("denied");
    }

    @Test
    void loadFile_sensitiveGnupgDir_blocked() {
        String gnupgPath = System.getProperty("user.home") + "/.gnupg/private.key";
        ContextReference ref = new ContextReference(ReferenceType.FILE, gnupgPath, "private.key", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("denied");
    }

    @Test
    void loadFile_sensitiveKubeDir_blocked() {
        String kubePath = System.getProperty("user.home") + "/.kube/config";
        ContextReference ref = new ContextReference(ReferenceType.FILE, kubePath, "config", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("denied");
    }

    @Test
    void loadFolder_sensitiveDir_blocked() {
        String sshDir = System.getProperty("user.home") + "/.ssh";
        ContextReference ref = new ContextReference(ReferenceType.FOLDER, sshDir, ".ssh", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("denied");
    }

    @Test
    void loadContent_unknownType_returnsError() {
        ContextReference ref = new ContextReference(ReferenceType.UNKNOWN, "foo", "foo", "unrecognized");
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("failed");
    }

    @Test
    void loadContent_failedReference_returnsError() {
        ContextReference ref = new ContextReference(ReferenceType.FILE, "/nonexistent", "nonexistent", "not found");
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("failed");
    }

    @Test
    void loadContent_fileNotFound_returnsMessage() {
        // Use a path under the working directory that doesn't exist
        String basePath = properties.getCore().getWorkingDirectory();
        ContextReference ref = new ContextReference(ReferenceType.FILE, basePath + "/nonexistent_file.txt", "nonexistent_file.txt", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("not found");
    }

    @Test
    void loadContentWithBudget_emptyRefs_returnsEmpty() {
        Optional<String> result = service.loadContentWithBudget(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void loadContentWithBudget_nullRefs_returnsEmpty() {
        Optional<String> result = service.loadContentWithBudget(null);
        assertThat(result).isEmpty();
    }
}
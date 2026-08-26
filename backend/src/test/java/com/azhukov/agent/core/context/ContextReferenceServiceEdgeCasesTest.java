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
import static org.mockito.Mockito.when;

/**
 * Additional focused tests for DefaultContextReferenceService covering edge cases,
 * null/blank inputs, error handling, and loadContentWithBudget.
 */
@ExtendWith(MockitoExtension.class)
class ContextReferenceServiceEdgeCasesTest {

    @Mock private SkillManager skillManager;

    private AgentProperties properties;
    private DefaultContextReferenceService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        service = new DefaultContextReferenceService(properties, skillManager);
        service.initHttpClient();
    }

    // ─── resolve: null/blank edge cases ───

    @Test
    void resolve_nullList_returnsEmpty() {
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void resolve_emptyList_returnsEmpty() {
        assertThat(service.resolve(List.of())).isEmpty();
    }

    @Test
    void resolve_allNullEntries_returnsEmpty() {
        assertThat(service.resolve(java.util.Arrays.asList(null, null, null))).isEmpty();
    }

    @Test
    void resolve_allBlankEntries_returnsEmpty() {
        assertThat(service.resolve(List.of("", "  ", "   "))).isEmpty();
    }

    @Test
    void resolve_mixedNullBlankAndValid_skipsInvalid() {
        List<ContextReference> result = service.resolve(java.util.Arrays.asList(null, "", "  ", "@diff", null));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.DIFF);
    }

    @Test
    void resolve_trimsWhitespaceBeforeClassifying() {
        List<ContextReference> result = service.resolve(List.of("  @diff  "));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.DIFF);
    }

    // ─── resolve: classify edge cases ───

    @Test
    void resolve_diffWithExtraChars_classifiedAsDiff() {
        List<ContextReference> result = service.resolve(List.of("@diff extra"));
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.DIFF);
    }

    @Test
    void resolve_stagedWithExtraChars_classifiedAsStaged() {
        List<ContextReference> result = service.resolve(List.of("@staged extra"));
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.STAGED);
    }

    @Test
    void resolve_skillProtocol_emptyName_classifiedAsSkill() {
        List<ContextReference> result = service.resolve(List.of("skill://"));
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.SKILL);
        assertThat(result.get(0).source()).isEmpty();
    }

    @Test
    void resolve_fileProtocol_emptyPath_classifiedAsFile() {
        List<ContextReference> result = service.resolve(List.of("file://"));
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.FILE);
    }

    @Test
    void resolve_folderProtocol_emptyPath_classifiedAsFolder() {
        List<ContextReference> result = service.resolve(List.of("folder://"));
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.FOLDER);
    }

    @Test
    void resolve_atPrefixNonExistentFile_classifiedAsUnknown() {
        List<ContextReference> result = service.resolve(List.of("@totally-unknown-path-xyz123"));
        assertThat(result.get(0).type()).isEqualTo(ReferenceType.UNKNOWN);
        assertThat(result.get(0).success()).isFalse();
    }

    // ─── loadContent: failed reference ───

    @Test
    void loadContent_failedReference_returnsErrorMessage() {
        ContextReference ref = new ContextReference(ReferenceType.UNKNOWN, "ref", "ref", "some error");
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("failed to load reference");
        assertThat(result.get()).contains("some error");
    }

    @Test
    void loadContent_failedReferenceWithEmptyError_treatedAsSuccess() {
        // error is empty string → success() returns true → goes to switch
        ContextReference ref = new ContextReference(ReferenceType.UNKNOWN, "ref", "ref", "");
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("unknown reference type");
    }

    @Test
    void loadContent_nullError_treatedAsSuccess() {
        ContextReference ref = new ContextReference(ReferenceType.UNKNOWN, "ref", "ref", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("unknown reference type");
    }

    // ─── loadContent: file edge cases ───

    @Test
    void loadContent_fileWithSubdirectory_returnsContent() throws Exception {
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Path file = subdir.resolve("test.txt");
        Files.writeString(file, "nested content");
        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "test.txt", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("nested content");
    }

    @Test
    void loadContent_fileWithTraversalOutsideWorkdir_returnsAccessDenied() {
        // Try to access ../../etc/passwd from tempDir
        ContextReference ref = new ContextReference(ReferenceType.FILE,
            "../../etc/passwd", "passwd", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        // Should be either access denied or file not found depending on normalization
        assertThat(result.get()).containsAnyOf("file access denied", "file not found", "file read error");
    }

    @Test
    void loadContent_emptyFile_returnsEmptyContent() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");
        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "empty.txt", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void loadContent_fileExactlyAtMaxBytes_returnsContent() throws Exception {
        // Create file exactly at maxReferenceFileBytes (100_000)
        Path file = tempDir.resolve("exact.txt");
        byte[] data = new byte[100_000];
        Files.write(file, data);
        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "exact.txt", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        // Should be OK since size == maxBytes (not > maxBytes)
        assertThat(result.get()).doesNotContain("file too large");
    }

    @Test
    void loadContent_fileOneByteOverMax_returnsTooLarge() throws Exception {
        Path file = tempDir.resolve("over.txt");
        byte[] data = new byte[100_001];
        Files.write(file, data);
        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "over.txt", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("file too large");
    }

    // ─── loadContent: skill edge cases ───

    @Test
    void loadContent_skillReturnsEmptyString_returnsSkillHeader() {
        when(skillManager.getSkill("empty-skill")).thenReturn("");
        ContextReference ref = new ContextReference(ReferenceType.SKILL, "empty-skill", "empty-skill", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("[skill empty-skill]");
    }

    // ─── loadContentWithBudget ───

    @Test
    void loadContentWithBudget_nullList_returnsEmpty() {
        assertThat(service.loadContentWithBudget(null)).isEmpty();
    }

    @Test
    void loadContentWithBudget_emptyList_returnsEmpty() {
        assertThat(service.loadContentWithBudget(List.of())).isEmpty();
    }

    @Test
    void loadContentWithBudget_multipleRefs_concatenatesContent() throws Exception {
        Path file1 = tempDir.resolve("a.txt");
        Files.writeString(file1, "content-a");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file2, "content-b");

        ContextReference ref1 = new ContextReference(ReferenceType.FILE, file1.toString(), "a.txt", null);
        ContextReference ref2 = new ContextReference(ReferenceType.FILE, file2.toString(), "b.txt", null);

        Optional<String> result = service.loadContentWithBudget(List.of(ref1, ref2));
        assertThat(result).isPresent();
        assertThat(result.get()).contains("content-a");
        assertThat(result.get()).contains("content-b");
    }

    @Test
    void loadContentWithBudget_singleRef_includesDisplayName() throws Exception {
        Path file = tempDir.resolve("named.txt");
        Files.writeString(file, "content");
        ContextReference ref = new ContextReference(ReferenceType.FILE, file.toString(), "named.txt", null);
        Optional<String> result = service.loadContentWithBudget(List.of(ref));
        assertThat(result).isPresent();
        assertThat(result.get()).contains("[named.txt]");
    }

    @Test
    void loadContentWithBudget_allFailedRefs_returnsContentWithErrorMessages() {
        ContextReference ref = new ContextReference(ReferenceType.UNKNOWN, "unknown-ref", "unknown-ref", "error");
        Optional<String> result = service.loadContentWithBudget(List.of(ref));
        assertThat(result).isPresent();
        assertThat(result.get()).contains("failed to load reference");
    }

    // ─── sensitive path: .env file anywhere ───

    @Test
    void loadContent_envFileInTempDir_blockedAsSensitive() throws Exception {
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "SECRET=value");
        ContextReference ref = new ContextReference(ReferenceType.FILE, envFile.toString(), ".env", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("sensitive credential path");
    }

    // ─── loadFolder: edge cases ───

    @Test
    void loadContent_folderWithHiddenFiles_excludesThem() throws Exception {
        Path folder = tempDir.resolve("hiddenfolder");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(".hidden"), "hidden");
        Files.writeString(folder.resolve("visible.txt"), "visible");
        ContextReference ref = new ContextReference(ReferenceType.FOLDER, folder.toString(), "hiddenfolder", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("visible.txt");
        assertThat(result.get()).doesNotContain(".hidden");
    }

    @Test
    void loadContent_folderWithPycache_excludesIt() throws Exception {
        Path folder = tempDir.resolve("pyfolder");
        Files.createDirectories(folder);
        Files.createDirectories(folder.resolve("__pycache__"));
        Files.writeString(folder.resolve("__pycache__").resolve("cache.pyc"), "cache");
        Files.writeString(folder.resolve("main.py"), "print('hi')");
        ContextReference ref = new ContextReference(ReferenceType.FOLDER, folder.toString(), "pyfolder", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("main.py");
        assertThat(result.get()).doesNotContain("__pycache__");
    }

    @Test
    void loadContent_folderIsFile_returnsFolderNotFound() throws Exception {
        Path file = tempDir.resolve("notafolder.txt");
        Files.writeString(file, "content");
        ContextReference ref = new ContextReference(ReferenceType.FOLDER, file.toString(), "notafolder.txt", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("folder not found");
    }

    @Test
    void loadContent_relativeFolder_resolvedAgainstWorkingDir() throws Exception {
        Path folder = tempDir.resolve("relfolder");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("file.txt"), "content");
        properties.getCore().setWorkingDirectory(tempDir.toString());
        service = new DefaultContextReferenceService(properties, skillManager);
        service.initHttpClient();
        ContextReference ref = new ContextReference(ReferenceType.FOLDER, "relfolder", "relfolder", null);
        Optional<String> result = service.loadContent(ref);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("file.txt");
    }
}
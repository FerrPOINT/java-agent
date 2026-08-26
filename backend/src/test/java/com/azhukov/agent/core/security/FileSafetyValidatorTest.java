package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSafetyValidatorTest {

    private AgentProperties properties;
    private FileSafetyValidator validator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getCore().setWorkingDirectory(tempDir.toString());
        validator = new FileSafetyValidator(properties);
    }

    // ─── checkRead ───

    @Test
    void checkRead_nullPath_returnsError() {
        assertThat(validator.checkRead(null)).isEqualTo("Path is null");
    }

    @Test
    void checkRead_normalFile_returnsNull() {
        Path file = tempDir.resolve("test.txt");
        assertThat(validator.checkRead(file)).isNull();
    }

    @Test
    void checkRead_sensitiveNameEnv_returnsError() {
        Path file = tempDir.resolve(".env");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameEnvrc_returnsError() {
        Path file = tempDir.resolve(".envrc");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameNetrc_returnsError() {
        Path file = tempDir.resolve(".netrc");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameIdRsa_returnsError() {
        Path file = tempDir.resolve("id_rsa");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameIdEd25519_returnsError() {
        Path file = tempDir.resolve("id_ed25519");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameToken_returnsError() {
        Path file = tempDir.resolve("token.txt");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameSecret_returnsError() {
        Path file = tempDir.resolve("secret.dat");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNamePassword_returnsError() {
        Path file = tempDir.resolve("password.cfg");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameApiKey_returnsError() {
        Path file = tempDir.resolve("api_key.json");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameApiKeyConcat_returnsError() {
        Path file = tempDir.resolve("apikey.json");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sensitiveNameCaseInsensitive_returnsError() {
        Path file = tempDir.resolve("TOKEN.txt");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_sshIdInPath_returnsError() {
        Path file = tempDir.resolve(".ssh/id_test");
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }

    @Test
    void checkRead_allowedPathRestriction_blocksOutside() {
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        Path outside = Path.of("/tmp").resolve("outside-file.txt");
        assertThat(validator.checkRead(outside)).contains("Path outside allowed roots");
    }

    @Test
    void checkRead_allowedPathRestriction_allowsInside() {
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        Path inside = tempDir.resolve("inside.txt");
        assertThat(validator.checkRead(inside)).isNull();
    }

    @Test
    void checkRead_fileAllowedPathsRestriction_blocksOutside() {
        properties.getFile().getAllowedPaths().add(tempDir.toString());
        Path outside = Path.of("/tmp").resolve("outside-file2.txt");
        assertThat(validator.checkRead(outside)).contains("Path outside allowed roots");
    }

    @Test
    void checkRead_fileAllowedPathsRestriction_allowsInside() {
        properties.getFile().getAllowedPaths().add(tempDir.toString());
        Path inside = tempDir.resolve("inside2.txt");
        assertThat(validator.checkRead(inside)).isNull();
    }

    @Test
    void checkRead_bothAllowedPathsSet_allowsInside() {
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        properties.getFile().getAllowedPaths().add(tempDir.toString());
        Path inside = tempDir.resolve("both.txt");
        assertThat(validator.checkRead(inside)).isNull();
    }

    // ─── checkWrite ───

    @Test
    void checkWrite_nullPath_returnsError() {
        assertThat(validator.checkWrite(null)).isEqualTo("Path is null");
    }

    @Test
    void checkWrite_normalFile_returnsNull() {
        Path file = tempDir.resolve("output.txt");
        assertThat(validator.checkWrite(file)).isNull();
    }

    @Test
    void checkWrite_blockedExtensionPem_returnsError() {
        Path file = tempDir.resolve("cert.pem");
        assertThat(validator.checkWrite(file)).contains("extension");
        assertThat(validator.checkWrite(file)).contains(".pem");
    }

    @Test
    void checkWrite_blockedExtensionKey_returnsError() {
        Path file = tempDir.resolve("private.key");
        assertThat(validator.checkWrite(file)).contains("extension");
    }

    @Test
    void checkWrite_blockedExtensionP12_returnsError() {
        Path file = tempDir.resolve("cert.p12");
        assertThat(validator.checkWrite(file)).contains("extension");
    }

    @Test
    void checkWrite_blockedExtensionPfx_returnsError() {
        Path file = tempDir.resolve("cert.pfx");
        assertThat(validator.checkWrite(file)).contains("extension");
    }

    @Test
    void checkWrite_blockedExtensionEnv_returnsError() {
        Path file = tempDir.resolve("config.env");
        assertThat(validator.checkWrite(file)).contains("extension");
    }

    @Test
    void checkWrite_blockedExtensionCaseInsensitive_returnsError() {
        Path file = tempDir.resolve("CERT.PEM");
        assertThat(validator.checkWrite(file)).contains("extension");
    }

    @Test
    void checkWrite_customBlockedExtensions_usedInsteadOfDefaults() {
        properties.getFile().getBlockedExtensions().add(".log");
        Path file = tempDir.resolve("test.log");
        assertThat(validator.checkWrite(file)).contains("extension");

        // .pem should no longer be blocked since custom list overrides defaults
        Path pemFile = tempDir.resolve("cert.pem");
        // But .pem files contain "pem" which isn't in SENSITIVE_NAMES, so it should pass
        // Actually wait — the name is "cert.pem", which doesn't match any sensitive name
        assertThat(validator.checkWrite(pemFile)).isNull();
    }

    @Test
    void checkWrite_sensitiveNameEnv_returnsError() {
        Path file = tempDir.resolve(".env");
        assertThat(validator.checkWrite(file)).contains("sensitive file");
    }

    @Test
    void checkWrite_sensitiveNameToken_returnsError() {
        Path file = tempDir.resolve("token.txt");
        assertThat(validator.checkWrite(file)).contains("sensitive file");
    }

    @Test
    void checkWrite_noExtension_notBlocked() {
        Path file = tempDir.resolve("README");
        assertThat(validator.checkWrite(file)).isNull();
    }

    @Test
    void checkWrite_allowedPathRestriction_blocksOutside() {
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        Path outside = Path.of("/tmp").resolve("outside-write.txt");
        assertThat(validator.checkWrite(outside)).contains("Path outside allowed roots");
    }

    @Test
    void checkWrite_allowedPathRestriction_allowsInside() {
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        Path inside = tempDir.resolve("inside-write.txt");
        assertThat(validator.checkWrite(inside)).isNull();
    }

    @Test
    void checkWrite_fileWithNoExtension_notBlockedByExtension() {
        Path file = tempDir.resolve("Makefile");
        assertThat(validator.checkWrite(file)).isNull();
    }

    // ─── combined: sensitive name takes priority over extension ───

    @Test
    void checkWrite_sensitiveNameCheckedAfterExtension() {
        // .env has both a blocked extension AND a sensitive name
        Path file = tempDir.resolve(".env");
        // Extension check comes first
        assertThat(validator.checkWrite(file)).contains("extension");
    }

    @Test
    void checkRead_sensitiveNameCheckedBeforeAllowedPath() {
        properties.getSecurity().getAllowedPaths().add(tempDir.toString());
        Path file = tempDir.resolve(".env");
        // Sensitive name check comes before allowed path check
        assertThat(validator.checkRead(file)).contains("sensitive file");
    }
}
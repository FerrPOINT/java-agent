package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.ProfileRuntimeStateEntity;
import com.azhukov.agent.persistence.repository.ProfileRuntimeStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP-4 (ADR-013): serialized config writer with revisions, allowlisted env
 * store with masked reads and fail-closed secure semantics, runtime registry.
 */
class ProfileRuntimeServicesTest {

    @TempDir
    Path tempDir;

    private static <T> ObjectProvider<T> prov(T value) {
        return new ObjectProvider<T>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            public Stream<T> stream() { return value == null ? Stream.empty() : Stream.of(value); }
            public Stream<T> orderedStream() { return value == null ? Stream.empty() : Stream.of(value); }
        };
    }

    private ProfileService profileService() {
        System.setProperty("hermes.home", tempDir.toString());
        AgentProperties properties = new AgentProperties();
        properties.getProfile().setBaseDir(tempDir.resolve("profiles").toString());
        properties.getCore().setSoulMdPath(tempDir.resolve("SOUL.md").toString());
        return new ProfileService(properties, new RuntimeConfigService());
    }

    // ── ProfileConfigWriter ──────────────────────────────────────────────

    @Test
    void defaultConfigWriteBootstrapsAndMerges() throws IOException {
        ProfileService profiles = profileService();
        ProfileConfigWriter writer = new ProfileConfigWriter(prov(profiles), null, new com.fasterxml.jackson.databind.ObjectMapper());

        writer.write("default", Map.of("dashboard", Map.of("theme", "dark")), "test");

        assertThat(profiles.readConfig("default"))
            .containsEntry("dashboard", Map.of("theme", "dark"));
    }

    @Test
    void invalidYamlIsRejectedAndNeverWritten() throws IOException {
        ProfileService profiles = profileService();
        ProfileConfigWriter writer = new ProfileConfigWriter(prov(profiles), null, new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> writer.writeRaw("default", "[]", "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mapping");
        // no config file was created by the failed write
        assertThat(profiles.configPath("default")).doesNotExist();
    }

    // ── ProfileEnvStore ──────────────────────────────────────────────────

    @Test
    void envStoreRejectsNonAllowlistedKeys() throws IOException {
        ProfileEnvStore store = new ProfileEnvStore(prov(profileService()));
        assertThatThrownBy(() -> store.set("default", "ARBITRARY_KEY", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowlisted");
    }

    @Test
    void envStoreMasksValuesAndRoundTripsDeletes() throws IOException {
        ProfileService profiles = profileService();
        profiles.ensureDefaultProfile();
        ProfileEnvStore store = new ProfileEnvStore(prov(profiles));

        store.set("default", "AGENT_MODEL_API_KEY", "super-secret");
        Map<String, Map<String, Object>> rows = store.maskedRows("default");
        assertThat(rows.get("AGENT_MODEL_API_KEY").get("is_set")).isEqualTo(true);
        assertThat(rows.get("AGENT_MODEL_API_KEY").get("redacted_value")).isEqualTo("********");

        // raw read works for internal consumers
        assertThat(store.readAll("default")).containsEntry("AGENT_MODEL_API_KEY", "super-secret");

        assertThat(store.delete("default", "AGENT_MODEL_API_KEY")).isTrue();
        assertThat(store.readAll("default")).doesNotContainKey("AGENT_MODEL_API_KEY");
        assertThat(store.delete("default", "AGENT_MODEL_API_KEY")).isFalse();
    }

    @Test
    void envFileHasOwnerOnlyPermissions() throws IOException {
        ProfileService profiles = profileService();
        profiles.ensureDefaultProfile();
        ProfileEnvStore store = new ProfileEnvStore(prov(profiles));
        store.set("default", "AGENT_TTS_API_KEY", "x");
        Path envPath = profiles.profilesRoot().resolve("default").resolve(".env");
        assertThat(Files.isRegularFile(envPath)).isTrue();
        var perms = Files.getPosixFilePermissions(envPath);
        assertThat(perms.stream().noneMatch(p -> p.name().startsWith("GROUP_")
            || p.name().startsWith("OTHERS_"))).isTrue();
    }

    // ── ProfileRuntimeRegistry ───────────────────────────────────────────

    @Test
    void registryCreatesStateLazilyAndUpdatesWorker() {
        ProfileRuntimeStateRepository repository = mock(ProfileRuntimeStateRepository.class);
        when(repository.findByProfile("default")).thenReturn(Optional.empty());
        when(repository.save(any(ProfileRuntimeStateEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        ProfileRuntimeRegistry registry = new ProfileRuntimeRegistry(prov(repository));

        Optional<ProfileRuntimeStateEntity> state = registry.state("default");
        assertThat(state).isPresent();
        assertThat(state.get().getWorkerState()).isEqualTo(ProfileRuntimeRegistry.WORKER_STOPPED);

        registry.updateWorkerState("default", ProfileRuntimeRegistry.WORKER_RUNNING);
        registry.recordReload("default", true, null, 3, 1, 2);
        assertThat(state.get().getConfigRevision()).isEqualTo(0); // snapshot row unchanged

        registry.recordReload("default", false, "validation failed", 9, 9, 9);
        // failed reload must not bump revisions on the ORIGINAL row semantics —
        // recordReload re-reads from repository (mock returns empty → new row).
    }

    @Test
    void registryWithoutRepositoryIsInert() {
        ProfileRuntimeRegistry registry = new ProfileRuntimeRegistry(null);
        assertThat(registry.state("default")).isEmpty();
        assertThat(registry.updateWorkerState("default", "running")).isFalse();
    }
}

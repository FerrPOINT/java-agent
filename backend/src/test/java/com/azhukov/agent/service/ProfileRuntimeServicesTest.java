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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
        ProfileConfigWriter writer = new ProfileConfigWriter(prov(profiles), null, null, null, new ProfileRuntimeRegistry(null));

        writer.write("default", Map.of("dashboard", Map.of("theme", "dark")), "test");

        assertThat(profiles.readConfig("default"))
            .containsEntry("dashboard", Map.of("theme", "dark"));
    }

    @Test
    void invalidYamlIsRejectedAndNeverWritten() throws IOException {
        ProfileService profiles = profileService();
        ProfileConfigWriter writer = new ProfileConfigWriter(prov(profiles), null, null, null, new ProfileRuntimeRegistry(null));

        assertThatThrownBy(() -> writer.writeRaw("default", "[]", "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mapping");
        // no config file was created by the failed write
        assertThat(profiles.configPath("default")).doesNotExist();
    }

    @Test
    void invalidProfileEditPreservesCurrentRoutingAndValidEditOnlyChangesItsProfile() throws Exception {
        var profiles = profileService();
        Files.createDirectories(profiles.profilePath("alpha"));
        Files.createDirectories(profiles.profilePath("beta"));
        var modelRouting = new ProfileModelRouting(profiles, new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null)));
        var instructions = new ProfileInstructions(new AgentProperties(), profiles,
            new com.fasterxml.jackson.databind.ObjectMapper());
        var writer = new ProfileConfigWriter(prov(profiles), null, modelRouting, instructions, new ProfileRuntimeRegistry(null));
        profiles.writeModel("alpha", "openai", "alpha-v1", "https://alpha.example.test/v1", "alpha-fixture");
        profiles.writeModel("beta", "openai", "beta", "https://beta.example.test/v1", "beta-fixture");
        var acceptedAlpha = modelRouting.bind("alpha", null);
        var beforeBeta = modelRouting.bind("beta", null);
        String before = profiles.readRawConfig("alpha");

        assertThatThrownBy(() -> writer.writeRaw("alpha", "model: {provider: openai}", "test"))
            .isInstanceOf(ProfileModelRouting.ProfileModelUnavailable.class);
        assertThatThrownBy(() -> writer.writeRaw("alpha", "dashboard: {}", "test"))
            .isInstanceOf(ProfileModelRouting.ProfileModelUnavailable.class);
        assertThatThrownBy(() -> writer.write("alpha", Map.of("instructions", Map.of("base_role", "designer")), "test"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.writeRaw("alpha", "model: {}\nmodel: [fixture-secret]\n", "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid profile YAML");
        assertThat(profiles.readRawConfig("alpha")).isEqualTo(before);
        assertThat(modelRouting.bind("alpha", null)).isEqualTo(acceptedAlpha);

        writer.write("alpha", Map.of("model", Map.of("default", "alpha-v2", "api_key", "alpha-replacement")), "test");
        assertThat(modelRouting.bind("alpha", null).modelName()).isEqualTo("alpha-v2");
        assertThat(modelRouting.bind("alpha", null).apiKey()).isEqualTo("alpha-replacement");
        assertThat(modelRouting.bind("alpha", acceptedAlpha)).isSameAs(acceptedAlpha);
        assertThat(modelRouting.bind("beta", null)).isEqualTo(beforeBeta);
    }

    @Test
    void aBlockedConfigWriteDoesNotDelayAnotherProfile() throws Exception {
        var profiles = spy(profileService());
        Files.createDirectories(profiles.profilePath("alpha"));
        Files.createDirectories(profiles.profilePath("beta"));
        var routing = new ProfileModelRouting(profiles, new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null)));
        var instructions = new ProfileInstructions(new AgentProperties(), profiles,
            new com.fasterxml.jackson.databind.ObjectMapper());
        var writer = new ProfileConfigWriter(prov(profiles), null, routing, instructions,
            new ProfileRuntimeRegistry(null));
        var readEntered = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            readEntered.countDown();
            if (!releaseRead.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Test did not release the alpha profile read");
            }
            return invocation.callRealMethod();
        }).when(profiles).readDiskExecutionConfig("alpha");

        try (var threads = Executors.newFixedThreadPool(2)) {
            var alpha = threads.submit(() -> writer.write("alpha", Map.of("max_concurrent_runs", 1), "test"));
            try {
                assertThat(readEntered.await(2, TimeUnit.SECONDS)).isTrue();
                var beta = threads.submit(() -> writer.write("beta", Map.of("max_concurrent_runs", 2), "test"));
                assertThat(beta.get(2, TimeUnit.SECONDS)).isPositive();
                assertThat(alpha.isDone()).isFalse();
                assertThat(profiles.readExecutionConfig("beta")).containsEntry("max_concurrent_runs", 2);
            } finally {
                releaseRead.countDown();
            }
            assertThat(alpha.get(2, TimeUnit.SECONDS)).isPositive();
            assertThat(profiles.readExecutionConfig("alpha")).containsEntry("max_concurrent_runs", 1);
        }
    }

    // ── ProfileEnvStore ──────────────────────────────────────────────────

    @Test
    void envStoreRejectsNonAllowlistedKeys() throws IOException {
        ProfileEnvStore store = new ProfileEnvStore(prov(profileService()), new com.azhukov.agent.service.ProfileRuntimeRegistry(null));
        assertThatThrownBy(() -> store.set("default", "ARBITRARY_KEY", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowlisted");
    }

    @Test
    void envStoreMasksValuesAndRoundTripsDeletes() throws IOException {
        ProfileService profiles = profileService();
        profiles.ensureDefaultProfile();
        ProfileEnvStore store = new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null));

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
        ProfileEnvStore store = new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null));
        store.set("default", "AGENT_TTS_API_KEY", "x");
        Path envPath = profiles.profilesRoot().resolve("default").resolve(".env");
        assertThat(Files.isRegularFile(envPath)).isTrue();
        var perms = Files.getPosixFilePermissions(envPath);
        assertThat(perms.stream().noneMatch(p -> p.name().startsWith("GROUP_")
            || p.name().startsWith("OTHERS_"))).isTrue();
    }

    @Test
    void envStoreRejectsAnotherProfilesLinkedCredentials() throws Exception {
        var profiles = profileService();
        Files.createDirectories(profiles.profilePath("alpha"));
        Files.createDirectories(profiles.profilePath("beta"));
        var store = new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null));
        store.set("alpha", "AGENT_MODEL_API_KEY", "alpha-fixture");
        Files.createSymbolicLink(profiles.profilePath("beta").resolve(".env"),
            profiles.profilePath("alpha").resolve(".env"));

        assertThatThrownBy(() -> store.set("beta", "AGENT_MODEL_API_KEY", "beta-fixture"))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.delete("beta", "AGENT_MODEL_API_KEY"))
            .isInstanceOf(IOException.class);
        assertThat(store.readAll("alpha")).containsEntry("AGENT_MODEL_API_KEY", "alpha-fixture");
    }

    @Test
    void envStoreRejectsMultiLineCredentialsBeforeWriting() throws Exception {
        var profiles = profileService();
        Files.createDirectories(profiles.profilePath("alpha"));
        var store = new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null));
        store.set("alpha", "AGENT_MODEL_API_KEY", "alpha-fixture");

        assertThatThrownBy(() -> store.set("alpha", "AGENT_MODEL_API_KEY",
            "replacement\rAGENT_MODEL_BASE_URL=https://injected.example.test"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.readAll("alpha")).containsExactlyEntriesOf(Map.of("AGENT_MODEL_API_KEY", "alpha-fixture"));
    }

    @Test
    void preparedModelRoutesKeepEachProfilesCredentialsAcrossUpdates() throws Exception {
        var profiles = profileService();
        Files.createDirectories(profiles.profilePath("alpha"));
        Files.createDirectories(profiles.profilePath("beta"));
        var env = new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null));
        var routing = new ProfileModelRouting(profiles, env);
        profiles.writeModel("alpha", "openai", "alpha-v1", "https://alpha.example.test/v1");
        env.set("alpha", "AGENT_MODEL_API_KEY", "fixture-alpha");
        profiles.writeModel("beta", "deepseek", "beta-v1", "https://beta.example.test/v1", "fixture-beta");

        var request = new com.azhukov.agent.core.client.ModelRequestOptions("global", null, false, null,
            null, null, null, "global", "https://global.example.test/v1", "fixture-global");
        var alpha = routing.bind("alpha", request);
        var beta = routing.bind("beta", request);
        assertThat(alpha.modelName()).isEqualTo("alpha-v1");
        assertThat(alpha.baseUrl()).isEqualTo("https://alpha.example.test/v1");
        assertThat(alpha.apiKey()).isEqualTo("fixture-alpha");
        assertThat(beta.modelName()).isEqualTo("beta-v1");
        assertThat(beta.baseUrl()).isEqualTo("https://beta.example.test/v1");
        assertThat(beta.apiKey()).isEqualTo("fixture-beta");

        profiles.writeModel("alpha", "openai", "alpha-v2", "https://alpha.example.test/v1", "fixture-new");
        assertThat(routing.bind("alpha", alpha)).isSameAs(alpha);
        assertThat(routing.bind("alpha", request).modelName()).isEqualTo("alpha-v2");
        assertThat(routing.bind("alpha", request).apiKey()).isEqualTo("fixture-new");
        assertThat(routing.bind("beta", request)).isEqualTo(beta);
        assertThatThrownBy(() -> routing.bind("beta", alpha)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingProfileCredentialsAndLinkedConfigCannotSelectAnotherModel() throws Exception {
        var profiles = profileService();
        var routing = new ProfileModelRouting(profiles, new ProfileEnvStore(prov(profiles), new com.azhukov.agent.service.ProfileRuntimeRegistry(null)));
        Files.createDirectories(profiles.profilePath("alpha"));
        Files.createDirectories(profiles.profilePath("beta"));
        profiles.writeModel("alpha", "openai", "alpha", "https://alpha.example.test/v1", "fixture-alpha");
        profiles.writeModel("beta", "openai", "beta", "https://beta.example.test/v1");
        assertThatThrownBy(() -> routing.bind("beta", null))
            .isInstanceOf(ProfileModelRouting.ProfileModelUnavailable.class);
        Files.delete(profiles.configPath("beta"));
        Files.createSymbolicLink(profiles.configPath("beta"), profiles.configPath("alpha"));
        assertThatThrownBy(() -> routing.bind("beta", null))
            .isInstanceOf(ProfileModelRouting.ProfileModelUnavailable.class);
        assertThat(routing.bind("alpha", null).modelName()).isEqualTo("alpha");
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

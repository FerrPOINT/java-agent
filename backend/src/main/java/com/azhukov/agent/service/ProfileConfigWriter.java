package com.azhukov.agent.service;

import com.azhukov.agent.persistence.entity.ProfileConfigRevisionEntity;
import com.azhukov.agent.persistence.repository.ProfileConfigRevisionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** Validates and atomically writes profile settings, with an audit revision for each write. */
@Service
@Slf4j
public class ProfileConfigWriter {

    private final ObjectProvider<ProfileService> profileServiceProvider;
    private final ObjectProvider<ProfileConfigRevisionRepository> revisionProvider;
    private final ProfileModelRouting modelRouting;
    private final ProfileInstructions instructions;
    private final ProfileRuntimeRegistry runtime;

    public ProfileConfigWriter(ObjectProvider<ProfileService> profileServiceProvider,
                               ObjectProvider<ProfileConfigRevisionRepository> revisionProvider,
                               ProfileModelRouting modelRouting, ProfileInstructions instructions,
                               ProfileRuntimeRegistry runtime) {
        this.profileServiceProvider = profileServiceProvider;
        this.revisionProvider = revisionProvider;
        this.modelRouting = modelRouting;
        this.instructions = instructions;
        this.runtime = runtime;
    }

    /** Merge and apply a profile config update. Returns the applied revision. */
    public long write(String profile, Map<String, Object> incoming, String actor) throws IOException {
        ProfileService profiles = profiles();
        String canon = profiles.normalizeProfileName(profile);
        synchronized (runtime.lockFor(canon)) {
            prepareProfile(profiles, canon);
            Map<String, Object> merged = profiles.readExecutionConfig(canon);
            boolean hadModel = merged.containsKey("model");
            deepMerge(merged, incoming);
            return apply(profiles, canon, dumpYaml(merged), merged, hadModel, actor);
        }
    }

    /** Replace the dashboard editor YAML after validating the candidate. */
    public long writeRaw(String profile, String yamlText, String actor) throws IOException {
        ProfileService profiles = profiles();
        String canon = profiles.normalizeProfileName(profile);
        synchronized (runtime.lockFor(canon)) {
            prepareProfile(profiles, canon);
            Map<String, Object> candidate = ProfileService.parseExecutionConfig(yamlText);
            // An explicit model in the candidate can also repair an invalid prior file.
            boolean hadModel = !"default".equals(canon) && !candidate.containsKey("model")
                && profiles.readExecutionConfig(canon).containsKey("model");
            return apply(profiles, canon, yamlText, candidate, hadModel, actor);
        }
    }

    private ProfileService profiles() {
        ProfileService profiles = profileServiceProvider.getIfAvailable();
        if (profiles == null) {
            throw new IllegalStateException("Profile service is unavailable");
        }
        return profiles;
    }

    private static void prepareProfile(ProfileService profiles, String profile) throws IOException {
        if ("default".equals(profile)) {
            profiles.ensureDefaultProfile();
        }
        profiles.requireKnownProfile(profile);
    }

    private long apply(ProfileService profiles, String profile, String yaml, Map<String, Object> candidate,
                       boolean hadModel, String actor) throws IOException {
        long revision = nextRevision(profile);
        try {
            validate(profile, candidate, hadModel);
            profiles.writeRawConfig(profile, yaml);
        } catch (IOException | RuntimeException e) {
            recordRevision(profile, revision, actor, "failed", "Profile configuration was rejected");
            throw e;
        }
        recordRevision(profile, revision, actor, "applied", null);
        return revision;
    }

    private void validate(String profile, Map<String, Object> candidate, boolean hadModel) {
        if (candidate.containsKey("agent") && !(candidate.get("agent") instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("agent section must be a mapping");
        }
        if ("default".equals(profile)) {
            return;
        }
        ApiRunAdmissionService.configuredLimit(candidate, ApiRunAdmissionService.DEFAULT_MAX_CONCURRENT_RUNS);
        if (hadModel || candidate.containsKey("model")) {
            modelRouting.validateCandidate(profile, candidate);
        }
        try {
            var base = instructions.loadBase(candidate.get("instructions"));
            for (String skill : base.skills().keySet()) {
                base.readSkill(skill);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("The selected Base instructions or skills are unavailable");
        }
    }

    private long nextRevision(String profile) {
        ProfileConfigRevisionRepository repository = revisionProvider == null
            ? null : revisionProvider.getIfAvailable();
        if (repository == null) {
            return System.currentTimeMillis();
        }
        return repository.findFirstByProfileOrderByRevisionDesc(profile)
            .map(previous -> previous.getRevision() + 1)
            .orElse(1L);
    }

    private void recordRevision(String profile, long revision, String actor, String status, String detail) {
        ProfileConfigRevisionRepository repository = revisionProvider == null
            ? null : revisionProvider.getIfAvailable();
        if (repository == null) {
            return;
        }
        try {
            ProfileConfigRevisionEntity entity = new ProfileConfigRevisionEntity();
            entity.setProfile(profile);
            entity.setRevision(revision);
            entity.setActor(actor == null || actor.isBlank() ? "dashboard" : actor);
            entity.setSummaryHash(sha256(profile + ":" + revision + ":" + status + ":" + Instant.now()));
            entity.setStatus(status);
            entity.setDetail(detail);
            repository.save(entity);
        } catch (Exception e) {
            log.warn("Could not record config revision for {}: {}", profile, e.getMessage());
        }
    }

    private String dumpYaml(Map<String, Object> config) {
        try {
            return new org.yaml.snakeyaml.Yaml().dump(config);
        } catch (Exception e) {
            throw new IllegalArgumentException("Config is not serializable: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map && target.get(key) instanceof Map existing) {
                deepMerge((Map<String, Object>) existing, (Map<String, Object>) value);
            } else if (value != null) {
                target.put(key, value);
            }
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "unavailable";
        }
    }
}

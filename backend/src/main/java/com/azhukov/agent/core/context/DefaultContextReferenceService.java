package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ContextReference;
import com.azhukov.agent.core.model.ReferenceType;
import com.azhukov.agent.core.skill.SkillManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class DefaultContextReferenceService implements ContextReferenceService {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextReferenceService.class);

    private final AgentProperties properties;
    private final SkillManager skillManager;
    private final HttpClient httpClient;

    public DefaultContextReferenceService(AgentProperties properties, SkillManager skillManager) {
        this.properties = properties;
        this.skillManager = skillManager;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.getCore().getHttpClientTimeoutSeconds()))
            .build();
    }

    @Override
    public List<ContextReference> resolve(List<String> refs) {
        List<ContextReference> result = new ArrayList<>();
        if (refs == null) {
            return result;
        }
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            result.add(classify(ref.trim()));
        }
        return result;
    }

    @Override
    public Optional<String> loadContent(ContextReference reference) {
        if (!reference.success()) {
            return Optional.of("[failed to load reference: " + reference.error() + "]");
        }
        return switch (reference.type()) {
            case FILE -> loadFile(reference.source());
            case URL -> loadUrl(reference.source());
            case SKILL -> loadSkill(reference.source());
            case UNKNOWN -> Optional.of("[unknown reference type: " + reference.source() + "]");
        };
    }

    private ContextReference classify(String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) {
            return new ContextReference(ReferenceType.URL, ref, ref, null);
        }
        if (ref.startsWith("skill://")) {
            return new ContextReference(ReferenceType.SKILL, ref.substring(8), ref, null);
        }
        if (ref.startsWith("file://")) {
            String path = ref.substring(7);
            return new ContextReference(ReferenceType.FILE, path, Paths.get(path).getFileName().toString(), null);
        }
        Path candidate = Paths.get(ref);
        if (Files.exists(candidate)) {
            return new ContextReference(ReferenceType.FILE, ref, candidate.getFileName().toString(), null);
        }
        return new ContextReference(ReferenceType.UNKNOWN, ref, ref, "unrecognized reference");
    }

    private Optional<String> loadFile(String source) {
        try {
            Path path = Paths.get(source).toAbsolutePath().normalize();
            Path base = Paths.get(properties.getCore().getWorkingDirectory()).toAbsolutePath().normalize();
            if (!path.startsWith(base)) {
                return Optional.of("[file access denied: " + source + "]");
            }
            if (!Files.exists(path)) {
                return Optional.of("[file not found: " + source + "]");
            }
            long maxBytes = properties.getCore().getMaxReferenceFileBytes();
            if (Files.size(path) > maxBytes) {
                return Optional.of("[file too large: " + source + "]");
            }
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read referenced file {}", source, e);
            return Optional.of("[file read error: " + source + "]");
        }
    }

    private Optional<String> loadUrl(String source) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                .GET()
                .timeout(Duration.ofSeconds(properties.getCore().getHttpClientTimeoutSeconds()))
                .header("User-Agent", properties.getCore().getHttpUserAgent())
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(response.body());
            }
            return Optional.of("[url returned " + response.statusCode() + ": " + source + "]");
        } catch (Exception e) {
            log.warn("Failed to fetch referenced url {}", source, e);
            return Optional.of("[url fetch error: " + source + "]");
        }
    }

    private Optional<String> loadSkill(String source) {
        try {
            String skill = skillManager.getSkill(source);
            if (skill == null) {
                return Optional.of("[skill not found: " + source + "]");
            }
            return Optional.of("[skill " + source + "]\n" + skill);
        } catch (Exception e) {
            log.warn("Failed to load referenced skill {}", source, e);
            return Optional.of("[skill load error: " + source + "]");
        }
    }
}

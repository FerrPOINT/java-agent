package com.azhukov.agent.service;

import com.azhukov.agent.persistence.repository.AttachmentArtifactRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real @Value injection through the shipped YAML, without a database or Boot server. */
class AttachmentStorageConfigurationTest {
    @TempDir Path root;

    @Test
    void environmentBindsTheShippedAttachmentProperty() throws Exception {
        assertConfigured(Map.of("AGENT_ATTACHMENTS_CACHE_ROOT", root.toString()), Map.of(), root);
    }

    @Test
    void explicitPropertyOverrideRetainsPriorityOverContainerEnvironment() throws Exception {
        Path custom = root.resolve("custom");
        assertConfigured(Map.of("AGENT_ATTACHMENTS_CACHE_ROOT", root.resolve("container").toString()),
            Map.of("agent.attachments.cache-root", custom.toString()), custom);
    }

    private void assertConfigured(Map<String, Object> environment, Map<String, Object> overrides,
                                  Path expected) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            var sources = context.getEnvironment().getPropertySources();
            sources.addFirst(new SystemEnvironmentPropertySource("container", environment));
            for (var source : new YamlPropertySourceLoader().load("application",
                    new ClassPathResource("application.yml"))) {
                sources.addLast(source);
            }
            sources.addFirst(new MapPropertySource("explicit", overrides));
            var repository = mock(AttachmentArtifactRepository.class);
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            context.registerBean(AttachmentArtifactRepository.class, () -> repository);
            context.register(AttachmentArtifactService.class);
            context.refresh();
            var result = context.getBean(AttachmentArtifactService.class).register("u1", "default",
                null, null, "cli", "document", "text/plain", "proof.txt", "durable".getBytes());
            assertThat(result.rejection()).isNull();
            assertThat(expected.resolve(result.cachePath())).hasContent("durable");
        }
    }
}

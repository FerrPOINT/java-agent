package com.azhukov.agent.service.tts;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeTtsProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void configuredMaintainedCliProducesAudioWithoutLegacyHttpEndpoint() throws Exception {
        Path fakeEdgeTts = tempDir.resolve("edge-tts");
        Files.writeString(fakeEdgeTts, "#!/bin/sh\nprintf 'fake-mp3'\n");
        Files.setPosixFilePermissions(fakeEdgeTts, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));

        AgentProperties properties = new AgentProperties();
        properties.getTts().setVoice("en-US-AriaNeural");
        EdgeTtsProvider provider = new EdgeTtsProvider(properties, fakeEdgeTts.toString());

        assertThat(provider.synthesize("hello", null)).isEqualTo("fake-mp3".getBytes());
    }
}

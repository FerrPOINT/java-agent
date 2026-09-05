package com.azhukov.agent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * f10b: /image attaches a real pending file reference; /statusbar toggles
 * persistent state instead of a fake acknowledgement.
 */
class ImageStatusbarCommandsTest {

    private CliState cliState;
    private SlashCommandRegistry registry;
    private BackendClient client;

    @BeforeEach
    void setUp() {
        cliState = new CliState();
        registry = new SlashCommandRegistry();
        client = mock(BackendClient.class);
        new UtilityCommands(cliState).registerAll(registry);
    }

    @Test
    void statusbarTogglesRealState() {
        assertThat(cliState.isStatusBarEnabled()).isFalse();
        String first = registry.execute("/statusbar", client, "sid");
        assertThat(first).contains("enabled");
        assertThat(cliState.isStatusBarEnabled()).isTrue();
        String second = registry.execute("/statusbar", client, "sid");
        assertThat(second).contains("disabled");
        assertThat(cliState.isStatusBarEnabled()).isFalse();
    }

    @Test
    void imageAttachesPendingReference(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("shot.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String out = registry.execute("/image " + png, client, "sid");
        assertThat(out).contains("Image attached");
        assertThat(cliState.getPendingImage()).isEqualTo(png);
    }

    @Test
    void imageMissingFileIsRejected(@TempDir Path tmp) {
        String out = registry.execute("/image " + tmp.resolve("nope.png"), client, "sid");
        assertThat(out).contains("File not found");
        assertThat(cliState.getPendingImage()).isNull();
    }
}

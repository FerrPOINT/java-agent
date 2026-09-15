package com.azhukov.agent.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * WP-11 (docs/35): /attach validates path/symlink/size/disposition, stages
 * the file, /attachments lists, /detach removes; the chat body carries
 * structured artifact refs after upload.
 */
class AttachCommandsTest {

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
    void attachStagesValidFile(@TempDir Path tmp) throws Exception {
        Path doc = tmp.resolve("report.pdf");
        Files.write(doc, new byte[] {1, 2, 3});
        String out = registry.execute("/attach " + doc, client, "sid");
        assertThat(out).contains("Image attached");
        assertThat(out).contains("disposition=file");
        assertThat(cliState.getPendingImage()).isEqualTo(doc);
    }

    @Test
    void attachWithDisposition(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("cat.png");
        Files.write(png, new byte[] {1});
        String out = registry.execute("/attach " + png + " photo", client, "sid");
        assertThat(out).contains("disposition=photo");
    }

    @Test
    void attachRejectsMissingFile() {
        assertThat(registry.execute("/attach /no/such/file.txt", client, "sid"))
            .contains("File not found");
    }

    @Test
    void attachRejectsSymlink(@TempDir Path tmp) throws Exception {
        Path real = tmp.resolve("real.txt");
        Files.write(real, new byte[] {1});
        Path link = tmp.resolve("link.txt");
        Files.createSymbolicLink(link, real);
        assertThat(registry.execute("/attach " + link, client, "sid"))
            .contains("Symlinks are not attachable");
    }

    @Test
    void attachRejectsEmptyFile(@TempDir Path tmp) throws Exception {
        Path empty = tmp.resolve("empty.txt");
        Files.write(empty, new byte[0]);
        assertThat(registry.execute("/attach " + empty, client, "sid"))
            .contains("File is empty");
    }

    @Test
    void attachRejectsOversize(@TempDir Path tmp) throws Exception {
        Path big = tmp.resolve("big.bin");
        byte[] data = new byte[20 * 1024 * 1024 + 1];
        Files.write(big, data);
        assertThat(registry.execute("/attach " + big, client, "sid"))
            .contains("File too large");
    }

    @Test
    void attachRejectsUnknownDisposition(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("x.txt");
        Files.write(f, new byte[] {1});
        assertThat(registry.execute("/attach " + f + " banana", client, "sid"))
            .contains("Unknown disposition");
    }

    @Test
    void attachValidatesPhotoExtension(@TempDir Path tmp) throws Exception {
        Path txt = tmp.resolve("notes.txt");
        Files.write(txt, new byte[] {1});
        assertThat(registry.execute("/attach " + txt + " photo", client, "sid"))
            .contains("photo disposition expects an image");
    }

    @Test
    void attachValidatesVoiceExtension(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("img.png");
        Files.write(png, new byte[] {1});
        assertThat(registry.execute("/attach " + png + " voice", client, "sid"))
            .contains("voice disposition expects audio");
    }

    @Test
    void attachmentsListAndDetach(@TempDir Path tmp) throws Exception {
        assertThat(registry.execute("/attachments", client, "sid"))
            .contains("No pending attachments");
        cliState.addPendingAttachment("att_aaa111");
        cliState.addPendingAttachment("att_bbb222");
        String list = registry.execute("/attachments", client, "sid");
        assertThat(list).contains("att_aaa111").contains("att_bbb222");

        String removed = registry.execute("/detach 1", client, "sid");
        assertThat(removed).contains("att_aaa111");
        assertThat(cliState.snapshotPendingAttachments()).containsExactly("att_bbb222");

        assertThat(registry.execute("/detach all", client, "sid"))
            .contains("All pending attachments removed");
        assertThat(cliState.hasPendingAttachments()).isFalse();
    }

    @Test
    void detachOutOfRangeIsHonest() {
        cliState.addPendingAttachment("att_aaa111");
        assertThat(registry.execute("/detach 5", client, "sid"))
            .contains("No attachment #5");
    }

    @Test
    void drainConsumesPendingAttachments() {
        cliState.addPendingAttachment("att_x");
        assertThat(cliState.drainPendingAttachments()).containsExactly("att_x");
        assertThat(cliState.hasPendingAttachments()).isFalse();
    }
}

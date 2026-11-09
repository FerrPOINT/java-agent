package com.azhukov.agent.service;

import com.azhukov.agent.core.security.SecureFileAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WP-j (Hermes vision HEIC/HEIF/AVIF parity): iPhone photos are HEIC; ImageIO
 * cannot decode them, so before the transcode lane the vision tool fed the
 * model garbage bytes.
 */
class ImageTranscodeServiceTest {

    private final ImageTranscodeService service = new ImageTranscodeService();

    private static byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void detectsHeifFamilyByMagicBytes() {
        // ftyp heic
        byte[] heic = {'c', 'i', 'c', 'c', 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        assertThat(ImageTranscodeService.isHeifFamily(heic)).isTrue();
        // ftyp avif
        byte[] avif = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'a', 'v', 'i', 'f'};
        assertThat(ImageTranscodeService.isHeifFamily(avif)).isTrue();
        // ftyp isom (regular MP4 video container) — NOT an image family we handle
        byte[] mp4 = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        assertThat(ImageTranscodeService.isHeifFamily(mp4)).isFalse();
        // PNG magic
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        assertThat(ImageTranscodeService.isHeifFamily(png)).isFalse();
        assertThat(ImageTranscodeService.isHeifFamily(new byte[4])).isFalse();
        assertThat(ImageTranscodeService.isHeifFamily(null)).isFalse();
    }

    @Test
    void decodableInputPassesThroughUntouched() throws Exception {
        byte[] png = pngBytes();
        assertThat(service.toDecodablePng(png)).isSameAs(png);
    }

    @Test
    void jpegInputPassesThroughUntouched() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        byte[] jpg = out.toByteArray();
        assertThat(service.toDecodablePng(jpg)).isSameAs(jpg);
    }

    @Test
    void preflightRejectsOversizedOrMissingDimensions() throws Exception {
        ImageTranscodeService.verifyImageSize("width: 8000\nheight: 5000\nbands: 3\n");
        assertThatThrownBy(() -> ImageTranscodeService.verifyImageSize("width: 8001\nheight: 5000\n"))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("40 megapixels");
        assertThatThrownBy(() -> ImageTranscodeService.verifyImageSize("width: 8000\n"))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("HEIF image dimensions");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void realHeicFileTranscodesToDecodablePng() throws Exception {
        // Generate a real HEIC through libheif's own encoder, then transcode back.
        byte[] png = pngBytes();
        java.nio.file.Path src = java.nio.file.Files.createTempFile("t", ".png");
        java.nio.file.Files.write(src, png);
        java.nio.file.Path heic = java.nio.file.Path.of(src + ".heic");
        Process enc;
        try {
            enc = new ProcessBuilder("heif-enc", src.toString(), "-o", heic.toString())
                .redirectErrorStream(true).start();
        } catch (java.io.IOException binaryMissing) {
            // Encoder binary not installed in this environment — skip, not fail.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "heif-enc unavailable");
            return;
        }
        enc.getInputStream().readAllBytes();
        assertThat(enc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        if (enc.exitValue() != 0 || !java.nio.file.Files.exists(heic)) {
            // Encoder not installed in this environment — skip, not fail.
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "heif-enc unavailable");
        }
        byte[] heicBytes = java.nio.file.Files.readAllBytes(heic);
        assertThat(ImageTranscodeService.isHeifFamily(heicBytes)).isTrue();

        byte[] out = service.toDecodablePng(heicBytes);
        assertThat(ImageIO.read(new ByteArrayInputStream(out))).isNotNull();
        java.nio.file.Files.deleteIfExists(src);
        java.nio.file.Files.deleteIfExists(heic);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterDeadlineCoversSilentProcessAndReapsIt(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir
    ) throws Exception {
        var pidFile = dir.resolve("pid");
        assertThatThrownBy(() -> ImageTranscodeService.runConverter(
            java.util.List.of("sh", "-c", "echo $$ > \"$1\"; exec sleep 30", "test", pidFile.toString()),
            java.time.Duration.ofMillis(500)))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("timed out");
        long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterDoesNotOpenPipeAsPngOutput(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var pipe = dir.resolve("result.png");
        assertThat(new ProcessBuilder("mkfifo", pipe.toString()).start().waitFor()).isZero();
        assertThatThrownBy(() -> ImageTranscodeService.readConvertedPng(
            SecureFileAccess.openRead(pipe)))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("Not a regular file");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterTimeoutStopsChildAfterParentExits(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir
    ) throws Exception {
        var pidFile = dir.resolve("child-pid");
        try {
            assertThatThrownBy(() -> ImageTranscodeService.runConverter(
                java.util.List.of("/bin/sh", "-c", "sleep 30 & echo $! > \"$1\"; sleep 0.05; exit 0",
                    "test", pidFile.toString()),
                java.time.Duration.ofMillis(500)))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("timed out");

            long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
            var procStatus = java.nio.file.Path.of("/proc", Long.toString(pid), "status");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
            while (isRunning(procStatus) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(isRunning(procStatus)).isFalse();
        } finally {
            if (java.nio.file.Files.exists(pidFile)) {
                long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static boolean isRunning(java.nio.file.Path procStatus) throws java.io.IOException {
        try {
            return java.nio.file.Files.readAllLines(procStatus).stream()
                .noneMatch(line -> line.startsWith("State:") && line.contains("(zombie)"));
        } catch (java.nio.file.NoSuchFileException gone) {
            return false;
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterDrainsNoisyOutputAndRejectsOversizedPng(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir
    ) throws Exception {
        var output = dir.resolve("result.png");
        byte[] png = pngBytes();
        java.nio.file.Files.write(output, png);
        var noisy = java.util.List.of("sh", "-c", "head -c 1048576 /dev/zero");
        ImageTranscodeService.runConverter(noisy, java.time.Duration.ofSeconds(3));
        assertThat(ImageTranscodeService.readConvertedPng(
            SecureFileAccess.openRead(output))).isEqualTo(png);

        java.nio.ByteBuffer.wrap(png).putInt(16, 10_000).putInt(20, 10_000);
        java.nio.file.Files.write(output, png);
        assertThatThrownBy(() -> ImageTranscodeService.readConvertedPng(
            SecureFileAccess.openRead(output)))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("40 megapixels");

        try (var file = new java.io.RandomAccessFile(output.toFile(), "rw")) {
            file.setLength(64L * 1024 * 1024 + 1);
        }
        assertThatThrownBy(() -> ImageTranscodeService.readConvertedPng(
            SecureFileAccess.openRead(output)))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("64 MiB");
    }

    @Test
    void shrinkerIntegratesTranscode() throws Exception {
        com.azhukov.agent.config.AgentProperties props = new com.azhukov.agent.config.AgentProperties();
        ImageShrinkerService shrinker = new ImageShrinkerService(props, service);
        byte[] png = pngBytes();
        String base64 = Base64.getEncoder().encodeToString(png);
        var out = shrinker.prepareImage(base64);
        assertThat(out.mimeType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(out.base64())))).isNotNull();
    }
}

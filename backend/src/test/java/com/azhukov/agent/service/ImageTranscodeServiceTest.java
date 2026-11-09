package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"sample.heic", "sample.avif"})
    @EnabledOnOs(OS.LINUX)
    @EnabledIf("hasVips")
    void decodesRealHeifFixture(String name) throws Exception {
        // Synthetic 32x32 gradient, encoded once with heif-enc and FFmpeg/libaom.
        try (var fixture = getClass().getResourceAsStream("/fixtures/vision/" + name)) {
            assertThat(fixture).isNotNull();
            var bytes = fixture.readAllBytes();
            assertThat(ImageTranscodeService.isHeifFamily(bytes)).isTrue();
            var png = ImageIO.read(new ByteArrayInputStream(service.toDecodablePng(bytes)));
            assertThat(png).isNotNull();
            assertThat(png.getWidth()).isEqualTo(32);
            assertThat(png.getHeight()).isEqualTo(32);
        }
    }

    private static boolean hasVips() {
        return java.util.stream.Stream.of("vips", "vipsheader").allMatch(binary ->
            java.util.Arrays.stream(System.getenv("PATH").split(java.io.File.pathSeparator))
                .map(dir -> java.nio.file.Path.of(dir, binary)).anyMatch(java.nio.file.Files::isExecutable));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterDeadlineCoversSilentProcessAndReapsIt(
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir
    ) throws Exception {
        var pidFile = dir.resolve("pid");
        assertThatThrownBy(() -> ImageTranscodeService.runConverter(
            java.util.List.of("sh", "-c", "echo $$ > \"$1\"; exec sleep 30", "test", pidFile.toString()),
            new byte[0], 1024, System.nanoTime() + java.time.Duration.ofMillis(500).toNanos()))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("timed out");
        long pid = Long.parseLong(java.nio.file.Files.readString(pidFile).strip());
        assertThat(ProcessHandle.of(pid).filter(ProcessHandle::isAlive)).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false, false", "true, false", "true, true"})
    @EnabledOnOs(OS.LINUX)
    void converterTimeoutStopsChildHoldingProcessPipes(boolean holdStdin, boolean keepParentAlive,
        @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir
    ) throws Exception {
        var pidFile = dir.resolve("child-pid");
        String child = holdStdin
            ? "exec 3<&0; sleep 6 <&3 3<&- >/dev/null 2>&1 &"
            : "sleep 6 &";
        String parent = keepParentAlive ? "wait" : "sleep 0.05; exit 0";
        byte[] input = new byte[holdStdin ? 1024 * 1024 : 0];
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> ImageTranscodeService.runConverter(
                java.util.List.of("/bin/sh", "-c", child + " echo $! > \"$1\"; " + parent,
                    "test", pidFile.toString()),
                input, 1024, start + java.time.Duration.ofMillis(750).toNanos()))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("timed out");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .as("cleanup must finish before the child exits on its own")
                .isLessThan(java.time.Duration.ofSeconds(3));

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
        } catch (java.io.IOException error) {
            // Linux can report ESRCH if the child exits between opening and reading procfs.
            if (error instanceof java.nio.file.NoSuchFileException
                    || "No such process".equals(error.getMessage())) return false;
            throw error;
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterPipesBytesWhileDrainingDiagnostics() throws Exception {
        byte[] input = new byte[256 * 1024];
        new java.util.Random(123).nextBytes(input);
        byte[] output = ImageTranscodeService.runConverter(
            java.util.List.of("/bin/sh", "-c", "head -c 1048576 /dev/zero >&2; cat"),
            input, input.length, System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos());
        assertThat(output).isEqualTo(input);
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void converterRejectsExcessOutputWithoutWaitingForExit() {
        assertThatThrownBy(() -> ImageTranscodeService.runConverter(
            java.util.List.of("/bin/sh", "-c", "head -c 4096 /dev/zero; exec sleep 30"),
            new byte[0], 1024, System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos()))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("output exceeds 1024 bytes");
    }

    @Test
    void shrinkerIntegratesTranscode() throws Exception {
        com.azhukov.agent.config.AgentProperties props = new com.azhukov.agent.config.AgentProperties();
        ImageShrinkerService shrinker = new ImageShrinkerService(props, service);
        byte[] png = pngBytes();
        String base64 = Base64.getEncoder().encodeToString(png);
        // Decodable input returns re-encoded (or same) bytes, never garbage.
        String out = shrinker.shrinkIfNeeded(base64);
        assertThat(ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(out)))).isNotNull();
    }
}

package com.azhukov.agent.service;

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
    @EnabledOnOs(OS.LINUX)
    void realHeicFileTranscodesToDecodablePng() throws Exception {
        // Generate a real HEIC through libheif's own encoder, then transcode back.
        byte[] png = pngBytes();
        java.nio.file.Path src = java.nio.file.Files.createTempFile("t", ".png");
        java.nio.file.Files.write(src, png);
        java.nio.file.Path heic = java.nio.file.Path.of(src + ".heic");
        Process enc = new ProcessBuilder("heif-enc", src.toString(), "-o", heic.toString())
            .redirectErrorStream(true).start();
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

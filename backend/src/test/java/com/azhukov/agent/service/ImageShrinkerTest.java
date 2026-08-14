package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-15: Tests for {@link ImageShrinker}.
 */
class ImageShrinkerTest {

    private ImageShrinker createShrinker() {
        return new ImageShrinker(new AgentProperties());
    }

    private ImageShrinker createShrinker(int maxImageBytes, int maxTotalBytes, double quality) {
        AgentProperties props = new AgentProperties();
        props.getModel().setMaxImageSizeBytes(maxImageBytes);
        props.getModel().setMaxTotalImageSizeBytes(maxTotalBytes);
        props.getModel().setImageJpegQuality(quality);
        return new ImageShrinker(props);
    }

    private String createTestImageBase64(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, width, height);
        g.setColor(java.awt.Color.BLUE);
        for (int i = 0; i < width; i += 10) {
            g.drawLine(i, 0, i, height);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /**
     * Create a large PNG image with random noise that will be > 4MB as PNG.
     */
    private String createLargeNoisyImageBase64(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var rand = new java.util.Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rand.nextInt(256);
                int g = rand.nextInt(256);
                int b = rand.nextInt(256);
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    @DisplayName("small image is not shrunk")
    void smallImageNotShrunk() throws Exception {
        ImageShrinker shrinker = createShrinker();
        String small = createTestImageBase64(10, 10);
        String result = shrinker.shrinkIfNeeded(small);
        assertThat(result).isEqualTo(small);
    }

    @Test
    @DisplayName("null input returns null")
    void nullInputReturnsNull() {
        ImageShrinker shrinker = createShrinker();
        assertThat(shrinker.shrinkIfNeeded((String) null)).isNull();
    }

    @Test
    @DisplayName("blank input returns blank")
    void blankInputReturnsBlank() {
        ImageShrinker shrinker = createShrinker();
        assertThat(shrinker.shrinkIfNeeded("")).isEqualTo("");
    }

    @Test
    @DisplayName("large image is shrunk below threshold")
    void largeImageIsShrunk() throws Exception {
        // Create a large PNG (1200x1200 with random noise) that will be > 4MB as PNG
        String large = createLargeNoisyImageBase64(1200, 1200);
        int originalSize = Base64.getDecoder().decode(large).length;
        assertThat(originalSize).isGreaterThan(4 * 1024 * 1024);

        ImageShrinker shrinker = createShrinker();
        String result = shrinker.shrinkIfNeeded(large);
        assertThat(result).isNotNull();
        int shrunkSize = Base64.getDecoder().decode(result).length;
        assertThat(shrunkSize).isLessThan(originalSize);
        assertThat(shrunkSize).isLessThanOrEqualTo(4 * 1024 * 1024);
    }

    @Test
    @DisplayName("custom threshold — image just under limit is not shrunk")
    void imageUnderCustomLimitNotShrunk() throws Exception {
        String img = createTestImageBase64(100, 100);
        int imgSize = Base64.getDecoder().decode(img).length;
        // Set threshold just above the image size
        ImageShrinker shrinker = createShrinker(imgSize + 1, 100 * 1024 * 1024, 0.85);
        String result = shrinker.shrinkIfNeeded(img);
        assertThat(result).isEqualTo(img);
    }

    @Test
    @DisplayName("list of small images is not shrunk")
    void smallImageListNotShrunk() throws Exception {
        ImageShrinker shrinker = createShrinker();
        String small = createTestImageBase64(10, 10);
        List<String> images = List.of(small, small);
        List<String> result = shrinker.shrinkIfNeeded(images);
        assertThat(result).isEqualTo(images);
    }

    @Test
    @DisplayName("list with one oversized image triggers shrinking")
    void listWithOversizedImageTriggersShrinking() throws Exception {
        String large = createLargeNoisyImageBase64(1200, 1200);
        String small = createTestImageBase64(10, 10);
        ImageShrinker shrinker = createShrinker();
        List<String> result = shrinker.shrinkIfNeeded(List.of(small, large));
        assertThat(result).hasSize(2);
        // Small image unchanged
        assertThat(result.get(0)).isEqualTo(small);
        // Large image shrunk
        int shrunkSize = Base64.getDecoder().decode(result.get(1)).length;
        int originalSize = Base64.getDecoder().decode(large).length;
        assertThat(shrunkSize).isLessThan(originalSize);
    }

    @Test
    @DisplayName("total payload over limit triggers shrinking even if individual images are under")
    void totalPayloadOverLimitTriggersShrinking() throws Exception {
        // Create medium images that individually are under 4MB but together exceed a low total limit
        String medium = createTestImageBase64(500, 500);
        int mediumSize = Base64.getDecoder().decode(medium).length;
        // Set per-image limit high (so individual check passes), but total limit very low
        ImageShrinker shrinker = createShrinker(50 * 1024 * 1024, mediumSize, 0.85);
        List<String> result = shrinker.shrinkIfNeeded(List.of(medium, medium));
        assertThat(result).hasSize(2);
        // Each should be shrunk since total exceeds limit
        int shrunkSize = Base64.getDecoder().decode(result.get(0)).length;
        assertThat(shrunkSize).isLessThanOrEqualTo(mediumSize);
    }

    @Test
    @DisplayName("empty list returns empty list")
    void emptyListReturnsEmpty() {
        ImageShrinker shrinker = createShrinker();
        List<String> result = shrinker.shrinkIfNeeded(List.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null list returns null")
    void nullListReturnsNull() {
        ImageShrinker shrinker = createShrinker();
        List<String> result = shrinker.shrinkIfNeeded((List<String>) null);
        assertThat(result).isNull();
    }
}
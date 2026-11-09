package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-15: Tests for {@link ImageShrinkerService}.
 */
class ImageShrinkerServiceTest {

    private ImageShrinkerService createShrinker() {
        return new ImageShrinkerService(new AgentProperties(), new ImageTranscodeService());
    }

    private ImageShrinkerService createShrinker(int maxImageBytes, double quality) {
        AgentProperties props = new AgentProperties();
        props.getModel().setMaxImageSizeBytes(maxImageBytes);
        props.getModel().setImageJpegQuality(quality);
        return new ImageShrinkerService(props, new ImageTranscodeService());
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
        ImageShrinkerService shrinker = createShrinker();
        String small = createTestImageBase64(10, 10);
        var result = shrinker.prepareImage(small);
        assertThat(result.base64()).isEqualTo(small);
        assertThat(result.mimeType()).isEqualTo("image/png");
    }

    @Test
    void rejectsPngWithValidHeaderButMissingImageData() throws Exception {
        byte[] png = Base64.getDecoder().decode(createTestImageBase64(20, 20));
        byte[] headerOnly = java.util.Arrays.copyOf(png, 33);
        String truncated = Base64.getEncoder().encodeToString(headerOnly);

        assertThatIllegalArgumentException().isThrownBy(() -> createShrinker().prepareImage(truncated))
            .withMessageContaining("Image preparation failed");
    }

    @Test
    void rejectsOversizedWebpHeaderWithoutPixels() {
        byte[] header = ByteBuffer.allocate(30).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(22)
            .put("WEBPVP8X".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(10)
            .putInt(0).put(new byte[] {-1, 31, 0, -1, 31, 0}).array();
        assertThatIllegalArgumentException().isThrownBy(() ->
            createShrinker().prepareImage(Base64.getEncoder().encodeToString(header)));
    }

    @Test
    void rejectsEmptyImageData() {
        var shrinker = createShrinker();
        assertThatIllegalArgumentException().isThrownBy(() -> shrinker.prepareImage(null));
        assertThatIllegalArgumentException().isThrownBy(() -> shrinker.prepareImage(""));
    }

    @Test
    void convertedImageThatCannotFitIsRejected() throws Exception {
        var properties = new AgentProperties();
        var converter = mock(ImageTranscodeService.class);
        var png = Base64.getDecoder().decode(createTestImageBase64(20, 20));
        when(converter.toDecodablePng(any())).thenReturn(png);
        var heif = Base64.getEncoder().encodeToString(new byte[] {0, 0, 0, 12, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'});

        properties.getModel().setMaxImageSizeBytes(png.length);
        var prepared = new ImageShrinkerService(properties, converter).prepareImage(heif);
        assertThat(prepared.mimeType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(prepared.base64())).isEqualTo(png);

        properties.getModel().setMaxImageSizeBytes(1);
        var limited = new ImageShrinkerService(properties, converter);
        assertThatIllegalArgumentException().isThrownBy(() -> limited.prepareImage(heif))
            .withMessageContaining("could not be reduced");
    }

    @Test
    @DisplayName("large image is shrunk below threshold")
    void largeImageIsShrunk() throws Exception {
        // Create a large PNG (1200x1200 with random noise) that will be > 4MB as PNG
        String large = createLargeNoisyImageBase64(1200, 1200);
        int originalSize = Base64.getDecoder().decode(large).length;
        assertThat(originalSize).isGreaterThan(4 * 1024 * 1024);

        ImageShrinkerService shrinker = createShrinker();
        var result = shrinker.prepareImage(large);
        assertThat(result.mimeType()).isEqualTo("image/jpeg");
        int shrunkSize = Base64.getDecoder().decode(result.base64()).length;
        assertThat(shrunkSize).isLessThan(originalSize);
        assertThat(shrunkSize).isLessThanOrEqualTo(4 * 1024 * 1024);
    }

    @Test
    @DisplayName("custom threshold — image just under limit is not shrunk")
    void imageUnderCustomLimitNotShrunk() throws Exception {
        String img = createTestImageBase64(100, 100);
        int imgSize = Base64.getDecoder().decode(img).length;
        // Set threshold just above the image size
        ImageShrinkerService shrinker = createShrinker(imgSize + 1, 0.85);
        assertThat(shrinker.prepareImage(img).base64()).isEqualTo(img);
    }

    @Test
    void rejectsTooManyPixelsBeforeReadingImageData() throws Exception {
        byte[] bytes = Base64.getDecoder().decode(createTestImageBase64(10, 10));
        // Keep only the signature and IHDR. A pixel read would fail on missing IDAT.
        var header = ByteBuffer.wrap(java.util.Arrays.copyOf(bytes, 33));
        header.putInt(16, 8000).putInt(20, 6000);
        String png = Base64.getEncoder().encodeToString(header.array());

        for (int byteLimit : new int[] {1, 1000}) {
            var shrinker = createShrinker(byteLimit, 0.85);
            assertThatIllegalArgumentException().isThrownBy(() -> shrinker.prepareImage(png))
                .withMessageContaining("40 megapixels");
        }
    }
}

package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** Prepares one image within the provider's payload limit. */
@Slf4j
@Component
public class ImageShrinkerService {

    private final int maxImageSizeBytes;
    private final float jpegQuality;
    private final ImageTranscodeService transcodeService;

    public ImageShrinkerService(AgentProperties properties) {
        this(properties, new ImageTranscodeService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ImageShrinkerService(AgentProperties properties, ImageTranscodeService transcodeService) {
        this.maxImageSizeBytes = properties.getModel().getMaxImageSizeBytes();
        this.jpegQuality = (float) properties.getModel().getImageJpegQuality();
        this.transcodeService = transcodeService;
    }

    public record PreparedImage(String base64, String mimeType) {}

    /** Prepare a single image and describe the bytes that will be sent to the provider. */
    public PreparedImage prepareImage(String base64Image) {
        if (base64Image == null || base64Image.isBlank()) {
            throw new IllegalArgumentException("Image data must not be empty");
        }
        try {
            byte[] bytes = transcodeService.toDecodablePng(Base64.getDecoder().decode(base64Image));
            return preparePixels(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Image preparation failed: " + e.getMessage(), e);
        }
    }

    private PreparedImage preparePixels(byte[] bytes) throws IOException {
        boolean fits = bytes.length <= maxImageSizeBytes;
        // The JDK cannot decode WebP, but can forward it within the byte limit.
        if (fits && bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return prepared(bytes, "image/webp");
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image format");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                ImageTranscodeService.verifyPixelCount(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (fits) return prepared(bytes, reader.getOriginatingProvider().getMIMETypes()[0]);

                byte[] shrunk = shrinkToJpeg(image, maxImageSizeBytes);
                if (shrunk == null) {
                    throw new IllegalArgumentException("Image could not be reduced to " + maxImageSizeBytes + " bytes");
                }
                log.info("image-shrink: shrank image from {} bytes to {} bytes", bytes.length, shrunk.length);
                return prepared(shrunk, "image/jpeg");
            } finally {
                reader.dispose();
            }
        }
    }

    private static PreparedImage prepared(byte[] bytes, String mimeType) {
        return new PreparedImage(Base64.getEncoder().encodeToString(bytes), mimeType);
    }

    private byte[] shrinkToJpeg(BufferedImage original, int targetBytes) {
        // Progressive downscale: 1.0, 0.8, 0.6, 0.4, 0.2
        double[] scales = {1.0, 0.8, 0.6, 0.4, 0.2};
        for (double scale : scales) {
            BufferedImage scaled = scaleImage(original, scale);
            byte[] encoded = encodeAsJpeg(scaled, jpegQuality);
            if (encoded != null && encoded.length <= targetBytes) {
                return encoded;
            }
            // If even at this scale we're over, try lower quality
            byte[] lowerQuality = encodeAsJpeg(scaled, jpegQuality * 0.6f);
            if (lowerQuality != null && lowerQuality.length <= targetBytes) {
                log.debug("image-shrink: used reduced quality {} to fit under {} bytes", jpegQuality * 0.6f, targetBytes);
                return lowerQuality;
            }
        }

        // Last resort: smallest scale, lowest quality
        BufferedImage smallest = scaleImage(original, 0.15);
        byte[] lastResort = encodeAsJpeg(smallest, 0.5f);
        if (lastResort != null && lastResort.length <= targetBytes) {
            return lastResort;
        }

        return null;
    }

    private BufferedImage scaleImage(BufferedImage original, double scale) {
        if (scale >= 1.0 && !original.getColorModel().hasAlpha()) {
            return original;
        }
        int newWidth = Math.max(1, (int) (original.getWidth() * scale));
        int newHeight = Math.max(1, (int) (original.getHeight() * scale));

        // JPEG has no alpha channel. Composite transparency against white.
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, newWidth, newHeight);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    private byte[] encodeAsJpeg(BufferedImage image, float quality) {
        // M14: Ensure ImageWriter is disposed in a finally block to prevent resource leak on exception
        ImageWriter writer = null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {

            writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);

            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), params);

            return out.toByteArray();
        } catch (IOException e) {
            log.warn("image-shrink: JPEG encoding failed: {}", e.getMessage());
            return null;
        } finally {
            // M14: Always dispose the writer to release native resources
            if (writer != null) {
                writer.dispose();
            }
        }
    }
}
package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * P2-15: Image shrinking for provider payload limits.
 * <p>
 * Mirrors Hermes {@code try_shrink_image_parts_in_messages} — before sending
 * image content to the LLM provider, checks if the image exceeds the configured
 * per-image or total payload limit. If it does, shrinks the image by resizing
 * and/or re-encoding as JPEG with configurable quality.
 * <p>
 * This prevents API errors from oversized images (e.g. Anthropic's 5 MB cap,
 * OpenAI's per-image limits).
 */
@Slf4j
@Component
public class ImageShrinker {

    private final int maxImageSizeBytes;
    private final int maxTotalImageSizeBytes;
    private final float jpegQuality;

    public ImageShrinker(AgentProperties properties) {
        this.maxImageSizeBytes = properties.getModel().getMaxImageSizeBytes();
        this.maxTotalImageSizeBytes = properties.getModel().getMaxTotalImageSizeBytes();
        this.jpegQuality = (float) properties.getModel().getImageJpegQuality();
    }

    /**
     * Shrink a single base64-encoded image if it exceeds the per-image limit.
     *
     * @param base64Image base64-encoded image data (without data: prefix)
     * @return base64-encoded image data, possibly shrunk; original if no shrink needed
     */
    public String shrinkIfNeeded(String base64Image) {
        if (base64Image == null || base64Image.isBlank()) {
            return base64Image;
        }

        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        if (imageBytes.length <= maxImageSizeBytes) {
            return base64Image;
        }

        byte[] shrunk = shrinkImageBytes(imageBytes, maxImageSizeBytes);
        if (shrunk != null && shrunk.length < imageBytes.length) {
            log.info("image-shrink: shrank image from {} bytes to {} bytes (target {} bytes)",
                imageBytes.length, shrunk.length, maxImageSizeBytes);
            return Base64.getEncoder().encodeToString(shrunk);
        }

        log.warn("image-shrink: could not shrink image below {} bytes (original {} bytes) — sending as-is",
            maxImageSizeBytes, imageBytes.length);
        return base64Image;
    }

    /**
     * Shrink a list of base64-encoded images if their total payload exceeds the
     * total limit, or if any individual image exceeds the per-image limit.
     *
     * @param base64Images list of base64-encoded image data
     * @return list of base64-encoded image data, with oversized images shrunk
     */
    public List<String> shrinkIfNeeded(List<String> base64Images) {
        if (base64Images == null || base64Images.isEmpty()) {
            return base64Images;
        }

        // M13: Decode each image once and cache the bytes, avoiding triple base64 decode
        // (was called in totalSize calculation, anyMatch check, and again in shrinkIfNeeded)
        List<byte[]> decodedBytes = new ArrayList<>(base64Images.size());
        long totalSize = 0;
        boolean needShrink = false;
        for (String s : base64Images) {
            if (s == null) {
                decodedBytes.add(null);
                continue;
            }
            byte[] bytes = Base64.getDecoder().decode(s);
            decodedBytes.add(bytes);
            totalSize += bytes.length;
            if (bytes.length > maxImageSizeBytes) {
                needShrink = true;
            }
        }

        needShrink = needShrink || totalSize > maxTotalImageSizeBytes;

        if (!needShrink) {
            return base64Images;
        }

        log.info("image-shrink: total image payload {} bytes exceeds limit {} bytes — shrinking",
            totalSize, maxTotalImageSizeBytes);

        List<String> result = new ArrayList<>(base64Images.size());
        for (int i = 0; i < base64Images.size(); i++) {
            String s = base64Images.get(i);
            if (s == null) {
                result.add(null);
                continue;
            }
            byte[] bytes = decodedBytes.get(i);
            // M13: Use cached bytes instead of re-decoding
            if (bytes.length <= maxImageSizeBytes) {
                result.add(s);
            } else {
                byte[] shrunk = shrinkImageBytes(bytes, maxImageSizeBytes);
                if (shrunk != null && shrunk.length < bytes.length) {
                    log.info("image-shrink: shrank image from {} bytes to {} bytes (target {} bytes)",
                        bytes.length, shrunk.length, maxImageSizeBytes);
                    result.add(Base64.getEncoder().encodeToString(shrunk));
                } else {
                    log.warn("image-shrink: could not shrink image below {} bytes (original {} bytes) — sending as-is",
                        maxImageSizeBytes, bytes.length);
                    result.add(s);
                }
            }
        }
        return result;
    }

    /**
     * Core image shrinking logic: decode the image, progressively reduce
     * dimensions and re-encode as JPEG until the result fits under the target
     * byte budget or we hit the minimum scale.
     *
     * @param imageBytes  raw image bytes
     * @param targetBytes maximum acceptable size in bytes
     * @return shrunk JPEG bytes, or null if shrinking failed
     */
    byte[] shrinkImageBytes(byte[] imageBytes, int targetBytes) {
        BufferedImage original;
        try (ByteArrayInputStream in = new ByteArrayInputStream(imageBytes)) {
            original = ImageIO.read(in);
        } catch (IOException e) {
            log.warn("image-shrink: could not read image: {}", e.getMessage());
            return null;
        }

        if (original == null) {
            log.warn("image-shrink: ImageIO returned null for input (unsupported format?)");
            return null;
        }

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
        if (lastResort != null && lastResort.length < imageBytes.length) {
            return lastResort;
        }

        return null;
    }

    private BufferedImage scaleImage(BufferedImage original, double scale) {
        if (scale >= 1.0) {
            return original;
        }
        int newWidth = Math.max(1, (int) (original.getWidth() * scale));
        int newHeight = Math.max(1, (int) (original.getHeight() * scale));

        // L11: Use TYPE_INT_ARGB to preserve alpha channel during scaling.
        // JPEG doesn't support alpha, but the encodeAsJpeg step will handle the
        // conversion. Using ARGB here prevents transparent pixels from turning black.
        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        try {
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
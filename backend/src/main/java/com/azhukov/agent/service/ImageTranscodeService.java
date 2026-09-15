package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WP-j (Hermes vision HEIC/HEIF/AVIF parity): iPhone photos arrive as HEIC/
 * HEIF (and increasingly AVIF). {@code ImageIO} cannot decode them on a stock
 * JVM, so before this service the vision lane silently produced a corrupt or
 * empty image — the model saw garbage instead of the photo.
 *
 * This service transcodes such formats to PNG via system converters when
 * available (libheif {@code heif-convert}, ImageMagick {@code convert},
 * AVIF {@code avifdec}) — probed once per process, honest degradation:
 * without a converter the caller gets a clear error naming the missing
 * package instead of a garbage decode.
 */
@Slf4j
@Service
public class ImageTranscodeService {

    /** Magic-byte sniffing — source of truth is the file, not the extension. */
    static boolean isHeifFamily(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return false;
        }
        // ISO-BMFF: bytes 4..7 = "ftyp", bytes 8..11 = brand
        if (bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            String brand = new String(bytes, 8, 4, StandardCharsets.ISO_8859_1);
            return brand.startsWith("heic") || brand.startsWith("heix")
                || brand.startsWith("heim") || brand.startsWith("heis")
                || brand.startsWith("mif1") || brand.startsWith("msf1")
                || brand.startsWith("avif") || brand.startsWith("avis");
        }
        return false;
    }

    private volatile Boolean heifConvertAvailable;
    private volatile Boolean magickAvailable;

    /**
     * Decode a HEIF-family image to PNG bytes. Returns the original bytes
     * when the input is already decodable by ImageIO (fast path), transcodes
     * when a converter exists, and throws with a fix instruction otherwise.
     */
    public byte[] toDecodablePng(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("empty image");
        }
        if (ImageIO.read(new ByteArrayInputStream(bytes)) != null) {
            return bytes; // already decodable (JPEG/PNG/GIF/BMP/WebP-JVM)
        }
        if (!isHeifFamily(bytes)) {
            return bytes; // unknown-but-not-HEIF: let the downstream try
        }
        byte[] png = transcode(bytes);
        BufferedImage check = ImageIO.read(new ByteArrayInputStream(png));
        if (check == null) {
            throw new IOException("HEIF transcode produced an undecodable image");
        }
        return png;
    }

    private byte[] transcode(byte[] heifBytes) throws IOException {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("vision-heif-", ".heic");
            Files.write(tmp, heifBytes);
            if (isHeifConvertAvailable()) {
                return runConverter(List.of("heif-convert", tmp.toString(), tmp + ".png"), tmp + ".png");
            }
            if (isMagickAvailable()) {
                return runConverter(List.of("convert", tmp.toString(), "png:" + tmp + ".png"), tmp + ".png");
            }
            throw new IOException("image is HEIC/HEIF/AVIF but no system converter is installed "
                + "(install libheif-examples for heif-convert)");
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
                deleteQuietly(Path.of(tmp + ".png"));
            }
        }
    }

    private boolean isHeifConvertAvailable() {
        if (heifConvertAvailable == null) {
            heifConvertAvailable = probeBinary("heif-convert");
        }
        return heifConvertAvailable;
    }

    private boolean isMagickAvailable() {
        if (magickAvailable == null) {
            magickAvailable = probeBinary("convert");
        }
        return magickAvailable;
    }

    private byte[] runConverter(List<String> command, String output) throws IOException {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] allOut = p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("converter timed out: " + command.get(0));
            }
            if (p.exitValue() != 0) {
                throw new IOException("converter failed (" + p.exitValue() + "): "
                    + new String(allOut, StandardCharsets.UTF_8).trim());
            }
            Path out = Path.of(output);
            if (!Files.exists(out) || Files.size(out) == 0) {
                throw new IOException("converter produced no output: " + command.get(0));
            }
            return Files.readAllBytes(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("converter interrupted", e);
        }
    }

    private static Boolean probeBinary(String name) {
        try {
            Process p = new ProcessBuilder(name).start();
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
            return true; // found + executed (usage error still means present)
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }
}

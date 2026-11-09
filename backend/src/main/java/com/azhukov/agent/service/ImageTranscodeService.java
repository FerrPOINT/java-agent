package com.azhukov.agent.service;

import com.azhukov.agent.core.security.SecureFileAccess;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
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

    private static final int DIAGNOSTIC_BYTES = 16 * 1024;
    private static final int MAX_PNG_BYTES = 64 * 1024 * 1024;
    private static final int PNG_HEADER_BYTES = 33; // signature and complete IHDR chunk
    private static final long MAX_PNG_PIXELS = 40_000_000;
    private static final Duration CONVERTER_TIMEOUT = Duration.ofSeconds(60);

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
                return runConverter(List.of("heif-convert", tmp.toString(), tmp + ".png"), Path.of(tmp + ".png"), CONVERTER_TIMEOUT);
            }
            if (isMagickAvailable()) {
                return runConverter(List.of("convert", tmp.toString(), "png:" + tmp + ".png"), Path.of(tmp + ".png"), CONVERTER_TIMEOUT);
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

    static byte[] runConverter(List<String> command, Path output, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        // Keep the group addressable by the original PID even if the converter exits first.
        var groupedCommand = new ArrayList<String>(command.size() + 2);
        groupedCommand.add("/usr/bin/setsid");
        groupedCommand.add("--");
        groupedCommand.addAll(command);
        Process process = new ProcessBuilder(groupedCommand).redirectErrorStream(true).start();
        try (var pipe = process.getInputStream()) {
            var diagnostics = new FutureTask<>(() -> {
                byte[] prefix = pipe.readNBytes(DIAGNOSTIC_BYTES);
                pipe.transferTo(java.io.OutputStream.nullOutputStream());
                return prefix;
            });
            Thread.ofVirtual().name("image-converter-output").start(diagnostics);
            boolean diagnosticsDrained = false;
            try {
                process.getOutputStream().close();
                if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException();
                }
                byte[] prefix = diagnostics.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                diagnosticsDrained = true;
                if (process.exitValue() != 0) {
                    throw new IOException("converter failed (" + process.exitValue() + "): "
                        + new String(prefix, StandardCharsets.UTF_8).trim());
                }
                return readConvertedPng(output);
            } catch (TimeoutException e) {
                throw new IOException("converter timed out: " + command.getFirst(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("converter interrupted", e);
            } catch (ExecutionException e) {
                throw new IOException("could not read converter diagnostics", e.getCause());
            } finally {
                if (!diagnosticsDrained) stopConverterGroup(process);
                diagnostics.cancel(true);
            }
        }
    }

    private static void stopConverterGroup(Process process) {
        // Also stop the direct process if cancellation beat setsid during startup.
        if (process.isAlive()) process.destroyForcibly();
        try {
            Process signal = new ProcessBuilder("/bin/bash", "-c", "kill -KILL -- \"-$1\"",
                "image-converter-cleanup", Long.toString(process.pid()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!awaitExit(signal) || signal.exitValue() != 0) {
                log.warn("Could not confirm signal delivery to image converter process group {}", process.pid());
            }
        } catch (IOException failure) {
            log.warn("Could not signal image converter process group {}", process.pid(), failure);
        } finally {
            if (!awaitExit(process)) {
                log.warn("Could not confirm exit of image converter {}", process.pid());
            }
        }
    }

    private static boolean awaitExit(Process process) {
        try {
            process.onExit().orTimeout(1, TimeUnit.SECONDS).join();
            return true;
        } catch (CompletionException failure) {
            return false;
        }
    }

    private static byte[] readConvertedPng(Path output) throws IOException {
        try (var file = SecureFileAccess.openRead(output); var input = Channels.newInputStream(file)) {
            if (file.size() > MAX_PNG_BYTES) throw new IOException("converter output exceeds 64 MiB");
            byte[] header = input.readNBytes(PNG_HEADER_BYTES);
            verifyPngDimensions(header);
            // Decode the same header that passed the pixel limit, even if the file changes.
            try (var png = new SequenceInputStream(new ByteArrayInputStream(header), input)) {
                byte[] bytes = png.readNBytes(MAX_PNG_BYTES + 1);
                if (bytes.length > MAX_PNG_BYTES) throw new IOException("converter output exceeds 64 MiB");
                return bytes;
            }
        }
    }

    private static void verifyPngDimensions(byte[] header) throws IOException {
        var readers = ImageIO.getImageReadersByFormatName("png");
        if (!readers.hasNext()) throw new IOException("PNG image reader is unavailable");
        var reader = readers.next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(header))) {
            reader.setInput(input);
            if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PNG_PIXELS) {
                throw new IOException("converter output exceeds 40 megapixels");
            }
        } finally {
            reader.dispose();
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

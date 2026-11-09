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
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Converts the first HEIF or AVIF image to a bounded PNG for model input. */
@Slf4j
@Service
public class ImageTranscodeService {

    private static final int DIAGNOSTIC_BYTES = 16 * 1024;
    private static final int MAX_PNG_BYTES = 64 * 1024 * 1024;
    private static final int PNG_HEADER_BYTES = 33; // signature and complete IHDR chunk
    private static final long MAX_PNG_PIXELS = 40_000_000;
    private static final Duration CONVERTER_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern IMAGE_SIZE = Pattern.compile(
        "(?m)^width: ([0-9]{1,10})\\Rheight: ([0-9]{1,10})$");

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

    /**
     * Decode a HEIF-family image to PNG bytes. Returns the original bytes
     * when the input is already decodable by ImageIO (fast path), transcodes
     * when a converter exists, and throws with a fix instruction otherwise.
     */
    public byte[] toDecodablePng(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("empty image");
        }
        if (!isHeifFamily(bytes)) {
            return bytes;
        }
        byte[] png = transcode(bytes);
        BufferedImage check = ImageIO.read(new ByteArrayInputStream(png));
        if (check == null) {
            throw new IOException("HEIF transcode produced an undecodable image");
        }
        return png;
    }

    private byte[] transcode(byte[] heifBytes) throws IOException {
        Path work = Files.createTempDirectory("vision-heif-");
        try (var directory = SecureFileAccess.openDirectory(work)) {
            try {
                Path input = work.resolve("source.heic");
                Path output = Path.of("image.png");
                Files.write(input, heifBytes, StandardOpenOption.CREATE_NEW);
                long deadline = System.nanoTime() + CONVERTER_TIMEOUT.toNanos();
                // The header probe and decoder use the same page selection.
                String firstImage = input + "[page=0,n=1]";
                verifyImageSize(runConverter(List.of("vipsheader", "-a", firstImage), CONVERTER_TIMEOUT));
                runConverter(List.of("/usr/bin/prlimit", "--fsize=" + MAX_PNG_BYTES + ":" + MAX_PNG_BYTES,
                    "--", "vips", "copy", firstImage, work.resolve(output).toString()),
                    Duration.ofNanos(deadline - System.nanoTime()));
                return readConvertedPng(directory.openRead(output));
            } finally {
                try {
                    for (Path entry : directory.entries()) {
                        directory.entries().deleteFile(entry.getFileName());
                    }
                } catch (IOException | DirectoryIteratorException failure) {
                    log.warn("Could not clean image converter files in {}", work, failure);
                }
            }
        } finally {
            try {
                Files.deleteIfExists(work);
            } catch (IOException failure) {
                log.warn("Could not remove image converter directory {}", work, failure);
            }
        }
    }

    static void verifyImageSize(String metadata) throws IOException {
        var dimensions = IMAGE_SIZE.matcher(metadata);
        if (!dimensions.find()) {
            throw new IOException("Could not read HEIF image dimensions");
        }
        verifyPixelCount(Long.parseLong(dimensions.group(1)), Long.parseLong(dimensions.group(2)));
    }

    static String runConverter(List<String> command, Duration timeout) throws IOException {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IOException("converter timed out: " + command.getFirst());
        }
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
                return new String(prefix, StandardCharsets.UTF_8);
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

    static byte[] readConvertedPng(SeekableByteChannel file) throws IOException {
        try (var input = Channels.newInputStream(file)) {
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
            verifyPixelCount(reader.getWidth(0), reader.getHeight(0));
        } finally {
            reader.dispose();
        }
    }

    private static void verifyPixelCount(long width, long height) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid image dimensions");
        }
        if (width > MAX_PNG_PIXELS / height) {
            throw new IOException("image exceeds 40 megapixels");
        }
    }
}

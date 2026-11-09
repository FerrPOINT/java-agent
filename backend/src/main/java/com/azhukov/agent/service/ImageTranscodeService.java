package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
    private static final long MAX_IMAGE_PIXELS = 40_000_000;
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

    /** Convert HEIF or AVIF, passing other formats through to the image reader. */
    public byte[] toDecodablePng(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("empty image");
        if (!isHeifFamily(bytes)) return bytes;

        long deadline = System.nanoTime() + CONVERTER_TIMEOUT.toNanos();
        // Both commands read the same owned bytes, with no shared file to replace.
        String firstImage = "stdin[page=0,n=1]";
        byte[] metadata = runConverter(List.of("vipsheader", "-a", firstImage), bytes, DIAGNOSTIC_BYTES, deadline);
        verifyImageSize(new String(metadata, StandardCharsets.UTF_8));
        // A suffix by itself directs libvips output to stdout.
        return runConverter(List.of("vips", "copy", firstImage, ".png"), bytes, MAX_PNG_BYTES, deadline);
    }

    static void verifyImageSize(String metadata) throws IOException {
        var dimensions = IMAGE_SIZE.matcher(metadata);
        if (!dimensions.find()) {
            throw new IOException("Could not read HEIF image dimensions");
        }
        verifyPixelCount(Long.parseLong(dimensions.group(1)), Long.parseLong(dimensions.group(2)));
    }

    static byte[] runConverter(List<String> command, byte[] input, int outputLimit, long deadline)
            throws IOException {
        if (deadline - System.nanoTime() <= 0) {
            throw new IOException("converter timed out: " + command.getFirst());
        }
        var groupedCommand = new ArrayList<>(List.of("/usr/bin/setsid", "--"));
        groupedCommand.addAll(command);
        Process process = new ProcessBuilder(groupedCommand).start();
        var writer = startIo(() -> {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            return null;
        });
        var reader = startIo(() -> {
            try (var stdout = process.getInputStream()) {
                byte[] output = stdout.readNBytes(outputLimit + 1);
                if (output.length > outputLimit) {
                    throw new IOException("converter output exceeds " + outputLimit + " bytes");
                }
                return output;
            }
        });
        var diagnostics = startIo(() -> {
            try (var stderr = process.getErrorStream()) {
                byte[] prefix = stderr.readNBytes(DIAGNOSTIC_BYTES);
                stderr.transferTo(OutputStream.nullOutputStream());
                return new String(prefix, StandardCharsets.UTF_8).trim();
            }
        });
        boolean finished = false;
        try {
            byte[] output = awaitIo(reader, deadline);
            String error = awaitIo(diagnostics, deadline);
            if (!process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            if (process.exitValue() != 0) {
                throw new IOException("converter failed (" + process.exitValue() + "): " + error);
            }
            awaitIo(writer, deadline);
            finished = true;
            return output;
        } catch (TimeoutException e) {
            throw new IOException("converter timed out: " + command.getFirst(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("converter interrupted", e);
        } finally {
            if (!finished) stopConverterGroup(process);
            writer.cancel(true);
            reader.cancel(true);
            diagnostics.cancel(true);
        }
    }

    private static <T> FutureTask<T> startIo(Callable<T> action) {
        var task = new FutureTask<>(action);
        Thread.startVirtualThread(task);
        return task;
    }

    private static <T> T awaitIo(FutureTask<T> task, long deadline)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return task.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("converter I/O failed", e.getCause());
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

    static void verifyPixelCount(long width, long height) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("Invalid image dimensions");
        }
        if (width > MAX_IMAGE_PIXELS / height) {
            throw new IOException("image exceeds 40 megapixels");
        }
    }
}

package com.azhukov.agent.core.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Feature 6: Environment probe — detects local toolchain state and emits
 * a one-line summary when something is unusual.
 *
 * Mirrors Hermes tools/env_probe.py — get_environment_probe_line().
 * Emits at most one short line when something non-default is detected.
 * When the environment looks normal, emits nothing — no token cost.
 */
@Slf4j
@Component
public class EnvironmentProbe {

    private volatile String cachedLine;
    private volatile boolean probed;

    /**
     * Return the cached probe line (building it on first call).
     * Returns "" when the environment is clean.
     */
    public String getProbeLine() {
        if (probed) {
            return cachedLine != null ? cachedLine : "";
        }
        synchronized (this) {
            if (probed) return cachedLine != null ? cachedLine : "";
            try {
                cachedLine = buildProbeLine();
            } catch (Exception e) {
                log.debug("env_probe failed: {}", e.getMessage());
                cachedLine = "";
            }
            probed = true;
            return cachedLine;
        }
    }

    /**
     * Reset cache for tests.
     */
    public void resetCache() {
        probed = false;
        cachedLine = null;
    }

    String buildProbeLine() {
        List<String> bits = new ArrayList<>();

        String javaVersion = System.getProperty("java.version", "");
        String gradleVersion = runCommand("gradle", "--version");
        boolean hasPython3 = commandExists("python3");
        boolean hasPip = commandExists("pip");
        boolean hasUv = commandExists("uv");
        boolean hasGit = commandExists("git");

        String python3Version = hasPython3 ? runCommand("python3", "--version") : "";

        // Only emit when something is unusual
        boolean pythonMissing = !hasPython3;
        boolean pipMissing = !hasPip;
        boolean gitMissing = !hasGit;

        // If everything is normal, stay silent
        if (!pythonMissing && !pipMissing && !gitMissing && hasUv) {
            return "";
        }

        if (!javaVersion.isEmpty()) {
            bits.add("java=" + javaVersion);
        }

        if (gradleVersion != null && !gradleVersion.isEmpty()) {
            bits.add("gradle=" + gradleVersion);
        }

        if (pythonMissing) {
            bits.add("python3=missing");
        } else if (python3Version != null && !python3Version.isEmpty()) {
            bits.add("python3=" + python3Version);
        }

        if (pipMissing) {
            bits.add("pip=missing");
        }

        if (hasUv) {
            bits.add("uv=installed");
        }

        if (gitMissing) {
            bits.add("git=missing");
        } else {
            bits.add("git=installed");
        }

        if (bits.isEmpty()) return "";
        return "Environment: " + String.join(", ", bits) + ".";
    }

    private String runCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) return "";
                // Extract version number from lines like "Python 3.11.5" or "Gradle 8.5"
                return line.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private boolean commandExists(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
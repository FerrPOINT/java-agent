package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Verifies the outbound filesystem boundary once per backend process. */
@Component
@Slf4j
public class ArtifactProcessIsolation {

    public static final Path ARTIFACT_ROOT = Path.of("/artifacts");
    private static final String PROFILE = "pdlc-java-agent";
    private static final Path APPARMOR_LABEL = Path.of("/proc/self/attr/apparmor/current");
    private static final String PROBE_COMMAND = """
        IFS= read -r label < /proc/self/attr/apparmor/current || exit 2
        case "$label" in
            *pdlc-java-agent-worker*" (enforce)") ;;
            *) exit 2 ;;
        esac
        (printf '%s' modified > "$1") 2>/dev/null && exit 1
        exit 0
        """;

    private Boolean verified;

    public synchronized boolean protects(Path root) {
        if (!ARTIFACT_ROOT.equals(root)) return false;
        if (verified == null) {
            verified = verify();
            if (!verified) log.warn("Outbound artifact delivery requires the enforced Java Agent AppArmor profile");
        }
        return verified;
    }

    private boolean verify() {
        Path fixture = null;
        Process child = null;
        try {
            String label = Files.readString(APPARMOR_LABEL).strip();
            if (!hasProfile(label, PROFILE)) return false;

            fixture = Files.createTempFile(ARTIFACT_ROOT, ".isolation-", ".probe");
            Files.writeString(fixture, "protected");
            var command = new ProcessBuilder("/bin/sh", "-c", PROBE_COMMAND, "artifact-isolation", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            command.environment().clear();
            child = command.start();
            return child.waitFor(3, TimeUnit.SECONDS) && child.exitValue() == 0
                && "protected".equals(Files.readString(fixture));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            log.debug("Could not verify artifact process isolation ({})", e.getClass().getSimpleName());
            return false;
        } finally {
            if (child != null && child.isAlive()) child.destroyForcibly();
            if (fixture != null) {
                try {
                    Files.deleteIfExists(fixture);
                } catch (IOException e) {
                    log.warn("Could not remove outbound isolation probe ({})", e.getClass().getSimpleName());
                }
            }
        }
    }

    private static boolean hasProfile(String label, String profile) {
        String enforced = " (enforce)";
        if (!label.endsWith(enforced)) return false;
        String stack = label.substring(0, label.length() - enforced.length());
        return java.util.Arrays.asList(stack.split("//&")).contains(profile);
    }
}

package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChromiumLauncher {

    private final AgentProperties properties;

    public Process launch(Path executable) throws IOException {
        AgentProperties.ChromiumProperties chromium = properties.getChromium();
        String userDataDir = chromium.getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) {
            userDataDir = Files.createTempDirectory("chromium-user-data-").toString();
        }

        List<String> args = new ArrayList<>();
        args.add(executable.toString());
        args.add("--remote-debugging-port=9222");
        if (chromium.isHeadless()) {
            args.add("--headless=new");
        }
        args.add("--no-sandbox");
        args.add("--disable-setuid-sandbox");
        args.add("--disable-dev-shm-usage");
        args.add("--disable-gpu");
        args.add("--disable-extensions");
        args.add("--disable-background-networking");
        args.add("--disable-sync");
        args.add("--no-first-run");
        args.add("--user-data-dir=" + userDataDir);
        args.addAll(chromium.getExtraArgs());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.inheritIO();
        pb.redirectErrorStream(true);
        log.info("Launching Chromium: {}", String.join(" ", args));
        Process process = pb.start();
        log.info("Chromium process started, pid={}", process.pid());
        return process;
    }

    public Path findExecutable(ChromiumPlatform.Platform platform, Path installDir) {
        AgentProperties.ChromiumProperties chromium = properties.getChromium();
        String configured = chromium.getExecutablePath();
        if (configured != null && !configured.isBlank()) {
            Path p = Paths.get(configured);
            if (Files.exists(p) && Files.isExecutable(p)) {
                return p;
            }
            log.warn("Configured Chromium executable not found or not executable: {}. Falling back to installDir/system.", configured);
        }

        Path inInstallDir = installDir.resolve(platform.archiveFolder()).resolve(platform.executableName());
        if (Files.exists(inInstallDir) && Files.isExecutable(inInstallDir)) {
            return inInstallDir;
        }

        return findSystemExecutable().orElse(null);
    }

    public java.util.Optional<Path> findSystemExecutable() {
        String[] candidates = {
            "chromium",
            "chromium-browser",
            "google-chrome",
            "google-chrome-stable",
            "chrome",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/bin/google-chrome",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium"
        };
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.exists(path) && Files.isExecutable(path)) {
                return java.util.Optional.of(path);
            }
            // Try which/where
            String found = findInPath(candidate);
            if (found != null) {
                Path p = Paths.get(found);
                if (Files.exists(p) && Files.isExecutable(p)) {
                    return java.util.Optional.of(p);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private String findInPath(String command) {
        try {
            String cmd = System.getProperty("os.name").toLowerCase().contains("win") ? "where" : "which";
            ProcessBuilder pb = new ProcessBuilder(cmd, command);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String result = reader.lines().collect(Collectors.joining("\n")).trim();
                if (p.waitFor(5, TimeUnit.SECONDS) && !result.isBlank()) {
                    return result.split("\n")[0].trim();
                }
            }
        } catch (Exception e) {
            log.debug("Could not locate {} in PATH: {}", command, e.getMessage());
        }
        return null;
    }

    public boolean waitForCdp(int timeoutSeconds) throws InterruptedException {
        return waitForCdp("127.0.0.1", 9222, timeoutSeconds);
    }

    boolean waitForCdp(String host, int port, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 500);
                log.info("CDP endpoint is ready on {}:{}", host, port);
                return true;
            } catch (Exception e) {
                Thread.sleep(500);
            }
        }
        log.error("CDP endpoint did not become ready within {} seconds", timeoutSeconds);
        return false;
    }
}

package com.azhukov.agent.tools.browser;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jakarta.annotation.PreDestroy;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChromiumLauncher {

    private final AgentProperties properties;

    // M23: Track the launched Chromium process so it can be killed on shutdown.
    private volatile Process launchedProcess;

    public Process launch(Path executable) throws IOException {
        AgentProperties.ChromiumProperties chromium = properties.getChromium();
        String userDataDir = chromium.getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) {
            userDataDir = Files.createTempDirectory("chromium-user-data-").toString();
        }
        Path userDataPath = Path.of(userDataDir);
        Files.createDirectories(userDataPath);
        makeWritable(userDataPath);
        configureCrashpad(executable);

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
        args.add("--disable-crash-reporter");
        args.add("--crash-dumps-dir=" + userDataPath.resolve("crash-dumps"));
        args.add("--user-data-dir=" + userDataDir);
        args.addAll(chromium.getExtraArgs());

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        // Chromium can exit before CDP is ready. Keep its startup log available
        // so the caller can fail fast with the actual launcher error.
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        log.info("Launching Chromium: {}", String.join(" ", args));
        Process process = pb.start();
        log.info("Chromium process started, pid={}", process.pid());
        // M23: Track the process for cleanup on shutdown
        this.launchedProcess = process;
        return process;
    }

    @PreDestroy
    void destroy() {
        // M23: Kill the launched Chromium process on bean destruction
        Process p = launchedProcess;
        if (p != null && p.isAlive()) {
            log.info("Destroying Chromium process (pid={}) on shutdown", p.pid());
            p.destroyForcibly();
        }
    }

    private void configureCrashpad(Path executable) throws IOException {
        Path handler = executable.getParent().resolve("chrome_crashpad_handler");
        Path original = executable.getParent().resolve("chrome_crashpad_handler.real");
        if (!Files.exists(handler) && !Files.exists(original)) {
            return;
        }
        if (!Files.exists(original)) {
            Files.move(handler, original);
        }
        Path database = Path.of(System.getProperty("user.home"), ".azhukov-agent", "crashpad");
        Files.createDirectories(database);
        makeWritable(database);
        String script = "#!/bin/sh\nexec \"$(dirname \"$0\")/chrome_crashpad_handler.real\" --database=\""
            + database + "\" \"$@\"\n";
        Files.writeString(handler, script, StandardCharsets.UTF_8);
        if (!handler.toFile().setExecutable(true, false)) {
            throw new IOException("Could not make Chromium crashpad wrapper executable: " + handler);
        }
    }

    private void makeWritable(Path directory) {
        File file = directory.toFile();
        if (!file.setReadable(true, false) || !file.setWritable(true, false) || !file.setExecutable(true, false)) {
            log.debug("Could not make Chromium profile world-accessible: {}", directory);
        }
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

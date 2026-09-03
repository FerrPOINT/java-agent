package com.azhukov.agent.tools.terminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ShellExecutableResolver {

    private ShellExecutableResolver() {
    }

    static String bash() {
        return bash(System.getenv(), System.getProperty("os.name", ""));
    }

    static String bash(Map<String, String> env, String osName) {
        boolean windows = isWindows(osName);
        if (!windows) {
            String found = findOnPath("bash", env, false);
            if (found != null) {
                return found;
            }
            for (String candidate : List.of("/usr/bin/bash", "/bin/bash")) {
                if (isUsableFile(candidate)) {
                    return candidate;
                }
            }
            String shell = envValue(env, "SHELL");
            return shell == null || shell.isBlank() ? "/bin/sh" : shell;
        }

        List<String> candidates = new ArrayList<>();
        addIfUsable(candidates, envValue(env, "HERMES_GIT_BASH_PATH"));

        String localAppData = envValue(env, "LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            addIfUsable(candidates, Path.of(localAppData, "hermes", "git", "bin", "bash.exe"));
            addIfUsable(candidates, Path.of(localAppData, "hermes", "git", "usr", "bin", "bash.exe"));
        }

        addIfUsable(candidates, Path.of(defaultEnv(env, "ProgramFiles", "C:\\Program Files"), "Git", "bin", "bash.exe"));
        addIfUsable(candidates, Path.of(defaultEnv(env, "ProgramFiles(x86)", "C:\\Program Files (x86)"), "Git", "bin", "bash.exe"));
        if (localAppData != null && !localAppData.isBlank()) {
            addIfUsable(candidates, Path.of(localAppData, "Programs", "Git", "bin", "bash.exe"));
        }

        String pathBash = findOnPath("bash", env, true);
        addIfUsable(candidates, pathBash);

        return candidates.isEmpty() ? "bash" : candidates.getFirst();
    }

    private static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    private static String defaultEnv(Map<String, String> env, String key, String defaultValue) {
        String value = envValue(env, key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String envValue(Map<String, String> env, String key) {
        if (env == null || key == null) {
            return null;
        }
        String exact = env.get(key);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String findOnPath(String executable, Map<String, String> env, boolean windows) {
        String path = envValue(env, "PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> names = windows ? List.of(executable + ".exe", executable) : List.of(executable);
        for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(entry, name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private static void addIfUsable(List<String> candidates, Path candidate) {
        if (candidate != null) {
            addIfUsable(candidates, candidate.toString());
        }
    }

    private static void addIfUsable(List<String> candidates, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        if (isUsableFile(candidate) && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static boolean isUsableFile(String candidate) {
        try {
            return Files.isRegularFile(Path.of(candidate));
        } catch (RuntimeException e) {
            return false;
        }
    }
}

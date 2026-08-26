package com.azhukov.agent.core.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Coding workspace snapshot — Hermes parity (agent/coding_context.py
 * {@code build_coding_workspace_block} + {@code detect_project_facts}).
 *
 * <p>Hands the model its <em>verify loop</em> up front — git state, manifests,
 * package managers, and the exact test/lint/build commands — instead of making
 * it rediscover them every session. Built once at prompt-build time; the output
 * must stay byte-stable to preserve the prompt cache.
 */
@Component
@Slf4j
public class CodingWorkspaceSnapshot {

    /** Hermes _PROJECT_MARKERS (coding_context.py:76-83). */
    private static final List<String> PROJECT_MARKERS = List.of(
        "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt",
        "package.json", "tsconfig.json", "deno.json",
        "Cargo.toml", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts",
        "Gemfile", "composer.json", "mix.exs", "pubspec.yaml",
        "CMakeLists.txt", "Makefile", "Dockerfile",
        "AGENTS.md", "CLAUDE.md", ".cursorrules");

    /** Hermes _CONTEXT_FILES — agent-instruction files surfaced separately. */
    private static final Set<String> CONTEXT_FILES = Set.of("AGENTS.md", "CLAUDE.md", ".cursorrules");

    /** Hermes _VERIFY_TARGETS — package.json scripts / Makefile targets worth surfacing. */
    private static final List<String> VERIFY_TARGETS = List.of(
        "test", "tests", "lint", "typecheck", "check", "build", "fmt", "format");

    /** Hermes _JS_LOCKFILES — lockfile → package manager. */
    private static final List<String[]> JS_LOCKFILES = List.of(
        new String[]{"pnpm-lock.yaml", "pnpm"}, new String[]{"bun.lockb", "bun"},
        new String[]{"bun.lock", "bun"}, new String[]{"yarn.lock", "yarn"},
        new String[]{"package-lock.json", "npm"});

    private static final int MAX_VERIFY_COMMANDS = 8;
    private static final int MAX_MANIFESTS = 6;
    private static final Duration GIT_TIMEOUT = Duration.ofMillis(2500);

    /**
     * Build the workspace snapshot block for the system prompt (empty outside
     * a workspace). Mirrors build_coding_workspace_block: git branch/status/
     * recent commits + project facts, "snapshot at session start" framing.
     */
    public String build(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return "";
        }
        Path resolved = Path.of(workingDir);
        if (!Files.isDirectory(resolved)) {
            return "";
        }
        Path gitRoot = gitRoot(resolved);
        Path root = gitRoot != null ? gitRoot : markerRoot(resolved);
        if (root == null) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Workspace (snapshot at session start — re-check with `git` before acting on it):");
        lines.add("- Root: " + root);

        if (gitRoot != null) {
            appendGitState(gitRoot, lines);
        }
        lines.addAll(projectFacts(root));
        return String.join("\n", lines);
    }

    /**
     * Return detected verify commands (test, build, lint) for the workspace.
     * Used by VerifyOnStopGuard to include specific commands in the nudge.
     */
    public java.util.List<String> getVerifyCommands() {
        String workingDir = System.getProperty("user.dir");
        if (workingDir == null || workingDir.isBlank()) {
            return java.util.List.of();
        }
        Path resolved = Path.of(workingDir);
        if (!Files.isDirectory(resolved)) {
            return java.util.List.of();
        }
        Path gitRoot = gitRoot(resolved);
        Path root = gitRoot != null ? gitRoot : markerRoot(resolved);
        if (root == null) {
            return java.util.List.of();
        }
        return detectVerifyCommands(root);
    }

    private java.util.List<String> detectVerifyCommands(Path root) {
        List<String> verify = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("scripts/run_tests.sh"))) {
            verify.add("scripts/run_tests.sh");
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            verify.add("./gradlew test");
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            verify.add("mvn test");
        }
        Path pkg = root.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            String pm = jsPackageManager(root);
            String scripts = readSmall(pkg);
            if (scripts != null) {
                for (String t : VERIFY_TARGETS) {
                    if (Pattern.compile("\"" + t + "\"\\s*:").matcher(scripts).find()) {
                        verify.add(pm + " run " + t);
                    }
                }
            }
        }
        boolean pytestIni = Files.isRegularFile(root.resolve("pytest.ini"));
        String pyproject = readSmall(root.resolve("pyproject.toml"));
        if (pytestIni || (pyproject != null && pyproject.contains("[tool.pytest"))) {
            verify.add("pytest");
        }
        String makefile = readSmall(root.resolve("Makefile"));
        if (makefile != null) {
            for (String t : VERIFY_TARGETS) {
                if (Pattern.compile("^" + Pattern.quote(t) + "\\s*:", Pattern.MULTILINE).matcher(makefile).find()) {
                    verify.add("make " + t);
                }
            }
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(verify);
        return new ArrayList<>(unique).subList(0, Math.min(unique.size(), MAX_VERIFY_COMMANDS));
    }

    private void appendGitState(Path root, List<String> lines) {
        try {
            String statusOut = git(root, "status", "--porcelain=2", "--branch");
            String head = parseHead(statusOut);
            if (head != null && !head.isEmpty()) {
                if ("(detached)".equals(head)) {
                    lines.add("- Branch: (detached HEAD)");
                } else {
                    lines.add("- Branch: " + head);
                }
            }
            int staged = countPrefix(statusOut, "1 ");
            int modified = countPrefix(statusOut, "2 ");
            int untracked = countPrefix(statusOut, "? ");
            int conflicts = countPrefix(statusOut, "u ");
            List<String> dirty = new ArrayList<>();
            if (staged > 0) dirty.add(staged + " staged");
            if (modified > 0) dirty.add(modified + " modified");
            if (untracked > 0) dirty.add(untracked + " untracked");
            if (conflicts > 0) dirty.add(conflicts + " conflicts");
            lines.add("- Status: " + (dirty.isEmpty() ? "clean" : String.join(", ", dirty)));

            String recent = git(root, "log", "-3", "--pretty=%h %s");
            if (recent != null && !recent.isBlank()) {
                lines.add("- Recent commits:");
                for (String c : recent.split("\n")) {
                    if (!c.isBlank()) lines.add("    " + c.trim());
                }
            }
        } catch (Exception e) {
            log.debug("git state unavailable for {}: {}", root, e.getMessage());
        }
    }

    /** Mirrors _project_facts: manifests, package managers, verify commands, context files. */
    private List<String> projectFacts(Path root) {
        List<String> facts = new ArrayList<>();
        List<String> manifests = new ArrayList<>();
        List<String> contextFiles = new ArrayList<>();
        for (String m : PROJECT_MARKERS) {
            if (Files.isRegularFile(root.resolve(m))) {
                if (CONTEXT_FILES.contains(m)) {
                    contextFiles.add(m);
                } else {
                    manifests.add(m);
                }
            }
        }

        List<String> verify = new ArrayList<>();
        if (Files.isRegularFile(root.resolve("scripts/run_tests.sh"))) {
            verify.add("scripts/run_tests.sh");
        }
        // Gradle/Maven verify commands
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            verify.add("./gradlew test");
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            verify.add("mvn test");
        }
        // JS verify targets from package.json scripts
        Path pkg = root.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            String pm = jsPackageManager(root);
            String scripts = readSmall(pkg);
            if (scripts != null) {
                for (String t : VERIFY_TARGETS) {
                    if (Pattern.compile("\"" + t + "\"\\s*:").matcher(scripts).find()) {
                        verify.add(pm + " run " + t);
                    }
                }
            }
        }
        // pytest
        boolean pytestIni = Files.isRegularFile(root.resolve("pytest.ini"));
        String pyproject = readSmall(root.resolve("pyproject.toml"));
        if (pytestIni || (pyproject != null && pyproject.contains("[tool.pytest"))) {
            verify.add("pytest");
        }
        // Makefile targets
        String makefile = readSmall(root.resolve("Makefile"));
        if (makefile != null) {
            for (String t : VERIFY_TARGETS) {
                if (Pattern.compile("^" + Pattern.quote(t) + "\\s*:", Pattern.MULTILINE).matcher(makefile).find()) {
                    verify.add("make " + t);
                }
            }
        }

        // Dedup + cap (Hermes dict.fromkeys + [:_MAX_VERIFY_COMMANDS])
        LinkedHashSet<String> uniqueVerify = new LinkedHashSet<>(verify);
        verify = new ArrayList<>(uniqueVerify).subList(0, Math.min(uniqueVerify.size(), MAX_VERIFY_COMMANDS));

        if (!manifests.isEmpty()) {
            List<String> shown = manifests.subList(0, Math.min(manifests.size(), MAX_MANIFESTS));
            List<String> pms = packageManagers(root);
            String line = "- Project: " + String.join(", ", shown);
            if (!pms.isEmpty()) {
                line += " (" + String.join("/", pms) + ")";
            }
            facts.add(line);
        }
        if (!verify.isEmpty()) {
            facts.add("- Verify: " + String.join("; ", verify));
        }
        if (!contextFiles.isEmpty()) {
            facts.add("- Context files: " + String.join(", ", contextFiles));
        }
        return facts;
    }

    private List<String> packageManagers(Path root) {
        List<String> pms = new ArrayList<>();
        String jsPm = jsPackageManager(root);
        if (Files.isRegularFile(root.resolve("package.json")) && jsPm != null) {
            pms.add(jsPm);
        }
        if (Files.isRegularFile(root.resolve("build.gradle")) || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
            pms.add("gradle");
        }
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            pms.add("maven");
        }
        if (Files.isRegularFile(root.resolve("Cargo.toml"))) pms.add("cargo");
        if (Files.isRegularFile(root.resolve("go.mod"))) pms.add("go");
        if (Files.isRegularFile(root.resolve("pyproject.toml"))
            || Files.isRegularFile(root.resolve("requirements.txt"))) pms.add("pip");
        return pms;
    }

    private String jsPackageManager(Path root) {
        for (String[] lk : JS_LOCKFILES) {
            if (Files.isRegularFile(root.resolve(lk[0]))) {
                return lk[1];
            }
        }
        return "npm";
    }

    // ── helpers ──

    private Path gitRoot(Path dir) {
        Path d = dir.toAbsolutePath();
        while (d != null) {
            if (Files.isDirectory(d.resolve(".git"))) {
                return d;
            }
            d = d.getParent();
        }
        return null;
    }

    private Path markerRoot(Path dir) {
        Path d = dir.toAbsolutePath();
        while (d != null) {
            for (String m : PROJECT_MARKERS) {
                if (Files.isRegularFile(d.resolve(m))) {
                    return d;
                }
            }
            d = d.getParent();
        }
        return null;
    }

    private String git(Path root, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(root.toFile())
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectErrorStream(true); // H10: merge stderr into stdout to avoid pipe-buffer deadlock
            Process p = pb.start();
            if (!p.waitFor(GIT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            byte[] out = p.getInputStream().readAllBytes();
            return new String(out).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private String parseHead(String porcelainBranch) {
        if (porcelainBranch == null) return null;
        for (String line : porcelainBranch.split("\n")) {
            if (line.startsWith("# branch.head ")) {
                return line.substring("# branch.head ".length()).trim();
            }
        }
        return null;
    }

    private int countPrefix(String status, String prefix) {
        int n = 0;
        for (String line : status.split("\n")) {
            if (line.startsWith(prefix)) n++;
        }
        return n;
    }

    private String readSmall(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > 256 * 1024) {
                return null;
            }
            return Files.readString(p);
        } catch (IOException e) {
            return null;
        }
    }
}

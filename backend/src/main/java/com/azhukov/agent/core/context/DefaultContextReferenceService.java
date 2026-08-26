package com.azhukov.agent.core.context;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.ContextReference;
import com.azhukov.agent.core.model.ReferenceType;
import com.azhukov.agent.core.skill.SkillManager;
import com.azhukov.agent.tools.terminal.CommandGuard;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Context reference service with support for @diff, @staged, @git, @folder
 * reference types and sensitive path blocking.
 * <p>
 * Mirrors the original project's agent/context_references.py.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultContextReferenceService implements ContextReferenceService {

 private static final int CHARS_PER_TOKEN = 4;

 // Sensitive directories that should be blocked from file/folder references
 private static final Set<String> SENSITIVE_HOME_DIRS = Set.of(
 ".ssh", ".aws", ".env", ".gnupg", ".kube", ".docker", ".azure", ".config/gh"
 );

 // Sensitive files that should be blocked
 private static final Set<String> SENSITIVE_HOME_FILES = Set.of(
 ".ssh/authorized_keys", ".ssh/id_rsa", ".ssh/id_ed25519", ".ssh/config",
 ".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile",
 ".netrc", ".pgpass", ".npmrc", ".pypirc", ".env"
 );

 private final AgentProperties properties;
 private final SkillManager skillManager;
 @Getter
 private HttpClient httpClient;
 private CommandGuard commandGuard;

 @PostConstruct
 public void initHttpClient() {
     this.httpClient = HttpClient.newBuilder()
         .connectTimeout(Duration.ofSeconds(properties.getCore().getHttpClientTimeoutSeconds()))
         .build();
     this.commandGuard = new CommandGuard(
         properties.getSecurity().getBlockedCommands(),
         properties.getTerminal().isBlockSudo()
     );
 }

 private int getMaxReferenceTokens() {
 int configured = properties.getContext().getMaxReferenceTokens();
 if (configured > 0) {
 return configured;
 }
 return Math.max(1, properties.getContext().getMaxTokens() / 4);
 }

 private int estimateTokens(String text) {
 if (text == null || text.isEmpty()) {
 return 0;
 }
 return text.length() / CHARS_PER_TOKEN + 1;
 }

 @Override
 public List<ContextReference> resolve(List<String> refs) {
 List<ContextReference> result = new ArrayList<>();
 if (refs == null) {
 return result;
 }
 for (String ref : refs) {
 if (ref == null || ref.isBlank()) {
 continue;
 }
 result.add(classify(ref.trim()));
 }
 return result;
 }

 @Override
 public Optional<String> loadContent(ContextReference reference) {
 if (!reference.success()) {
 return Optional.of("[failed to load reference: " + reference.error() + "]");
 }
 return switch (reference.type()) {
 case FILE -> loadFile(reference.source());
 case URL -> loadUrl(reference.source());
 case SKILL -> loadSkill(reference.source());
 case DIFF -> loadGitDiff(reference.source());
 case STAGED -> loadGitStaged(reference.source());
 case GIT -> loadGitLog(reference.source());
 case FOLDER -> loadFolder(reference.source());
 case UNKNOWN -> Optional.of("[unknown reference type: " + reference.source() + "]");
 };
 }

 public Optional<String> loadContentWithBudget(List<ContextReference> refs) {
 if (refs == null || refs.isEmpty()) {
 return Optional.empty();
 }
 int maxTokens = properties.getContext().getMaxTokens();
 int maxRefTokens = getMaxReferenceTokens();
 int warnThreshold = maxRefTokens;
 int refuseThreshold = maxRefTokens * 2;

 StringBuilder sb = new StringBuilder();
 int totalTokens = 0;

 for (ContextReference ref : refs) {
 Optional<String> content = loadContent(ref);
 if (content.isPresent()) {
 String text = content.get();
 int tokens = estimateTokens(text);
 totalTokens += tokens;
 sb.append("[").append(ref.displayName()).append("]\n").append(text).append("\n\n");
 }
 }

 if (totalTokens > refuseThreshold) {
 log.warn("Reference content exceeds budget: {} tokens > {} max", totalTokens, refuseThreshold);
 return Optional.of("[Reference content exceeds token budget: " + totalTokens
 + " tokens > " + refuseThreshold + " max. References were not injected.]");
 }

 if (totalTokens > warnThreshold) {
 log.warn("Reference content approaching budget: {} tokens > {} max", totalTokens, warnThreshold);
 }

 return Optional.of(sb.toString().trim());
 }

 private ContextReference classify(String ref) {
 if (ref.startsWith("http://") || ref.startsWith("https://")) {
 return new ContextReference(ReferenceType.URL, ref, ref, null);
 }
 if (ref.startsWith("skill://")) {
 return new ContextReference(ReferenceType.SKILL, ref.substring(8), ref, null);
 }
 if (ref.startsWith("file://")) {
 String path = ref.substring(7);
 return new ContextReference(ReferenceType.FILE, path, Paths.get(path).getFileName().toString(), null);
 }
 // @diff, @staged, @git, @folder reference types
 if (ref.startsWith("@diff") || ref.equals("@diff")) {
 return new ContextReference(ReferenceType.DIFF, "", "@diff", null);
 }
 if (ref.startsWith("@staged") || ref.equals("@staged")) {
 return new ContextReference(ReferenceType.STAGED, "", "@staged", null);
 }
 if (ref.startsWith("@git:") || ref.equals("@git")) {
 String count = ref.startsWith("@git:") ? ref.substring(5) : "1";
 return new ContextReference(ReferenceType.GIT, count, "@git", null);
 }
 if (ref.startsWith("@folder:") || ref.startsWith("folder://")) {
 String path = ref.startsWith("@folder:") ? ref.substring(8) : ref.substring(9);
 return new ContextReference(ReferenceType.FOLDER, path, path, null);
 }
 if (ref.startsWith("@")) {
 // Try as file reference with @ prefix
 String path = ref.substring(1);
 Path candidate = Paths.get(path);
 if (Files.exists(candidate)) {
 return new ContextReference(ReferenceType.FILE, path, candidate.getFileName().toString(), null);
 }
 }
 Path candidate = Paths.get(ref);
 if (Files.exists(candidate)) {
 return new ContextReference(ReferenceType.FILE, ref, candidate.getFileName().toString(), null);
 }
 return new ContextReference(ReferenceType.UNKNOWN, ref, ref, "unrecognized reference");
 }

 /**
 * Check if a path is sensitive and should be blocked.
 * Blocks .ssh, .aws, .env, .gnupg, .kube and other sensitive directories/files.
 */
 private boolean isSensitivePath(Path path) {
 String pathStr = path.toString();
 Path home = Paths.get(System.getProperty("user.home"));

 // Check against sensitive files
 for (String sensitiveFile : SENSITIVE_HOME_FILES) {
 Path sensitivePath = home.resolve(sensitiveFile);
 if (path.equals(sensitivePath)) {
 return true;
 }
 }

 // Check against sensitive directories
 for (String sensitiveDir : SENSITIVE_HOME_DIRS) {
 Path sensitivePath = home.resolve(sensitiveDir);
 if (path.startsWith(sensitivePath)) {
 return true;
 }
 }

 // Check for .env files anywhere
 if (path.getFileName() != null && path.getFileName().toString().equals(".env")) {
 return true;
 }

 return false;
 }

 private Optional<String> loadFile(String source) {
 try {
 Path path = Paths.get(source).toAbsolutePath().normalize();
 // Sensitive path blocking (check BEFORE base directory, so credentials
 // outside the working directory are still blocked with the right message)
 if (isSensitivePath(path)) {
 log.warn("Blocked sensitive path reference: {}", source);
 return Optional.of("[file access denied — sensitive credential path: " + source + "]");
 }
 Path base = Paths.get(properties.getCore().getWorkingDirectory()).toAbsolutePath().normalize();
 if (!path.startsWith(base)) {
 return Optional.of("[file access denied: " + source + "]");
 }
 if (!Files.exists(path)) {
 return Optional.of("[file not found: " + source + "]");
 }
 long maxBytes = properties.getCore().getMaxReferenceFileBytes();
 if (Files.size(path) > maxBytes) {
 return Optional.of("[file too large: " + source + "]");
 }
 return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
 } catch (IOException e) {
 log.warn("Failed to read referenced file {}", source, e);
 return Optional.of("[file read error: " + source + "]");
 }
 }

 private Optional<String> loadUrl(String source) {
 try {
 HttpRequest request = HttpRequest.newBuilder(URI.create(source))
 .GET()
 .timeout(Duration.ofSeconds(properties.getCore().getHttpClientTimeoutSeconds()))
 .header("User-Agent", properties.getCore().getHttpUserAgent())
 .build();
 HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
 if (response.statusCode() >= 200 && response.statusCode() < 300) {
 return Optional.of(response.body());
 }
 return Optional.of("[url returned " + response.statusCode() + ": " + source + "]");
 } catch (Exception e) {
 log.warn("Failed to fetch referenced url {}", source, e);
 return Optional.of("[url fetch error: " + source + "]");
 }
 }

 private Optional<String> loadSkill(String source) {
 try {
 String skill = skillManager.getSkill(source);
 if (skill == null) {
 return Optional.of("[skill not found: " + source + "]");
 }
 return Optional.of("[skill " + source + "]\n" + skill);
 } catch (Exception e) {
 log.warn("Failed to load referenced skill {}", source, e);
 return Optional.of("[skill load error: " + source + "]");
 }
 }

 /**
 * Load git diff output (unstaged changes).
 */
 private Optional<String> loadGitDiff(String source) {
 return runGitCommand("git diff", "diff");
 }

 /**
 * Load git staged changes.
 */
 private Optional<String> loadGitStaged(String source) {
 return runGitCommand("git diff --staged", "diff --staged");
 }

 /**
 * Load git log with patch.
 */
 private Optional<String> loadGitLog(String source) {
 int count = 1;
 try {
 count = Math.max(1, Math.min(Integer.parseInt(source), 10));
 } catch (NumberFormatException e) {
     log.debug("Reference line number parsing failed: {}", e.getMessage());
 }
 return runGitCommand("git log -" + count + " -p", "git log -" + count + " -p");
 }

 private Optional<String> runGitCommand(String command, String label) {
     try {
         // Validate command against CommandGuard before execution
         String blockReason = commandGuard.check(command);
         if (blockReason != null) {
             log.warn("Blocked git command by CommandGuard: {} — {}", command, blockReason);
             return Optional.of("[" + label + ": blocked by CommandGuard: " + blockReason + "]");
         }
         String workingDir = properties.getCore().getWorkingDirectory();
 ProcessBuilder pb = new ProcessBuilder(command.split(" "));
 pb.directory(workingDir != null ? new java.io.File(workingDir) : new java.io.File("."));
 pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
 Process process = pb.start();
 // H10: Read stdout and stderr concurrently via gobbler threads to avoid
 // pipe-buffer deadlock when the child fills one pipe while we block on the other.
 java.io.ByteArrayOutputStream stdoutBuf = new java.io.ByteArrayOutputStream();
 java.io.ByteArrayOutputStream stderrBuf = new java.io.ByteArrayOutputStream();
 Thread stdoutGobbler = new Thread(() -> {
     try { process.getInputStream().transferTo(stdoutBuf); } catch (java.io.IOException ignored) { }
 }, "ctxref-stdout-gobbler");
 Thread stderrGobbler = new Thread(() -> {
     try { process.getErrorStream().transferTo(stderrBuf); } catch (java.io.IOException ignored) { }
 }, "ctxref-stderr-gobbler");
 stdoutGobbler.setDaemon(true);
 stderrGobbler.setDaemon(true);
 stdoutGobbler.start();
 stderrGobbler.start();
 boolean done = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
 if (!done) {
 process.destroyForcibly();
 stdoutGobbler.interrupt();
 stderrGobbler.interrupt();
 return Optional.of("[" + label + ": timed out (30s)]");
 }
 stdoutGobbler.join(5000);
 stderrGobbler.join(5000);
 String output = stdoutBuf.toString(StandardCharsets.UTF_8);
 String error = stderrBuf.toString(StandardCharsets.UTF_8);
 if (process.exitValue() != 0) {
 String err = error.strip();
 if (err.isEmpty()) err = "git command failed";
 return Optional.of("[" + label + ": " + err + "]");
 }
 String content = output.strip();
 if (content.isEmpty()) {
 content = "(no output)";
 }
 return Optional.of("```diff\n" + content + "\n```");
 } catch (Exception e) {
 log.warn("Failed to run git command: {}", command, e);
 return Optional.of("[" + label + ": " + e.getMessage() + "]");
 }
 }

 /**
 * Load folder listing.
 */
 private Optional<String> loadFolder(String source) {
 try {
 Path path = Paths.get(source);
 if (!path.isAbsolute()) {
 String workingDir = properties.getCore().getWorkingDirectory();
 path = Paths.get(workingDir != null ? workingDir : ".").resolve(path).toAbsolutePath().normalize();
 }
 final Path basePath = path;
 // Sensitive path blocking
 if (isSensitivePath(path)) {
 log.warn("Blocked sensitive folder reference: {}", source);
 return Optional.of("[folder access denied — sensitive credential path: " + source + "]");
 }
 if (!Files.exists(path) || !Files.isDirectory(path)) {
 return Optional.of("[folder not found: " + source + "]");
 }

 StringBuilder listing = new StringBuilder();
 listing.append(path.getFileName()).append("/\n");
 try (var stream = Files.walk(path, 3)) {
 int limit = 200;
 var items = stream
 .filter(p -> !p.equals(basePath))
 .filter(p -> {
 String name = p.getFileName().toString();
 return !name.startsWith(".") && !name.equals("__pycache__");
 })
 .limit(limit)
 .sorted()
 .toList();
 for (Path item : items) {
 int depth = path.relativize(item).getNameCount();
 String indent = " ".repeat(Math.max(depth - 1, 0));
 if (Files.isDirectory(item)) {
 listing.append(indent).append("- ").append(item.getFileName()).append("/\n");
 } else {
 listing.append(indent).append("- ").append(item.getFileName()).append("\n");
 }
 }
 }
 return Optional.of(listing.toString().trim());
 } catch (IOException e) {
 log.warn("Failed to list folder {}", source, e);
 return Optional.of("[folder read error: " + source + "]");
 }
 }
}
package com.azhukov.agent.tools.terminal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Shell-script hooks bridge — ported from the original project's {@code shell_hooks.py}.
 *
 * <p>Reads the {@code hooks:} block from configuration, prompts the user for
 * consent on first use of each {@code (event, command)} pair, and registers
 * callbacks so every existing pre/post tool call site dispatches to the
 * configured shell scripts — with zero changes to call sites.
 *
 * <h2>Design notes</h2>
 * <ul>
 * <li>Subprocess execution uses {@code ProcessBuilder} with no shell —
 * no shell injection footguns. Users that need pipes/redirection wrap
 * their logic in a script.</li>
 * <li>First-use consent is gated by an allowlist file at
 * {@code ~/.hermes/shell-hooks-allowlist.json}. Non-interactive callers
 * must pass {@code acceptHooks=true} for registration to succeed
 * without a prompt.</li>
 * <li>Registration is idempotent — safe to invoke from multiple entry points.</li>
 * </ul>
 *
 * <h2>Wire protocol</h2>
 * <p><b>stdin</b> (JSON, piped to the script):
 * <pre>{@code
 * {
 * "hook_event_name": "pre_tool_call",
 * "tool_name": "terminal",
 * "tool_input": {"command": "rm -rf /"},
 * "session_id": "sess_abc123",
 * "cwd": "/home/user/project",
 * "extra": {...}
 * }
 * }</pre>
 *
 * <p><b>stdout</b> (JSON, optional — anything else is ignored):
 * <pre>{@code
 * // Block a pre_tool_call (either shape accepted; normalised internally):
 * {"decision": "block", "reason": "Forbidden command"} // Claude-Code-style
 * {"action": "block", "message": "Forbidden command"} // canonical
 *
 * // Inject context for pre_llm_call:
 * {"context": "Today is Friday"}
 *
 * // Silent no-op:
 * <empty or any non-matching JSON object>
 * }</pre>
 */
@Slf4j
public class ShellHookManager {

 private static final int DEFAULT_TIMEOUT_SECONDS = 60;
 private static final int MAX_TIMEOUT_SECONDS = 300;
 private static final String ALLOWLIST_FILENAME = "shell-hooks-allowlist.json";
 private static final String DEFAULT_BLOCK_MESSAGE = "Blocked by shell hook.";

 private static final ObjectMapper MAPPER = new ObjectMapper();

 // ─── Hook specification ───

 /**
 * Parsed and validated representation of a single {@code hooks:} entry.
 */
 public static class ShellHookSpec {
 private final String event;
 private final String command;
 private final String matcher;
 private final int timeout;
 private final Pattern compiledMatcher;

 public ShellHookSpec(String event, String command, String matcher, int timeout) {
 this.event = event;
 this.command = command != null ? command.strip() : command;
 // Strip whitespace from matcher — YAML quirks can introduce padding
 String strippedMatcher = matcher != null ? matcher.strip() : null;
 this.matcher = strippedMatcher != null && !strippedMatcher.isEmpty() ? strippedMatcher : null;
 this.timeout = Math.max(1, Math.min(timeout, MAX_TIMEOUT_SECONDS));
 Pattern compiled = null;
 if (this.matcher != null) {
 try {
 compiled = Pattern.compile(this.matcher);
 } catch (Exception e) {
 log.warn("Shell hook matcher '{}' is invalid ({}); treating as literal equality",
 this.matcher, e.getMessage());
 }
 }
 this.compiledMatcher = compiled;
 }

 public String event() { return event; }
 public String command() { return command; }
 public String matcher() { return matcher; }
 public int timeout() { return timeout; }

 public boolean matchesTool(String toolName) {
 if (matcher == null) return true;
 if (toolName == null) return false;
 if (compiledMatcher != null) {
 return compiledMatcher.matcher(toolName).matches();
 }
 // compiledMatcher is null when regex failed to compile → literal equality
 return toolName.equals(matcher);
 }
 }

 // ─── Hook response (normalised from script stdout) ───

 /**
 * Normalised response from a shell hook invocation.
 */
 public record HookResponse(
 boolean blocked,
 String message,
 String context // non-null only for pre_llm_call context injection
 ) {
 /**
 * A no-op response (no block, no context).
 */
 public static HookResponse noop() {
 return new HookResponse(false, null, null);
 }
 }

 // ─── Valid hook events ───

 private static final Set<String> VALID_HOOKS = Set.of(
 "pre_tool_call", "post_tool_call", "pre_llm_call", "post_llm_call",
 "on_session_start", "on_session_end", "on_compression", "on_error"
 );

 // Registered (event, matcher, command) triples for idempotence
 private final Set<String> registered = ConcurrentHashMap.newKeySet();

 // Callbacks per event
 private final ConcurrentHashMap<String, List<ShellHookSpec>> hooks = new ConcurrentHashMap<>();

 private final String allowlistPath;
 private final boolean acceptHooks;

 /**
 * Create a new ShellHookManager.
 *
 * @param hermesHome the project home directory path (for the allowlist file)
 * @param acceptHooks whether to auto-approve hooks without a TTY prompt
 */
 public ShellHookManager(String hermesHome, boolean acceptHooks) {
 this.allowlistPath = hermesHome != null
 ? Paths.get(hermesHome, ALLOWLIST_FILENAME).toString()
 : Paths.get(System.getProperty("user.home", "/root"), ".hermes", ALLOWLIST_FILENAME).toString();
 this.acceptHooks = acceptHooks;
 }

 /**
 * Create a ShellHookManager with auto-accept disabled.
 *
 * @param hermesHome the project home directory path (for the allowlist file)
 */
 public ShellHookManager(String hermesHome) {
 this(hermesHome, false);
 }

 /**
 * Register all hooks from a configuration map.
 *
 * <p>The expected config shape is a map of event names to lists of hook
 * definitions, each with a {@code command} field and optional {@code matcher}
 * and {@code timeout} fields.
 *
 * <pre>{@code
 * {
 * "pre_tool_call": [
 * {"command": "/path/to/hook.sh", "matcher": "terminal", "timeout": 30}
 * ],
 * "post_tool_call": [
 * {"command": "/path/to/post_hook.sh"}
 * ]
 * }
 * }</pre>
 *
 * @param hooksConfig the hooks configuration map; may be null or empty
 * @return the list of specs that were registered
 */
 public List<ShellHookSpec> registerFromConfig(Object hooksConfig) {
 if (hooksConfig == null) {
 return List.of();
 }

 @SuppressWarnings("unchecked")
 var configMap = (java.util.Map<String, Object>) hooksConfig;
 if (configMap.isEmpty()) {
 return List.of();
 }

 List<ShellHookSpec> registeredSpecs = new ArrayList<>();

 for (var entry : configMap.entrySet()) {
 String eventName = entry.getKey();
 if (!VALID_HOOKS.contains(eventName)) {
 log.warn("Unknown hook event '{}' in hooks config (valid: {})", eventName, VALID_HOOKS);
 continue;
 }

 Object entries = entry.getValue();
 if (entries == null) continue;

 if (!(entries instanceof List<?> list)) {
 log.warn("hooks.{} must be a list of hook definitions; got {}", eventName, entries.getClass().getSimpleName());
 continue;
 }

 for (int i = 0; i < list.size(); i++) {
 Object raw = list.get(i);
 ShellHookSpec spec = parseSingleEntry(eventName, i, raw);
 if (spec == null) continue;

 String key = spec.event() + ":" + spec.matcher() + ":" + spec.command();
 if (registered.contains(key)) continue;

 // Check allowlist / consent
 if (!isAllowlisted(spec.event(), spec.command())) {
 if (!promptAndRecord(spec.event(), spec.command())) {
 log.warn("Shell hook for {} ({}) not allowlisted — skipped. " +
 "Use acceptHooks=true or approve at the TTY prompt next run.",
 spec.event(), spec.command());
 continue;
 }
 }

 registered.add(key);
 hooks.computeIfAbsent(spec.event(), k -> Collections.synchronizedList(new ArrayList<>())).add(spec);
 registeredSpecs.add(spec);
 log.info("Shell hook registered: {} -> {} (matcher={}, timeout={}s)",
 spec.event(), spec.command(), spec.matcher(), spec.timeout());
 }
 }

 return registeredSpecs;
 }

 /**
 * Invoke all registered {@code pre_tool_call} hooks for the given tool.
 *
 * <p>If any hook returns a block decision, the first block response is
 * returned immediately (subsequent hooks are not invoked).
 *
 * @param toolName the name of the tool about to be called
 * @param toolInput the tool input (e.g. the command string for terminal)
 * @return the first block response, or a no-op if no hooks block
 */
 public HookResponse invokePreToolCall(String toolName, String toolInput) {
 List<ShellHookSpec> specs = hooks.get("pre_tool_call");
 if (specs == null || specs.isEmpty()) {
 return HookResponse.noop();
 }

 ObjectNode payload = buildPayload("pre_tool_call", toolName, toolInput);

 for (ShellHookSpec spec : specs) {
 if (!spec.matchesTool(toolName)) continue;

 SpawnResult result = spawn(spec, payload);
 if (result.error != null) {
 log.warn("Shell hook failed (event=pre_tool_call command={}): {}", spec.command(), result.error);
 continue;
 }
 if (result.timedOut) {
 log.warn("Shell hook timed out after {}s (event=pre_tool_call command={})",
 spec.timeout(), spec.command());
 continue;
 }
 if (result.exitCode != 0) {
 log.warn("Shell hook exited {} (event=pre_tool_call command={}); stderr={}",
 result.exitCode, spec.command(), truncate(result.stderr, 400));
 }

 HookResponse response = parseResponse("pre_tool_call", result.stdout);
 if (response.blocked()) {
 return response;
 }
 }

 return HookResponse.noop();
 }

 /**
 * Invoke all registered {@code post_tool_call} hooks for the given tool.
 *
 * @param toolName the name of the tool that was called
 * @param toolInput the tool input (e.g. the command string for terminal)
 * @param exitCode the exit code of the command
 * @param stdout the stdout output (may be truncated)
 */
 public void invokePostToolCall(String toolName, String toolInput, int exitCode, String stdout) {
 List<ShellHookSpec> specs = hooks.get("post_tool_call");
 if (specs == null || specs.isEmpty()) {
 return;
 }

 ObjectNode payload = buildPayload("post_tool_call", toolName, toolInput);
 payload.put("exit_code", exitCode);
 if (stdout != null) {
 payload.put("stdout", truncate(stdout, 4000));
 }

 for (ShellHookSpec spec : specs) {
 if (!spec.matchesTool(toolName)) continue;

 SpawnResult result = spawn(spec, payload);
 if (result.error != null) {
 log.warn("Shell hook failed (event=post_tool_call command={}): {}", spec.command(), result.error);
 } else if (result.timedOut) {
 log.warn("Shell hook timed out after {}s (event=post_tool_call command={})",
 spec.timeout(), spec.command());
 } else if (result.exitCode != 0) {
 log.debug("Shell hook exited {} (event=post_tool_call command={})",
 result.exitCode, spec.command());
 }
 }
 }

 /**
 * Return the list of all registered hook specs (for introspection / doctor).
 *
 * @return unmodifiable list of all registered specs
 */
 public List<ShellHookSpec> getRegisteredHooks() {
 return hooks.values().stream()
 .flatMap(List::stream)
 .toList();
 }

 /**
 * Return the list of registered hook specs for a specific event.
 *
 * @param event the hook event name
 * @return unmodifiable list of specs for that event
 */
 public List<ShellHookSpec> getHooksForEvent(String event) {
 return hooks.getOrDefault(event, List.of());
 }

 /**
 * Clear all registered hooks (for testing).
 */
 public void reset() {
 registered.clear();
 hooks.clear();
 }

 // ─── Internal: config parsing ───

 @SuppressWarnings("unchecked")
 private ShellHookSpec parseSingleEntry(String event, int index, Object raw) {
 if (!(raw instanceof java.util.Map<?, ?> map)) {
 log.warn("hooks.{}[{}] must be a mapping with a 'command' key; got {}",
 event, index, raw == null ? "null" : raw.getClass().getSimpleName());
 return null;
 }

 Object commandObj = map.get("command");
 if (!(commandObj instanceof String command) || command.isBlank()) {
 log.warn("hooks.{}[{}] is missing a non-empty 'command' field", event, index);
 return null;
 }

 Object matcherObj = map.get("matcher");
 String matcher = null;
 if (matcherObj instanceof String m && !m.isBlank()) {
 matcher = m;
 }

 // Matcher only honored for pre_tool_call / post_tool_call
 if (matcher != null && !event.equals("pre_tool_call") && !event.equals("post_tool_call")) {
 log.warn("hooks.{}[{}].matcher='{}' will be ignored — matcher is only for pre/post_tool_call",
 event, index, matcher);
 matcher = null;
 }

 int timeout = DEFAULT_TIMEOUT_SECONDS;
 Object timeoutObj = map.get("timeout");
 if (timeoutObj instanceof Number n) {
 timeout = n.intValue();
 } else if (timeoutObj instanceof String s) {
 try { timeout = Integer.parseInt(s); }
 catch (NumberFormatException e) {
 log.warn("hooks.{}[{}].timeout must be an int (got '{}'); using default {}s",
 event, index, s, DEFAULT_TIMEOUT_SECONDS);
 }
 }
 if (timeout < 1) {
 log.warn("hooks.{}[{}].timeout must be >=1; using default {}s", event, index, DEFAULT_TIMEOUT_SECONDS);
 timeout = DEFAULT_TIMEOUT_SECONDS;
 }
 if (timeout > MAX_TIMEOUT_SECONDS) {
 log.warn("hooks.{}[{}].timeout={}s exceeds max {}s; clamping", event, index, timeout, MAX_TIMEOUT_SECONDS);
 timeout = MAX_TIMEOUT_SECONDS;
 }

 return new ShellHookSpec(event, command, matcher, timeout);
 }

 // ─── Internal: subprocess execution ───

 private record SpawnResult(
 Integer exitCode,
 String stdout,
 String stderr,
 boolean timedOut,
 String error
 ) {}

 private SpawnResult spawn(ShellHookSpec spec, ObjectNode payload) {
 // Parse the command into argv — no shell, to avoid injection
 String[] argv;
  try {
  argv = parseShellArgs(spec.command());
  } catch (Exception e) {
  return new SpawnResult(null, "", "", false, "command '" + spec.command() + "' cannot be parsed: " + e.getMessage());
  }
  if (argv.length == 0) {
  return new SpawnResult(null, "", "", false, "empty command");
  }
  argv = normalizeShellScriptArgv(argv);

 String stdinJson;
 try {
 stdinJson = MAPPER.writeValueAsString(payload);
 } catch (Exception e) {
 return new SpawnResult(null, "", "", false, "failed to serialize payload: " + e.getMessage());
 }

 ProcessBuilder pb = new ProcessBuilder(argv);
 pb.redirectInput(ProcessBuilder.Redirect.PIPE);
 pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
 pb.redirectError(ProcessBuilder.Redirect.PIPE);
 // No shell — safe by construction

 Process process;
 try {
 process = pb.start();
 } catch (IOException e) {
 return new SpawnResult(null, "", "", false,
 e instanceof java.io.FileNotFoundException ? "command not found" : e.getMessage());
 }

 try {
 // Write stdin in a separate thread — the script may exit before
 // we finish writing, which would cause a broken pipe. Writing
 // in a thread avoids blocking on a process that already closed
 // its stdin, and the pipe close is best-effort.
 Thread stdinWriter = new Thread(() -> {
 try {
 process.getOutputStream().write(stdinJson.getBytes());
 process.getOutputStream().close();
 } catch (IOException ignored) {
 // Broken pipe — script already exited, stdin write is moot
 }
 }, "hook-stdin-writer");
 stdinWriter.setDaemon(true);
 stdinWriter.start();

 // H10: Read stdout and stderr concurrently via gobbler threads to avoid
 // pipe-buffer deadlock when the child fills one pipe while we block on the other.
 ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
 ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
 Thread stdoutGobbler = new Thread(() -> {
     try { process.getInputStream().transferTo(stdoutBuf); } catch (IOException ignored) { }
 }, "hook-stdout-gobbler");
 Thread stderrGobbler = new Thread(() -> {
     try { process.getErrorStream().transferTo(stderrBuf); } catch (IOException ignored) { }
 }, "hook-stderr-gobbler");
 stdoutGobbler.setDaemon(true);
 stderrGobbler.setDaemon(true);
 stdoutGobbler.start();
 stderrGobbler.start();

 // Wait with timeout
 boolean finished = process.waitFor(spec.timeout(), java.util.concurrent.TimeUnit.SECONDS);
 if (!finished) {
 process.destroyForcibly();
 stdinWriter.interrupt();
 stdoutGobbler.interrupt();
 stderrGobbler.interrupt();
 return new SpawnResult(null, "", "", true, null);
 }

 stdoutGobbler.join(5000);
 stderrGobbler.join(5000);
 String stdout = stdoutBuf.toString();
 String stderr = stderrBuf.toString();
 return new SpawnResult(process.exitValue(), stdout, stderr, false, null);
 } catch (Exception e) {
 process.destroyForcibly();
 return new SpawnResult(null, "", "", false, e.getMessage());
 }
 }

 // ─── Internal: payload building ───

 private ObjectNode buildPayload(String event, String toolName, String toolInput) {
 ObjectNode payload = MAPPER.createObjectNode();
 payload.put("hook_event_name", event);
 payload.put("tool_name", toolName != null ? toolName : "");
 if (toolInput != null) {
 // Wrap as tool_input object
 ObjectNode toolInputNode = MAPPER.createObjectNode();
 toolInputNode.put("command", toolInput);
 payload.set("tool_input", toolInputNode);
 }
 payload.put("session_id", "");
 try {
 payload.put("cwd", new File(".").getCanonicalPath());
 } catch (IOException e) {
 payload.put("cwd", "");
 }
 return payload;
 }

 // ─── Internal: response parsing ───

 private HookResponse parseResponse(String event, String stdout) {
 if (stdout == null || stdout.isBlank()) {
 return HookResponse.noop();
 }

 JsonNode data;
 try {
 data = MAPPER.readTree(stdout.trim());
 } catch (Exception e) {
 log.warn("Shell hook stdout was not valid JSON (event={}): {}", event, truncate(stdout, 200));
 return HookResponse.noop();
 }

 if (!data.isObject()) {
 return HookResponse.noop();
 }

 // For pre_tool_call: check for block decision in either shape
 if ("pre_tool_call".equals(event)) {
 // canonical shape: {"action": "block", "message": "..."}
 String action = data.path("action").asText("");
 if ("block".equals(action)) {
 String message = extractBlockMessage(data, "message", "reason");
 return new HookResponse(true, message, null);
 }
 // Claude-Code-style shape: {"decision": "block", "reason": "..."}
 String decision = data.path("decision").asText("");
 if ("block".equals(decision)) {
 String message = extractBlockMessage(data, "reason", "message");
 return new HookResponse(true, message, null);
 }
 return HookResponse.noop();
 }

 // For pre_llm_call: check for context injection
 String context = data.path("context").asText(null);
 if (context != null && !context.isBlank()) {
 return new HookResponse(false, null, context);
 }

 return HookResponse.noop();
 }

 private String extractBlockMessage(JsonNode data, String primaryField, String secondaryField) {
 String primary = data.path(primaryField).asText(null);
 String secondary = data.path(secondaryField).asText(null);
 String raw = primary != null && !primary.isEmpty() ? primary : secondary;
 return raw != null && !raw.isEmpty() ? raw : DEFAULT_BLOCK_MESSAGE;
 }

 // ─── Internal: allowlist / consent ───

 private boolean isAllowlisted(String event, String command) {
 try {
 Path p = Paths.get(allowlistPath);
 if (!Files.exists(p)) return false;
 JsonNode root = MAPPER.readTree(Files.readString(p));
 JsonNode approvals = root.path("approvals");
 if (!approvals.isArray()) return false;
 for (JsonNode entry : approvals) {
 if (!entry.isObject()) continue;
 String e = entry.path("event").asText("");
 String c = entry.path("command").asText("");
 if (e.equals(event) && c.equals(command)) {
 return true;
 }
 }
 } catch (Exception e) {
 log.debug("Failed to read allowlist: {}", e.getMessage());
 }
 return false;
 }

 private boolean promptAndRecord(String event, String command) {
 if (acceptHooks) {
 recordApproval(event, command);
 log.info("Shell hook auto-approved via acceptHooks: {} -> {}", event, command);
 return true;
 }

 // Non-interactive — cannot prompt
 if (System.console() == null) {
 return false;
 }

 // Interactive prompt
 System.console().printf("""
 
 ⚠ A shell hook is about to register a shell hook that will run a
 command on your behalf.
 
 Event: %s
 Command: %s
 
 Commands run with your full user credentials. Only approve
 commands you trust.
 """.formatted(event, command));
 String answer = System.console().readLine("Allow this hook to run? [y/N]: ");
 if (answer != null && answer.strip().toLowerCase().matches("y|yes")) {
 recordApproval(event, command);
 return true;
 }
 return false;
 }

 private void recordApproval(String event, String command) {
 try {
 Path p = Paths.get(allowlistPath);
 Files.createDirectories(p.getParent());

 // Read existing
 JsonNode root;
 if (Files.exists(p)) {
 root = MAPPER.readTree(Files.readString(p));
 } else {
 root = MAPPER.createObjectNode();
 }

 // Build new approvals list (replace existing entry for same event+command)
 ObjectNode newRoot = MAPPER.createObjectNode();
 var newApprovals = MAPPER.createArrayNode();

 // Copy existing entries (excluding same event+command)
 JsonNode existingApprovals = root.path("approvals");
 if (existingApprovals.isArray()) {
 for (JsonNode entry : existingApprovals) {
 String e = entry.path("event").asText("");
 String c = entry.path("command").asText("");
 if (!e.equals(event) || !c.equals(command)) {
 newApprovals.add(entry);
 }
 }
 }

 // Add new entry
 ObjectNode newEntry = MAPPER.createObjectNode();
 newEntry.put("event", event);
 newEntry.put("command", command);
 newEntry.put("approved_at", java.time.Instant.now().toString());
 newApprovals.add(newEntry);

 newRoot.set("approvals", newApprovals);

 // Atomically write (write to temp + rename)
 Path tmp = Files.createTempFile(p.getParent(), ".shell-hooks-allowlist.", ".tmp");
 Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(newRoot));
 Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
 java.nio.file.StandardCopyOption.ATOMIC_MOVE);
 } catch (Exception e) {
 log.warn("Failed to persist shell hook allowlist to {}: {}. " +
 "The approval is in-memory for this run, but the next startup will re-prompt.",
 allowlistPath, e.getMessage());
 }
 }

 // ─── Internal: utilities ───

 /**
 * Parse a command string into shell arguments (no shell expansion).
 * Handles simple quoting (single and double) but does NOT expand variables,
 * globs, or other shell metacharacters.
 */
  private String[] parseShellArgs(String command) {
 List<String> args = new ArrayList<>();
 StringBuilder current = new StringBuilder();
 boolean inSingleQuote = false;
 boolean inDoubleQuote = false;
 boolean hasContent = false;

 for (int i = 0; i < command.length(); i++) {
 char c = command.charAt(i);

 if (inSingleQuote) {
 if (c == '\'') {
 inSingleQuote = false;
 } else {
 current.append(c);
 }
 } else if (inDoubleQuote) {
 if (c == '"') {
 inDoubleQuote = false;
 } else {
 current.append(c);
 }
 } else {
 if (c == '\'') {
 inSingleQuote = true;
 hasContent = true;
 } else if (c == '"') {
 inDoubleQuote = true;
 hasContent = true;
 } else if (Character.isWhitespace(c)) {
 if (hasContent || current.length() > 0) {
 args.add(current.toString());
 current.setLength(0);
 hasContent = false;
 }
 } else if (c == '~' && current.length() == 0) {
 // Expand ~ to user home
 String home = System.getProperty("user.home", "/root");
 current.append(home);
 hasContent = true;
 } else {
 current.append(c);
 hasContent = true;
 }
 }
 }

 if (hasContent || current.length() > 0) {
 args.add(current.toString());
 }

  return args.toArray(new String[0]);
  }

  private String[] normalizeShellScriptArgv(String[] argv) {
  String executable = argv[0];
  if (!isShellScript(executable)) {
  return argv;
  }
  String[] wrapped = new String[argv.length + 1];
  wrapped[0] = "bash";
  wrapped[1] = toBashScriptPath(argv[0]);
  if (argv.length > 1) {
  System.arraycopy(argv, 1, wrapped, 2, argv.length - 1);
  }
  return wrapped;
  }

  private boolean isShellScript(String executable) {
  if (executable == null || executable.isBlank()) {
  return false;
  }
  String fileName;
  try {
  Path path = Paths.get(executable);
  Path name = path.getFileName();
  fileName = name != null ? name.toString() : executable;
  } catch (Exception e) {
  fileName = executable;
  }
  String lower = fileName.toLowerCase();
  return lower.endsWith(".sh") || lower.endsWith(".bash");
  }

  private String toBashScriptPath(String executable) {
  if (!isWindows()) {
  return executable;
  }
  String normalized = executable.replace('\\', '/');
  if (normalized.length() >= 3 && normalized.charAt(1) == ':' && normalized.charAt(2) == '/') {
  char drive = Character.toLowerCase(normalized.charAt(0));
  return "/mnt/" + drive + "/" + normalized.substring(3);
  }
  return normalized;
  }

  private boolean isWindows() {
  return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

 private static String truncate(String s, int maxLen) {
 if (s == null) return "";
 return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
 }
}

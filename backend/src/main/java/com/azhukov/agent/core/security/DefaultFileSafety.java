package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DefaultFileSafety implements FileSafety {

 private final AgentProperties properties;

 // ─── Write denylist: sensitive files that are NEVER writable ───

 /** Filename suffixes that are always denied for writing. */
 private static final Set<String> DENYLIST_FILENAMES = Set.of(
 ".env",
 ".env.local",
 ".env.development",
 ".env.production",
 ".env.test",
 ".env.staging",
 ".envrc",
 ".netrc",
 ".pgpass",
 ".npmrc",
 ".pypirc",
 ".git-credentials",
 "config.json", // .docker/config.json — matched via path segment check too
 "auth.json",
 "auth.lock",
 ".anthropic_oauth.json",
 "webhook_subscriptions.json"
 );

 /** Path segments that trigger deny when the normalized path contains them. */
 private static final Set<String> DENYLIST_SEGMENTS = Set.of(
 ".ssh",
 ".gnupg",
 ".aws",
 ".kube",
 ".docker",
 ".azure",
 ".config" // .config/gh, .config/gcloud matched via exact suffix
 );

 /** Exact normalized sub-paths that are always denied. */
 private static final Set<String> DENYLIST_EXACT_SUFFIXES = Set.of(
 ".aws/credentials",
 ".kube/config",
 ".docker/config.json",
 ".config/gh/hosts.yml",
 ".config/gcloud/credentials.db"
 );

 /** Absolute paths that are always denied. */
 private static final Set<String> DENYLIST_ABSOLUTE = Set.of(
 "/etc/sudoers",
 "/etc/shadow",
 "/etc/passwd",
 "/var/run/docker.sock",
 "/run/docker.sock"
 );

 /** Absolute directory prefixes that are always denied for writes. */
 private static final Set<String> DENYLIST_ABSOLUTE_PREFIXES = Set.of(
 "/etc/sudoers.d",
 "/etc/systemd",
 "/boot",
 "/usr/lib/systemd"
 );

 /** Hermes-owned directories that generic file tools must never mutate. */
 private static final Set<String> HERMES_WRITE_DENIED_DIRS = Set.of(
 "sessions",
 "mcp-tokens",
 "pairing"
 );

 /** Hermes-owned files that generic file tools must never mutate. */
 private static final Set<String> HERMES_WRITE_DENIED_FILES = Set.of(
 "state.db",
 "cache/bws_cache.enc.json"
 );

 /** Project instruction files that require an explicit human path in Hermes. */
 private static final Set<String> PROTECTED_INSTRUCTION_FILENAMES = Set.of(
 "agents.md",
 "agents.override.md",
 "claude.md",
 "soul.md",
 ".cursorrules"
 );

 // ─── Read-block list: sensitive files that should never be read ───

 private static final Set<String> READ_BLOCK_FILENAMES = Set.of(
 ".env",
 ".env.local",
 ".env.development",
 ".env.production",
 ".env.test",
 ".env.staging",
 ".envrc",
 "auth.json",
 "auth.lock",
 ".anthropic_oauth.json",
 "webhook_subscriptions.json",
 "google_oauth.json",
 "bws_cache.json"
 );

 private static final Set<String> READ_BLOCK_SEGMENTS = Set.of(
 ".ssh",
 ".gnupg"
 );

 private static final Set<String> READ_BLOCK_EXACT_SUFFIXES = Set.of(
 ".aws/credentials",
 "auth/google_oauth.json",
 "cache/bws_cache.json"
 );

 /** Hermes-owned directories that generic read/search tools must never expose. */
 private static final Set<String> HERMES_READ_BLOCK_DIRS = Set.of(
 "mcp-tokens",
 "browser-profile",
 "skills/.hub"
 );

 // ─── P1-10: Cross-profile write guard ───
 //
 // Profile-scoped directories under the project root that should be guarded.
 // Adding a new area here extends the guard with no other code change.
 // (Ported from the original project's file_safety.py PROFILE_SCOPED_AREAS)
 private static final Set<String> PROFILE_SCOPED_AREAS = Set.of(
 "skills", "plugins", "cron", "memories"
 );

 /**
 * Information about a cross-profile write attempt.
 */
 public record CrossProfileInfo(
 String activeProfile,
 String targetProfile,
 String area,
 String targetPath
 ) {}

 // ─── Path traversal protection ───

 private boolean hasTraversalBeforeNormalize(Path path) {
 if (path == null) return false;
 // Check for ".." as a path component before normalization
 for (Path element : path) {
 if (element.toString().equals("..")) {
 return true;
 }
 }
 return false;
 }

 private boolean matchesDenylist(Path normalized) {
 if (normalized == null) return false;
 String pathStr = normalized.toString();
 String unixPathStr = pathStr.replace('\\', '/');
 String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";

 if (matchesProtectedInstructionWrite(normalized)) {
 return true;
 }

 // Check absolute paths
 for (String abs : DENYLIST_ABSOLUTE) {
 if (matchesAbsolutePath(unixPathStr, abs)) {
 return true;
 }
 }

 for (String prefix : DENYLIST_ABSOLUTE_PREFIXES) {
 if (matchesAbsolutePrefix(unixPathStr, prefix)) {
 return true;
 }
 }

 // Check filenames
 if (DENYLIST_FILENAMES.contains(fileName)) {
 return true;
 }

 // Check exact suffix sub-paths
 for (String suffix : DENYLIST_EXACT_SUFFIXES) {
 if (endsWithPath(normalized, suffix)) {
 return true;
 }
 }

 // Check path segments (directories)
 for (Path element : normalized) {
 String seg = element.toString();
 if (DENYLIST_SEGMENTS.contains(seg)) {
 return true;
 }
 }

 return matchesHermesWriteDenylist(normalized);
 }

 private boolean matchesAbsolutePath(String unixPathStr, String absolutePath) {
 return unixPathStr.equals(absolutePath)
 || unixPathStr.equals(absolutePath.substring(1))
 || unixPathStr.contains(":/" + absolutePath.substring(1));
 }

 private boolean matchesAbsolutePrefix(String unixPathStr, String absolutePrefix) {
 String trimmed = absolutePrefix.endsWith("/") ? absolutePrefix.substring(0, absolutePrefix.length() - 1) : absolutePrefix;
 String withoutSlash = trimmed.substring(1);
 return unixPathStr.equals(trimmed)
 || unixPathStr.startsWith(trimmed + "/")
 || unixPathStr.equals(withoutSlash)
 || unixPathStr.startsWith(withoutSlash + "/")
 || unixPathStr.contains(":/" + withoutSlash + "/")
 || unixPathStr.endsWith(":/" + withoutSlash);
 }

 private boolean matchesProtectedInstructionWrite(Path path) {
 if (path == null || isUnderHermesBase(path)) {
 return false;
 }
 String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
 if (PROTECTED_INSTRUCTION_FILENAMES.contains(fileName)) {
 return true;
 }
 if (".hermes".equals(fileName)) {
 return true;
 }
 for (Path element : path) {
 if (".hermes".equals(element.toString().toLowerCase(Locale.ROOT))) {
 return true;
 }
 }
 return false;
 }

 @Override
 public boolean isPathAllowed(Path path) {
 if (path == null) {
 return false;
 }

 Path normalized = path.toAbsolutePath().normalize();
 Path safetyPath = resolveExistingForSafety(path);

 // Denylist check — even when broad file safety checks are disabled.
 if (matchesDenylist(normalized) || matchesDenylist(safetyPath)) {
 return false;
 }

 if (!properties.getSecurity().isFileSafetyEnabled()) {
 return true;
 }

 // Path traversal protection: block paths with ".." components
 if (hasTraversalBeforeNormalize(path)) {
 // Allow only if the normalized result still passes — but we block
 // because traversal attempts are suspicious by default.
 // However, internal ".." that stays within bounds should be allowed.
 // We normalize and check if it's still within allowed base.
 List<String> allowed = properties.getSecurity().getAllowedPaths();
 if (allowed == null || allowed.isEmpty()) {
 return true;
 }
 for (String allowedPath : allowed) {
 Path base = resolveExistingForSafety(Paths.get(allowedPath));
 if (safetyPath.startsWith(base)) {
 return true;
 }
 }
 return false;
 }

 List<String> allowed = properties.getSecurity().getAllowedPaths();
 if (allowed == null || allowed.isEmpty()) {
 return true;
 }
 for (String allowedPath : allowed) {
 Path base = resolveExistingForSafety(Paths.get(allowedPath));
 if (safetyPath.startsWith(base)) {
 return true;
 }
 }
 return false;
 }

 @Override
 public boolean isReadBlocked(Path path) {
 if (path == null) {
 return false;
 }

 Path normalized = path.toAbsolutePath().normalize();
 Path safetyPath = resolveExistingForSafety(path);
 String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";
 String safetyFileName = safetyPath.getFileName() != null ? safetyPath.getFileName().toString() : "";

 // Check filenames
 if (READ_BLOCK_FILENAMES.contains(fileName) || READ_BLOCK_FILENAMES.contains(safetyFileName)) {
 return true;
 }

 // Check exact suffix sub-paths
 for (String suffix : READ_BLOCK_EXACT_SUFFIXES) {
 if (endsWithPath(normalized, suffix) || endsWithPath(safetyPath, suffix)) {
 return true;
 }
 }

 // Check path segments (directories)
 for (Path element : safetyPath) {
 String seg = element.toString();
 if (READ_BLOCK_SEGMENTS.contains(seg)) {
 return true;
 }
 }

 return matchesHermesReadBlock(safetyPath);
 }

 private boolean endsWithPath(Path normalized, String suffix) {
 if (normalized == null || suffix == null || suffix.isBlank()) {
 return false;
 }
 return normalized.endsWith(Paths.get(suffix));
 }

 private boolean matchesHermesWriteDenylist(Path path) {
 if (path == null) {
 return false;
 }
 for (Path base : hermesBasePaths()) {
 for (String file : HERMES_WRITE_DENIED_FILES) {
 if (path.equals(base.resolve(file).normalize())) {
 return true;
 }
 }
 for (String dir : HERMES_WRITE_DENIED_DIRS) {
 if (isSameOrChild(path, base.resolve(dir).normalize())) {
 return true;
 }
 }
 }
 return false;
 }

 private boolean matchesHermesReadBlock(Path path) {
 if (path == null) {
 return false;
 }
 for (Path base : hermesBasePaths()) {
 for (String dir : HERMES_READ_BLOCK_DIRS) {
 if (isSameOrChild(path, base.resolve(dir).normalize())) {
 return true;
 }
 }
 }
 return false;
 }

 private boolean isUnderHermesBase(Path path) {
 for (Path base : hermesBasePaths()) {
 if (isSameOrChild(path, base)) {
 return true;
 }
 }
 return false;
 }

 private List<Path> hermesBasePaths() {
 Set<Path> bases = new LinkedHashSet<>();
 addHermesBase(bases, resolveHermesHome());
 addHermesBase(bases, resolveHermesRoot());
 return new ArrayList<>(bases);
 }

 private void addHermesBase(Set<Path> bases, String rawPath) {
 if (rawPath == null || rawPath.isBlank()) {
 return;
 }
 bases.add(Paths.get(rawPath).toAbsolutePath().normalize());
 }

 private boolean isSameOrChild(Path path, Path base) {
 return path.equals(base) || path.startsWith(base);
 }

 private Path resolveExistingForSafety(Path path) {
 Path absolute = path.toAbsolutePath().normalize();
 try {
 if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
 return absolute.toRealPath().normalize();
 }
 Path current = absolute;
 List<Path> missing = new ArrayList<>();
 while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
 Path fileName = current.getFileName();
 if (fileName != null) {
 missing.add(fileName);
 }
 current = current.getParent();
 }
 if (current == null) {
 return absolute;
 }
 Path resolved = current.toRealPath().normalize();
 for (int i = missing.size() - 1; i >= 0; i--) {
 resolved = resolved.resolve(missing.get(i).toString());
 }
 return resolved.normalize();
 } catch (IOException | SecurityException e) {
 return absolute;
 }
 }

 @Override
 public boolean isCommandAllowed(String command) {
 if (!properties.getSecurity().isFileSafetyEnabled()) {
 return true;
 }
 List<String> blocked = properties.getSecurity().getBlockedCommands();
 if (blocked == null || command == null) {
 return true;
 }
 String lower = command.toLowerCase();
 for (String b : blocked) {
 if (lower.contains(b.toLowerCase())) {
 return false;
 }
 }
 return true;
 }

 // ─── P1-10: Cross-profile write guard ───
 //
 // Ported from the original project's file_safety.py classify_cross_profile_target() and
 // get_cross_profile_warning(). This is a soft guard, NOT a security boundary:
 // the agent runs as the same OS user and has unrestricted terminal access,
 // so this returns a warning the model can choose to honor or override with
 // cross_profile=true. Same shape as the dangerous-command approval flow.

 /**
 * Resolve the active profile name from the project home directory.
 * <ul>
 * <li>{@code ~/.hermes} → {@code "default"}</li>
 * <li>{@code ~/.hermes/profiles/X} → {@code "X"}</li>
 * </ul>
 *
 * Falls back to {@code "default"} on any resolution failure so the guard
 * never throws into the tool path.
 *
 * @return the active profile name
 */
 public String resolveActiveProfileName() {
 String hermesHome = resolveHermesHome();
 String hermesRoot = resolveHermesRoot();
 if (hermesHome == null || hermesRoot == null) {
 return "default";
 }
 Path homePath = Paths.get(hermesHome).toAbsolutePath().normalize();
 Path rootPath = Paths.get(hermesRoot).toAbsolutePath().normalize();
 Path profilesDir = rootPath.resolve("profiles");

 // Only derive a profile name if homePath is under profilesDir
 if (!homePath.startsWith(profilesDir)) {
 return "default";
 }

 // homePath is under profilesDir — extract the first component after profiles/
 Path rel = profilesDir.relativize(homePath);
 if (rel.getNameCount() >= 1) {
 return rel.getName(0).toString();
 }
 return "default";
 }

 /**
 * Classify a write target as cross-profile if it lands in another
 * profile's scoped area (skills/plugins/cron/memories).
 *
 * <p>Returns {@code Optional.empty()} when the target is outside project scope,
 * is inside the ACTIVE profile, or doesn't hit a profile-scoped area.
 * Otherwise returns a {@link CrossProfileInfo} with:
 * <ul>
 * <li>{@code activeProfile}: name of the profile the agent is running as</li>
 * <li>{@code targetProfile}: name of the profile the path belongs to</li>
 * <li>{@code area}: which scoped area ("skills", "plugins", etc.)</li>
 * <li>{@code targetPath}: the resolved path string</li>
 * </ul>
 *
 * @param path the write target path
 * @return cross-profile info if the target is in another profile, empty otherwise
 */
 public Optional<CrossProfileInfo> classifyCrossProfileTarget(Path path) {
 if (path == null) {
 return Optional.empty();
 }
 String hermesRoot = resolveHermesRoot();
 if (hermesRoot == null) {
 return Optional.empty();
 }

 Path target = path.toAbsolutePath().normalize();
 Path rootReal = Paths.get(hermesRoot).toAbsolutePath().normalize();

 // Check if target is under the project root at all
 Path rel;
 try {
 rel = rootReal.relativize(target);
 } catch (IllegalArgumentException e) {
 return Optional.empty(); // target not under root
 }

 int partCount = rel.getNameCount();
 if (partCount == 0) {
 return Optional.empty();
 }

 String firstPart = rel.getName(0).toString();
 String targetProfile = null;
 String area = null;

 if (PROFILE_SCOPED_AREAS.contains(firstPart)) {
 // <root>/<area>/... → default profile
 targetProfile = "default";
 area = firstPart;
 } else if ("profiles".equals(firstPart) && partCount >= 3) {
 // <root>/profiles/<name>/<area>/... → named profile
 String profileName = rel.getName(1).toString();
 String areaName = rel.getName(2).toString();
 if (PROFILE_SCOPED_AREAS.contains(areaName)) {
 targetProfile = profileName;
 area = areaName;
 }
 }

 if (targetProfile == null) {
 return Optional.empty();
 }

 String activeProfile = resolveActiveProfileName();
 if (targetProfile.equals(activeProfile)) {
 // In-profile write — not a cross-profile event
 return Optional.empty();
 }

 return Optional.of(new CrossProfileInfo(
 activeProfile,
 targetProfile,
 area,
 target.toString()
 ));
 }

 /**
 * Return a model-facing warning string when {@code path} is cross-profile.
 *
 * <p>Returns {@code Optional.empty()} when the write is in-scope (same profile)
 * or outside the project entirely. The caller is expected to surface the warning
 * to the agent as a tool-result error, NOT to silently allow the write —
 * the agent must either get explicit user direction to proceed, or pass
 * {@code cross_profile=true} to its write tool.
 *
 * <p>This is defense-in-depth: the terminal tool runs as the same OS user
 * and can write any of these paths without going through this guard.
 * Treat the guard as a confusion-reducer, not a security boundary.
 *
 * @param path the write target path
 * @return warning string if cross-profile, empty if in-scope or outside the project
 */
 public Optional<String> getCrossProfileWarning(Path path) {
 return classifyCrossProfileTarget(path).map(info ->
 "Cross-profile write blocked by soft guard: " + info.targetPath() +
 " belongs to profile '" + info.targetProfile() + "', but the " +
 "agent is running under profile '" + info.activeProfile() + "'. " +
 "Editing another profile's " + info.area() + "/ will affect that " +
 "profile's future sessions, not the one you are currently in. " +
 "Confirm with the user before proceeding. To bypass this guard " +
 "after explicit user direction, retry the call with cross_profile=true. " +
 "(Defense-in-depth — not a security boundary; the terminal tool can still bypass.)"
 );
 }

 /**
 * Check if a write to the given path should be blocked by the cross-profile guard.
 *
 * <p>This is a convenience method combining {@link #getCrossProfileWarning(Path)}
 * with a simple boolean check. The caller should use {@link #getCrossProfileWarning(Path)}
 * to get the full warning message for the model.
 *
 * @param path the write target path
 * @return {@code true} if the write is cross-profile and should be guarded
 */
 public boolean isCrossProfile(Path path) {
 return classifyCrossProfileTarget(path).isPresent();
 }

 // ─── project home/root resolution ───

 /**
 * Resolve the active HERMES_HOME (profile-aware) path.
 * Uses the HERMES_HOME env var, falling back to {@code ~/.hermes}.
 */
 private String resolveHermesHome() {
 String env = System.getenv("HERMES_HOME");
 if (env != null && !env.isBlank()) {
 return env;
 }
 String userHome = System.getProperty("user.home", "/root");
 return userHome + "/.hermes";
 }

 /**
 * Resolve the project root directory (always the parent of any profile, never per-profile).
 * Uses the HERMES_ROOT env var if set, otherwise defaults to {@code ~/.hermes}.
 */
 private String resolveHermesRoot() {
 String env = System.getenv("HERMES_ROOT");
 if (env != null && !env.isBlank()) {
 return env;
 }
 // Default root is ~/.hermes (same as home when not in profile mode)
 String userHome = System.getProperty("user.home", "/root");
 return userHome + "/.hermes";
 }
}

package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
 ".config/gcloud/credentials.db",
 // Hermes parity (file_safety.py build_write_denied_prefixes): /etc/systemd
 // and /etc/sudoers.d are prefix-denied — suffix matching on the normalized
 // absolute path covers them without a blanket "systemd" segment ban.
 "etc/systemd",
 "etc/sudoers.d"
 );

 /** Absolute paths that are always denied. */
 private static final Set<String> DENYLIST_ABSOLUTE = Set.of(
 "/etc/sudoers",
 "/etc/shadow",
 "/etc/passwd",
 // Hermes _SENSITIVE_EXACT_PATHS (file_tools.py:661)
 "/var/run/docker.sock",
 "/run/docker.sock"
 );

 /**
 * Hermes parity (file_tools.py:651 _SENSITIVE_PATH_PREFIXES): sensitive
 * system-directory prefixes refused for writes — the whole /etc/ and /boot/
 * trees, plus /usr/lib/systemd/. The tool message tells the model to use the
 * terminal tool with sudo when a system file genuinely must change.
 */
 private static final List<String> DENYLIST_PREFIXES = List.of(
 "/etc/",
 "/boot/",
 "/usr/lib/systemd/"
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
 ".aws/credentials"
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
 String str = path.toString();
 // Check for ".." as a path component before normalization
 for (Path element : path) {
 if (element.toString().equals("..")) {
 return true;
 }
 }
 return false;
 }

 private Path resolveExistingPath(Path path) {
   Path normalized = path.toAbsolutePath().normalize();
   try {
   return Files.exists(normalized) ? normalized.toRealPath() : normalized;
   } catch (java.io.IOException e) {
   return normalized;
   }
 }

 private boolean matchesDenylist(Path normalized) {
 if (normalized == null) return false;
 String pathStr = normalized.toString();
 String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";

 // Check absolute paths
 for (String abs : DENYLIST_ABSOLUTE) {
 if (pathStr.equals(abs)) {
 return true;
 }
 }

 // Check system-directory prefixes (Hermes _SENSITIVE_PATH_PREFIXES)
 for (String prefix : DENYLIST_PREFIXES) {
 if (pathStr.startsWith(prefix)) {
 return true;
 }
 }

 // Check filenames
 if (DENYLIST_FILENAMES.contains(fileName)) {
 return true;
 }

 // Check exact suffix sub-paths
 for (String suffix : DENYLIST_EXACT_SUFFIXES) {
 if (pathStr.endsWith("/" + suffix) || pathStr.endsWith(suffix)) {
 return true;
 }
 // Directory-prefix form: "/etc/systemd/system/x.service" must match the
 // "etc/systemd" prefix (Hermes build_write_denied_prefixes semantics).
 if (pathStr.contains("/" + suffix + "/")) {
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

 return false;
 }

 @Override
 public boolean isWriteAllowed(Path path) {
 if (path == null) {
 return false;
 }
 Path resolved = resolveExistingPath(path);
 if (matchesDenylist(resolved)) {
 return false;
 }
 if (!properties.getSecurity().isFileSafetyEnabled()) {
 return true;
 }
 return isPathAllowed(path);
 }

 @Override
 public boolean isPathAllowed(Path path) {
 if (path == null) {
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
 Path normalized = path.toAbsolutePath().normalize();
 if (matchesDenylist(normalized)) {
 return false;
 }
 List<String> allowed = properties.getSecurity().getAllowedPaths();
 if (allowed == null || allowed.isEmpty()) {
 return true;
 }
 for (String allowedPath : allowed) {
 Path base = Paths.get(allowedPath).toAbsolutePath().normalize();
 if (normalized.startsWith(base)) {
 return true;
 }
 }
 return false;
 }

 Path normalized = resolveExistingPath(path);

 // Denylist check — even inside allowed paths
 if (matchesDenylist(normalized)) {
 return false;
 }

 List<String> allowed = properties.getSecurity().getAllowedPaths();
 if (allowed == null || allowed.isEmpty()) {
 return true;
 }
 for (String allowedPath : allowed) {
 Path base = Paths.get(allowedPath).toAbsolutePath().normalize();
 if (normalized.startsWith(base)) {
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
 if (!properties.getSecurity().isFileSafetyEnabled()) {
 return false;
 }

 Path normalized = path.toAbsolutePath().normalize();
 String pathStr = normalized.toString();
 String fileName = normalized.getFileName() != null ? normalized.getFileName().toString() : "";

 // Check filenames
 if (READ_BLOCK_FILENAMES.contains(fileName)) {
 return true;
 }

 // Check exact suffix sub-paths
 for (String suffix : READ_BLOCK_EXACT_SUFFIXES) {
 if (pathStr.endsWith("/" + suffix)) {
 return true;
 }
 }

 // Check path segments (directories)
 for (Path element : normalized) {
 String seg = element.toString();
 if (READ_BLOCK_SEGMENTS.contains(seg)) {
 return true;
 }
 }

 return false;
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
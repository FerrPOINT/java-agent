package com.azhukov.agent.core.security;

import java.nio.file.Path;
import java.util.Optional;

public interface FileSafety {

 boolean isPathAllowed(Path path);

 /**
 * Checks if a WRITE to the given path is allowed. False when the path hits
 * the sensitive denylist (~/.pgpass, ~/.git-credentials, /etc/sudoers,
 * ~/.aws/*, ~/.gnupg/*, ~/.kube/*, /etc/systemd/*, ...) — Hermes parity
 * (agent/file_safety.py is_write_denied). When file safety is disabled
 * everything is allowed.
 */
 default boolean isWriteAllowed(Path path) {
 return true;
 }

 boolean isCommandAllowed(String command);

 /**
 * Checks if reading the given path should be blocked.
 * Blocks reads of sensitive credential files such as .env,
 * auth.json, .ssh/, .aws/credentials, .gnupg/, etc.
 *
 * @param path the path to check
 * @return true if the read should be blocked, false otherwise
 */
 default boolean isReadBlocked(Path path) {
 return false;
 }

 // ─── P1-10: Cross-profile write guard ───

 /**
 * Classify a write target as cross-profile if it lands in another
 * profile's scoped area (skills/plugins/cron/memories).
 *
 * @param path the write target path
 * @return cross-profile info if the target is in another profile, empty otherwise
 */
 default Optional<DefaultFileSafety.CrossProfileInfo> classifyCrossProfileTarget(Path path) {
 return Optional.empty();
 }

 /**
 * Return a model-facing warning string when {@code path} is cross-profile.
 *
 * @param path the write target path
 * @return warning string if cross-profile, empty if in-scope or outside the project
 */
 default Optional<String> getCrossProfileWarning(Path path) {
 return Optional.empty();
 }

 /**
 * Check if a write to the given path should be blocked by the cross-profile guard.
 *
 * @param path the write target path
 * @return {@code true} if the write is cross-profile
 */
 default boolean isCrossProfile(Path path) {
 return false;
 }

 /**
 * Resolve the active profile name.
 *
 * @return the active profile name (e.g. "default" or a named profile)
 */
 default String resolveActiveProfileName() {
 return "default";
 }
}
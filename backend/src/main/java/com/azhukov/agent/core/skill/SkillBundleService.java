package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * S6: Skill Bundle Service — YAML multi-skill aliases.
 * <p>
 * A bundle is a YAML file that names a set of skills to load together.
 * Invoking a bundle loads every referenced skill's full content into a single
 * user message, the same way /skill-name does — but for N skills at once.
 * <p>
 * S6 FIX: Uses SnakeYAML for YAML parsing instead of hand-rolled parser.
 * S6 FIX: installBundle builds runtime invocation message instead of persisting entries.
 * S6: Adds reload_bundles(), conflict resolution (bundles checked first), usage tracking.
 * <p>
 * Ported from the original project's skill_bundles.py.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillBundleService {

 private final SkillManager skillManager;
 private final AgentProperties properties;

 // S6: Bundle definition
 public record Bundle(
 String name,
 String description,
 List<String> skills,
 String instruction,
 String path
 ) {}

 // S6: Bundle invocation result
 public record BundleInvocationResult(
 String message,
 List<String> loadedSkillNames,
 List<String> missingSkillNames
 ) {}

 // S6: Cached bundle mapping: "/slug" -> Bundle
 private volatile Map<String, Bundle> bundlesCache = new LinkedHashMap<>();
 private volatile long bundlesCacheMtime = -1;

 /**
 * S6: Scan the bundles directory and rebuild the cache.
 * Returns mapping of "/slug" -> Bundle.
 */
 public synchronized Map<String, Bundle> scanBundles() {
 Map<String, Bundle> out = new LinkedHashMap<>();
 long maxMtime = 0;
 for (Path file : iterBundleFiles()) {
 try {
 Bundle bundle = loadBundleFile(file);
 if (bundle == null) continue;
 String key = "/" + SkillUtils.slugify(bundle.name());
 if (out.containsKey(key)) {
 log.warn("Duplicate bundle slug {} from {}; keeping {}", key, file, out.get(key).path());
 continue;
 }
 out.put(key, bundle);
 try {
 long mtime = Files.getLastModifiedTime(file).toMillis();
 if (mtime > maxMtime) maxMtime = mtime;
 } catch (IOException ignored) {}
 } catch (Exception e) {
 log.warn("Failed to load bundle file: {}", file, e);
 }
 }
 // Also check directory mtime
 Path bundlesDir = getBundlesDir();
 if (Files.isDirectory(bundlesDir)) {
 try {
 long dirMtime = Files.getLastModifiedTime(bundlesDir).toMillis();
 if (dirMtime > maxMtime) maxMtime = dirMtime;
 } catch (IOException ignored) {}
 }
 bundlesCache = out;
 bundlesCacheMtime = maxMtime;
 return out;
 }

 /**
 * S6: Return current bundle mapping, rescanning when disk changed.
 */
 public Map<String, Bundle> getSkillBundles() {
 long currentMtime = computeMaxMtime();
 if (bundlesCache.isEmpty() || bundlesCacheMtime != currentMtime) {
 return scanBundles();
 }
 return bundlesCache;
 }

 /**
 * S6: Resolve a user-typed command to its canonical bundle slash key.
 * Hyphens and underscores are treated interchangeably.
 */
 public String resolveBundleCommandKey(String command) {
 if (command == null || command.isBlank()) return null;
 String cmdKey = "/" + command.replace("_", "-");
 return getSkillBundles().containsKey(cmdKey) ? cmdKey : null;
 }

 /**
 * S6: Build the user message content for a bundle slash command invocation.
 * S6 FIX: Builds runtime invocation message (load all referenced skills) instead of persisting entries.
 */
 public BundleInvocationResult buildBundleInvocationMessage(String cmdKey, String userInstruction, String sessionId) {
 Map<String, Bundle> bundles = getSkillBundles();
 Bundle bundle = bundles.get(cmdKey);
 if (bundle == null) return null;

 List<String> loadedNames = new ArrayList<>();
 List<String> missing = new ArrayList<>();
 List<String> skillBlocks = new ArrayList<>();
 Set<String> seen = new HashSet<>();

 for (String skillId : bundle.skills()) {
 String identifier = skillId == null ? "" : skillId.trim();
 if (identifier.isEmpty() || seen.contains(identifier)) continue;
 seen.add(identifier);

 // Try to load from filesystem skills dir first
 String content = loadSkillContent(identifier, sessionId);
 if (content == null) {
 // Try from SkillManager (database)
 content = skillManager.getSkill(identifier);
 }
 if (content == null) {
 missing.add(identifier);
 continue;
 }

 // S6: Bump usage tracking
 bumpUse(identifier);

 loadedNames.add(identifier);
 String activationNote = "[Loaded as part of the \"" + bundle.name() + "\" skill bundle.]";
 skillBlocks.add(activationNote + "\n\n" + content.strip());
 }

 if (skillBlocks.isEmpty()) return null;

 // Header
 List<String> headerLines = new ArrayList<>();
 headerLines.add("[IMPORTANT: The user has invoked the \"" + bundle.name() +
 "\" skill bundle, loading " + loadedNames.size() +
 " skills together. Treat every skill below as active guidance for this turn.]");
 headerLines.add("");
 headerLines.add("Bundle: " + bundle.name());
 headerLines.add("Skills loaded: " + String.join(", ", loadedNames));
 if (!missing.isEmpty()) {
 headerLines.add("Skills missing (skipped): " + String.join(", ", missing));
 }
 if (bundle.instruction() != null && !bundle.instruction().isBlank()) {
 headerLines.add("");
 headerLines.add("Bundle instruction: " + bundle.instruction());
 }
 if (userInstruction != null && !userInstruction.isBlank()) {
 headerLines.add("");
 headerLines.add("User instruction: " + userInstruction);
 }

 String header = String.join("\n", headerLines);
 String message = header + "\n\n" + String.join("\n\n", skillBlocks);
 return new BundleInvocationResult(message, loadedNames, missing);
 }

 /**
 * S6: Load skill content from filesystem skills directories.
 */
 private String loadSkillContent(String skillName, String sessionId) {
 // Try each skills directory
 Path skillsDir = getSkillsDir();
 Path skillMd = skillsDir.resolve(skillName).resolve("SKILL.md");
 if (Files.exists(skillMd)) {
 try {
 return Files.readString(skillMd);
 } catch (IOException e) {
 log.debug("Failed to read skill {}: {}", skillName, e.getMessage());
 }
 }
 // Try external dirs
 if (properties != null && properties.getSkills() != null) {
 for (String extDir : properties.getSkills().getExternalDirs()) {
 Path ext = Path.of(extDir).resolve(skillName).resolve("SKILL.md");
 if (Files.exists(ext)) {
 try {
 return Files.readString(ext);
 } catch (IOException e) {
 log.debug("Failed to read external skill {}: {}", skillName, e.getMessage());
 }
 }
 }
 }
 return null;
 }

 /**
 * S6: Reload bundles — re-scan and return added/removed/unchanged diff.
 */
 public SkillCommandService.ReloadDiff reloadBundles() {
 Map<String, String> before = new LinkedHashMap<>();
 for (var entry : bundlesCache.entrySet()) {
 before.put(entry.getKey().substring(1), entry.getValue().description());
 }
 Map<String, Bundle> newBundles = scanBundles();
 Map<String, String> after = new LinkedHashMap<>();
 for (var entry : newBundles.entrySet()) {
 after.put(entry.getKey().substring(1), entry.getValue().description());
 }
 return SkillCommandService.buildDiff(before, after, newBundles.size());
 }

 /**
 * S6: List all bundles for display.
 */
 public List<Bundle> listBundlesInfo() {
 return new ArrayList<>(getSkillBundles().values());
 }

 /**
 * S6: Save a bundle to disk.
 */
 public void saveBundle(Bundle bundle) {
 try {
 Path bundlesDir = getBundlesDir();
 Files.createDirectories(bundlesDir);
 Path file = bundlesDir.resolve(SkillUtils.slugify(bundle.name()) + ".yaml");
 Yaml yaml = new Yaml();
 Map<String, Object> data = new LinkedHashMap<>();
 data.put("name", bundle.name());
 data.put("description", bundle.description());
 data.put("skills", bundle.skills());
 if (bundle.instruction() != null && !bundle.instruction().isBlank()) {
 data.put("instruction", bundle.instruction());
 }
 Files.writeString(file, yaml.dump(data));
 log.info("Saved bundle config: {}", file);
 scanBundles(); // refresh cache
 } catch (IOException e) {
 throw new RuntimeException("Failed to save bundle config: " + e.getMessage(), e);
 }
 }

 /**
 * S6: Delete a bundle by name.
 */
 public boolean deleteBundle(String bundleName) {
 Path file = getBundlesDir().resolve(SkillUtils.slugify(bundleName) + ".yaml");
 try {
 boolean deleted = Files.deleteIfExists(file);
 if (deleted) scanBundles();
 return deleted;
 } catch (IOException e) {
 return false;
 }
 }

 /**
 * S6: Bump usage tracking for a skill on bundle invocation.
 */
 public void bumpUse(String skillName) {
 try {
 skillManager.incrementViewCount(skillName);
 } catch (Exception e) {
 log.debug("Failed to bump usage for skill: {}", skillName);
 }
 }

 // ── Private helpers ───────────────────────────────────────────────────

 private List<Path> iterBundleFiles() {
 Path base = getBundlesDir();
 if (!Files.isDirectory(base)) return List.of();
 List<Path> files = new ArrayList<>();
 try (var stream = Files.list(base)) {
 stream.filter(p -> {
 String name = p.getFileName().toString();
 return name.endsWith(".yaml") || name.endsWith(".yml");
 }).sorted().forEach(files::add);
 } catch (IOException e) {
 log.debug("Failed to list bundles directory: {}", base);
 }
 return files;
 }

 private long computeMaxMtime() {
 long max = 0;
 Path base = getBundlesDir();
 if (Files.isDirectory(base)) {
 try {
 max = Files.getLastModifiedTime(base).toMillis();
 } catch (IOException ignored) {}
 }
 for (Path file : iterBundleFiles()) {
 try {
 long mtime = Files.getLastModifiedTime(file).toMillis();
 if (mtime > max) max = mtime;
 } catch (IOException ignored) {}
 }
 return max;
 }

 /**
 * S6 FIX: Parse a single bundle YAML file using SnakeYAML. Returns null on any error.
 */
 @SuppressWarnings("unchecked")
 private Bundle loadBundleFile(Path path) {
 String raw;
 try {
 raw = Files.readString(path);
 } catch (IOException e) {
 log.warn("Could not read bundle {}: {}", path, e.getMessage());
 return null;
 }

 Map<String, Object> data;
 try {
 Yaml yaml = new Yaml();
 Object parsed = yaml.load(raw);
 if (!(parsed instanceof Map)) {
 log.warn("Bundle {} is not a mapping; skipping", path);
 return null;
 }
 data = (Map<String, Object>) parsed;
 } catch (Exception e) {
 log.warn("Invalid YAML in bundle {}: {}", path, e.getMessage());
 return null;
 }

 String name = data.get("name") != null ? String.valueOf(data.get("name")).trim() : path.getFileName().toString().replaceAll("\\.[^.]+$", "");
 if (name.isEmpty()) {
 log.warn("Bundle {} has no name; skipping", path);
 return null;
 }

 Object skillsObj = data.get("skills");
 if (!(skillsObj instanceof List<?> skillsList) || skillsList.isEmpty()) {
 log.warn("Bundle {} has no skills list; skipping", path);
 return null;
 }
 List<String> skills = new ArrayList<>();
 for (Object s : skillsList) {
 String skillName = s != null ? s.toString().trim() : "";
 if (!skillName.isEmpty()) skills.add(skillName);
 }
 if (skills.isEmpty()) {
 log.warn("Bundle {} has empty skills list; skipping", path);
 return null;
 }

 String description = data.get("description") != null ? String.valueOf(data.get("description")).trim() : "";
 if (description.isEmpty()) description = "Load " + skills.size() + " skills as a bundle";
 String instruction = data.get("instruction") != null ? String.valueOf(data.get("instruction")).trim() : "";

 String slug = SkillUtils.slugify(name);
 if (slug.isEmpty()) {
 log.warn("Bundle {} yielded empty slug; skipping", path);
 return null;
 }

 return new Bundle(name, description, skills, instruction, path.toString());
 }

 private Path getBundlesDir() {
 if (properties != null && properties.getCore() != null) {
 String wd = properties.getCore().getWorkingDirectory();
 if (wd != null && !wd.isBlank()) {
 return Path.of(wd, "skill-bundles");
 }
 }
 return Path.of("skill-bundles");
 }

 private Path getSkillsDir() {
 if (properties != null && properties.getCore() != null) {
 String wd = properties.getCore().getWorkingDirectory();
 if (wd != null && !wd.isBlank()) {
 return Path.of(wd, "skills");
 }
 }
 return Path.of("skills");
 }

 // ── Legacy compatibility ──────────────────────────────────────────────

 /**
 * S6: Install a skill bundle — builds runtime invocation message instead of persisting entries.
 */
 public void installBundle(Bundle bundle) {
 if (bundle == null || bundle.name() == null || bundle.skills() == null) {
 throw new IllegalArgumentException("Bundle must have name and skills list");
 }
 // S6 FIX: Don't persist — just log that the bundle is available for runtime invocation
 log.info("Registered skill bundle '{}' with {} skills (runtime invocation)", bundle.name(), bundle.skills().size());
 }

 public void install(String bundleName) {
 String workingDir = properties.getCore().getWorkingDirectory();
 Path bundleDir = Path.of(workingDir, "bundles", bundleName);
 if (!Files.isDirectory(bundleDir)) {
 throw new IllegalArgumentException("Bundle directory not found: " + bundleDir);
 }
 Path skillMd = bundleDir.resolve("SKILL.md");
 if (!Files.exists(skillMd)) {
 throw new IllegalArgumentException("SKILL.md not found in bundle: " + bundleDir);
 }
 try {
 String content = Files.readString(skillMd);
 skillManager.saveSkill(bundleName, content);
 log.info("Installed skill bundle '{}' from {}", bundleName, bundleDir);
 } catch (IOException e) {
 throw new IllegalArgumentException("Failed to read SKILL.md from bundle: " + bundleDir, e);
 }
 }

 public List<String> list() {
 return skillManager.listSkillNames();
 }

 public void uninstall(String bundleName) {
 boolean deleted = skillManager.deleteSkill(bundleName);
 log.info("Uninstall bundle '{}': deleted={}", bundleName, deleted);
 }

 public void uninstallBundle(String bundleName) {
 for (String skillName : skillManager.listSkillNames()) {
 if (skillName.startsWith(bundleName + "/")) {
 skillManager.deleteSkill(skillName);
 }
 }
 log.info("Uninstalled skill bundle '{}'", bundleName);
 }
}
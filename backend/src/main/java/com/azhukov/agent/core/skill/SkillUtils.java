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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S6: Lightweight skill metadata utilities — frontmatter parsing, platform/env matching,
 * external dirs, disabled skills, excluded dirs filter.
 * <p>
 * Ported from the original project's skill_utils.py.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SkillUtils {

 private final AgentProperties properties;

 // ── Platform mapping ──────────────────────────────────────────────────

 private static final Map<String, String> PLATFORM_MAP = Map.of(
 "macos", "darwin",
 "linux", "linux",
 "windows", "win32"
 );

 // S6: Directories to exclude when scanning for skill files
 private static final Set<String> EXCLUDED_SKILL_DIRS = Set.of(
 ".git", ".github", ".hub", ".archive", ".venv", "venv",
 "node_modules", "site-packages", "__pycache__",
 ".tox", ".nox", ".pytest_cache", ".mypy_cache", ".ruff_cache"
 );

 // S6: Recognized environment tags
 private static final Set<String> KNOWN_ENVIRONMENTS = Set.of("kanban", "docker", "s6");

 private static final Pattern SKILL_INVALID_CHARS = Pattern.compile("[^a-z0-9-]");
 private static final Pattern SKILL_MULTI_HYPHEN = Pattern.compile("-{2,}");

 // ── Injection pattern detection (ported from skills_tool.py _INJECTION_PATTERNS) ──

 /**
  * Simple prompt-injection detection patterns. These are checked on skill_view
  * to warn the user (but not block viewing), mirroring the Hermes behavior.
  */
 public static final List<String> INJECTION_PATTERNS = List.of(
     "ignore previous instructions",
     "ignore all previous",
     "you are now",
     "disregard your",
     "forget your instructions",
     "new instructions:",
     "system prompt:",
     "<system>",
     "]]>"
 );

 // ── Env var name validation ──

 private static final Pattern ENV_VAR_NAME_RE = Pattern.compile("^[A-Z_][A-Z0-9_]*$");

 // ── Tags parsing ──────────────────────────────────────────────────────

 /**
  * Parse tags from a frontmatter value. Handles:
  * <ul>
  *   <li>Already-parsed list (from YAML): [tag1, tag2]</li>
  *   <li>String with brackets: "[tag1, tag2]"</li>
  *   <li>Comma-separated string: "tag1, tag2"</li>
  * </ul>
  */
 public static List<String> parseTags(Object tagsValue) {
     if (tagsValue == null) {
         return List.of();
     }
     if (tagsValue instanceof List<?> list) {
         return list.stream()
             .filter(Objects::nonNull)
             .map(String::valueOf)
             .map(String::trim)
             .filter(s -> !s.isEmpty())
             .toList();
     }
     String str = String.valueOf(tagsValue).trim();
     if (str.isEmpty()) {
         return List.of();
     }
     if (str.startsWith("[") && str.endsWith("]")) {
         str = str.substring(1, str.length() - 1);
     }
     return Arrays.stream(str.split(","))
         .map(String::trim)
         .map(s -> {
             // Strip surrounding quotes
             if ((s.startsWith("\"") && s.endsWith("\"")) ||
                 (s.startsWith("'") && s.endsWith("'"))) {
                 return s.substring(1, s.length() - 1);
             }
             return s;
         })
         .filter(s -> !s.isEmpty())
         .toList();
 }

 // ── Required environment variables extraction ─────────────────────────

 /**
  * Extract required environment variables from frontmatter.
  * Supports:
  * <ul>
  *   <li>Comma-separated string: "API_KEY, SECRET_TOKEN"</li>
  *   <li>YAML list: [API_KEY, SECRET_TOKEN]</li>
  *   <li>List of dicts: [{name: API_KEY, help: ...}, ...]</li>
  * </ul>
  * @return list of env var entries, each with at least "name" key
  */
 @SuppressWarnings("unchecked")
 public static List<Map<String, Object>> extractRequiredEnvironmentVariables(Map<String, Object> frontmatter) {
     Object raw = frontmatter.get("required_environment_variables");
     if (raw == null) {
         return List.of();
     }
     // Normalize to list
     List<?> rawList;
     if (raw instanceof List<?> list) {
         rawList = list;
     } else if (raw instanceof Map<?, ?> map) {
         rawList = List.of(map);
     } else {
         // String: comma-separated
         rawList = Arrays.stream(String.valueOf(raw).split(","))
             .map(String::trim)
             .filter(s -> !s.isEmpty())
             .toList();
     }

     List<Map<String, Object>> result = new ArrayList<>();
     Set<String> seen = new HashSet<>();

     for (Object item : rawList) {
         if (item instanceof String nameStr) {
             String name = nameStr.trim();
             if (!name.isEmpty() && !seen.contains(name) && ENV_VAR_NAME_RE.matcher(name).matches()) {
                 seen.add(name);
                 result.add(Map.of("name", name));
             }
         } else if (item instanceof Map<?, ?> itemMap) {
             Object nameObj = itemMap.get("name");
             if (nameObj == null) nameObj = itemMap.get("env_var");
             if (nameObj == null) continue;
             String name = String.valueOf(nameObj).trim();
             if (name.isEmpty() || seen.contains(name) || !ENV_VAR_NAME_RE.matcher(name).matches()) {
                 continue;
             }
             seen.add(name);
             Map<String, Object> entry = new LinkedHashMap<>();
             entry.put("name", name);
             Object prompt = itemMap.get("prompt");
             entry.put("prompt", prompt != null ? String.valueOf(prompt).trim() : "Enter value for " + name);
             Object help = itemMap.get("help");
             if (help == null) help = itemMap.get("provider_url");
             if (help == null) help = itemMap.get("url");
             if (help != null) entry.put("help", String.valueOf(help).trim());
             Object requiredFor = itemMap.get("required_for");
             if (requiredFor != null) entry.put("required_for", String.valueOf(requiredFor).trim());
             if (itemMap.get("optional") != null) entry.put("optional", true);
             result.add(entry);
         }
     }
     return result;
 }

 /**
  * Check which required env vars are NOT set in System.getenv().
  * @return list of missing env var names
  */
 public static List<String> findMissingEnvironmentVariables(List<Map<String, Object>> requiredEnvVars) {
     List<String> missing = new ArrayList<>();
     for (Map<String, Object> entry : requiredEnvVars) {
         if (Boolean.TRUE.equals(entry.get("optional"))) continue;
         String name = String.valueOf(entry.get("name"));
         String value = System.getenv(name);
         if (value == null || value.isBlank()) {
             missing.add(name);
         }
     }
     return missing;
 }

 // ── Injection pattern detection ──────────────────────────────────────

 /**
  * Scan content for suspicious prompt-injection patterns.
  * @return list of matched patterns (empty if none found)
  */
 public static List<String> detectInjectionPatterns(String content) {
     if (content == null || content.isBlank()) {
         return List.of();
     }
     String lower = content.toLowerCase();
     List<String> found = new ArrayList<>();
     for (String pattern : INJECTION_PATTERNS) {
         if (lower.contains(pattern)) {
             found.add(pattern);
         }
     }
     return found;
 }

 // ── Frontmatter parsing ───────────────────────────────────────────────

 /**
 * S6: Parse YAML frontmatter from a markdown string.
 * Returns a FrontmatterResult containing the parsed frontmatter map and the remaining body.
 */
 public static FrontmatterResult parseFrontmatter(String content) {
 Map<String, Object> frontmatter = new LinkedHashMap<>();
 String body = content;

 if (content == null || !content.startsWith("---")) {
 return new FrontmatterResult(frontmatter, body);
 }

 // Find closing ---
 Pattern endPattern = Pattern.compile("\\n---\\s*\\n");
 Matcher endMatcher = endPattern.matcher(content.substring(3));
 if (!endMatcher.find()) {
 return new FrontmatterResult(frontmatter, body);
 }

 String yamlContent = content.substring(3, endMatcher.start() + 3);
 body = content.substring(endMatcher.end() + 3);

 try {
 Yaml yaml = new Yaml();
 Object parsed = yaml.load(yamlContent);
 if (parsed instanceof Map) {
 frontmatter = (Map<String, Object>) parsed;
 }
 } catch (Exception e) {
 // Fallback: simple key:value parsing
 for (String line : yamlContent.trim().split("\n")) {
 int idx = line.indexOf(':');
 if (idx < 0) continue;
 String key = line.substring(0, idx).trim();
 String value = line.substring(idx + 1).trim();
 frontmatter.put(key, value);
 }
 }

 return new FrontmatterResult(frontmatter, body);
 }

 /**
 * S6: Frontmatter parse result.
 */
 public record FrontmatterResult(Map<String, Object> frontmatter, String body) {}

 // ── Platform matching ────────────────────────────────────────────────

 /**
 * S6: Return true when the skill is compatible with the current OS.
 * Skills declare platform requirements via a top-level 'platforms' list in frontmatter.
 * If absent or empty, compatible with all platforms.
 */
 public static boolean skillMatchesPlatform(Map<String, Object> frontmatter) {
 Object platforms = frontmatter.get("platforms");
 if (platforms == null) {
 return true;
 }
 List<?> platformList;
 if (platforms instanceof List<?> list) {
 platformList = list;
 } else {
 platformList = List.of(platforms);
 }
 if (platformList.isEmpty()) {
 return true;
 }
 String currentOs = System.getProperty("os.name", "").toLowerCase();
 for (Object platform : platformList) {
 String normalized = String.valueOf(platform).toLowerCase().trim();
 String mapped = PLATFORM_MAP.getOrDefault(normalized, normalized);
 // Check if current OS starts with the mapped value
 if (currentOs.startsWith(mapped) || currentOs.contains(mapped)) {
 return true;
 }
 }
 return false;
 }

 // ── Environment matching ──────────────────────────────────────────────

 /**
 * S6: Return true when the skill is relevant to the current runtime environment.
 * Skills declare an 'environments' list in frontmatter.
 * If absent or empty, relevant in all environments (backward-compatible default).
 * This is an OFFER-time filter; explicit loads bypass it.
 */
 public static boolean skillMatchesEnvironment(Map<String, Object> frontmatter) {
 Object environments = frontmatter.get("environments");
 if (environments == null) {
 return true;
 }
 List<?> envList;
 if (environments instanceof List<?> list) {
 envList = list;
 } else {
 envList = List.of(environments);
 }
 if (envList.isEmpty()) {
 return true;
 }
 for (Object env : envList) {
 String normalized = String.valueOf(env).toLowerCase().trim();
 if (normalized.isEmpty()) continue;
 if (!KNOWN_ENVIRONMENTS.contains(normalized)) {
 // Unknown tag — fail open (don't hide skill)
 return true;
 }
 if (detectEnvironment(normalized)) {
 return true;
 }
 }
 return false;
 }

 /**
 * S6: Detect whether the named runtime environment is currently active.
 * Cached per process for the lifetime.
 */
 private static final Map<String, Boolean> ENV_DETECT_CACHE = new HashMap<>();

 private static boolean detectEnvironment(String env) {
 if (ENV_DETECT_CACHE.containsKey(env)) {
 return ENV_DETECT_CACHE.get(env);
 }
 boolean result = true; // fail-open default
 if ("docker".equals(env)) {
 result = Files.isDirectory(Path.of("/.dockerenv")) ||
 System.getenv().containsKey("KUBERNETES_SERVICE_HOST");
 } else if ("s6".equals(env)) {
 result = Files.isDirectory(Path.of("/run/s6")) ||
 Files.isDirectory(Path.of("/package/admin/s6-overlay"));
 } else if ("kanban".equals(env)) {
 result = System.getenv("HERMES_KANBAN_TASK") != null ||
 System.getenv("HERMES_KANBAN_BOARD") != null;
 }
 ENV_DETECT_CACHE.put(env, result);
 return result;
 }

 // ── Excluded directories ─────────────────────────────────────────────

 /**
 * S6: Check if a path contains any excluded directory component.
 */
 public static boolean isExcludedSkillPath(Path path) {
 for (Path component : path) {
 if (EXCLUDED_SKILL_DIRS.contains(component.toString())) {
 return true;
 }
 }
 return false;
 }

 /**
 * S6: Return the set of excluded skill directory names.
 */
 public static Set<String> getExcludedSkillDirs() {
 return EXCLUDED_SKILL_DIRS;
 }

 // ── Slug normalization ───────────────────────────────────────────────

 /**
 * S6: Normalize a skill name to a hyphen-separated slug.
 * Strips non-alnum chars, collapses multiple hyphens.
 */
 public static String slugify(String name) {
 if (name == null || name.isBlank()) return "";
 String slug = name.toLowerCase().replace(" ", "-").replace("_", "-");
 slug = SKILL_INVALID_CHARS.matcher(slug).replaceAll("");
 slug = SKILL_MULTI_HYPHEN.matcher(slug).replaceAll("-");
 return slug.strip().replaceAll("^-|-$", "");
 }

 // ── Disabled skills ──────────────────────────────────────────────────

 /**
 * S6: Read disabled skill names from config.
 * Reads skills.disabled from AgentProperties.
 */
 public Set<String> getDisabledSkillNames() {
 if (properties == null || properties.getSkills() == null) {
 return Set.of();
 }
 List<String> disabled = properties.getSkills().getDisabled();
 if (disabled == null || disabled.isEmpty()) {
 return Set.of();
 }
 Set<String> result = new LinkedHashSet<>();
 for (String name : disabled) {
 if (name != null && !name.isBlank()) {
 result.add(name.trim());
 }
 }
 return result;
 }

 /**
 * S6: Read disabled skill names for a specific platform.
 * Reads skills.platform_disabled.<platform> from config.
 */
 public Set<String> getDisabledSkillNames(String platform) {
 if (properties == null || properties.getSkills() == null) {
 return Set.of();
 }
 if (platform != null && !platform.isBlank()) {
 Map<String, List<String>> platformDisabled = properties.getSkills().getPlatformDisabled();
 if (platformDisabled != null && platformDisabled.containsKey(platform)) {
 List<String> names = platformDisabled.get(platform);
 if (names != null) {
 Set<String> result = new LinkedHashSet<>();
 for (String name : names) {
 if (name != null && !name.isBlank()) {
 result.add(name.trim());
 }
 }
 return result;
 }
 }
 }
 return getDisabledSkillNames();
 }

 // ── External skills directories ──────────────────────────────────────

 private volatile List<Path> cachedExternalDirs;
 private volatile long cachedExternalDirsMtime = -1;

 /**
 * S6: Read skills.external_dirs from config, expand ~/ and ${VAR}, validate existence.
 * Cached by mtime of the config source.
 */
 public List<Path> getExternalSkillsDirs() {
 if (properties == null || properties.getSkills() == null) {
 return List.of();
 }
 List<String> rawDirs = properties.getSkills().getExternalDirs();
 if (rawDirs == null || rawDirs.isEmpty()) {
 return List.of();
 }

 Path localSkillsDir = getLocalSkillsDir();
 List<Path> result = new ArrayList<>();
 Set<Path> seen = new HashSet<>();

 for (String entry : rawDirs) {
 if (entry == null || entry.isBlank()) continue;
 String expanded = expandPath(entry.trim());
 Path p = Path.of(expanded);
 if (!p.isAbsolute()) {
 // Resolve relative to working directory
 p = Path.of(properties.getCore().getWorkingDirectory()).resolve(p).toAbsolutePath().normalize();
 } else {
 p = p.toAbsolutePath().normalize();
 }
 if (p.equals(localSkillsDir)) continue;
 if (seen.contains(p)) continue;
 if (Files.isDirectory(p)) {
 seen.add(p);
 result.add(p);
 } else {
 log.debug("External skills dir does not exist, skipping: {}", p);
 }
 }
 return result;
 }

 /**
 * S6: Return all skill directories: local first, then external.
 */
 public List<Path> getAllSkillsDirs() {
 List<Path> dirs = new ArrayList<>();
 dirs.add(getLocalSkillsDir());
 dirs.addAll(getExternalSkillsDirs());
 return dirs;
 }

 /**
 * S6: Get the local skills directory.
 */
 public Path getLocalSkillsDir() {
 if (properties != null && properties.getCore() != null) {
 String wd = properties.getCore().getWorkingDirectory();
 if (wd != null && !wd.isBlank()) {
 return Path.of(wd, "skills");
 }
 }
 return Path.of("skills");
 }

 /**
 * S6: Expand ~/ and ${VAR} in a path string.
 */
 private static String expandPath(String path) {
 String result = path;
 // Expand ~ to user home
 if (result.startsWith("~")) {
 result = System.getProperty("user.home") + result.substring(1);
 }
 // Expand ${VAR} or $VAR
 result = expandEnvVars(result);
 return result;
 }

 private static String expandEnvVars(String text) {
 // ${VAR} pattern
 Pattern varPattern = Pattern.compile("\\$\\{(\\w+)}");
 Matcher m = varPattern.matcher(text);
 StringBuilder sb = new StringBuilder();
 while (m.find()) {
 String varName = m.group(1);
 String value = System.getenv(varName);
 m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : "${" + varName + "}"));
 }
 m.appendTail(sb);
 return sb.toString();
 }

 // ── Skill file iteration ──────────────────────────────────────────────

 /**
 * S6: Walk a skills directory yielding sorted paths matching the filename.
 * Excludes .git, node_modules, venv, __pycache__, etc.
 */
 public static List<Path> iterSkillIndexFiles(Path skillsDir, String filename) {
 if (skillsDir == null || !Files.isDirectory(skillsDir)) {
 return List.of();
 }
 List<Path> matches = new ArrayList<>();
 walkSkillDir(skillsDir, filename, matches);
 matches.sort(Comparator.comparing(p -> skillsDir.relativize(p).toString()));
 return matches;
 }

 private static void walkSkillDir(Path dir, String filename, List<Path> results) {
 try (var stream = Files.list(dir)) {
 List<Path> entries = stream.toList();
 for (Path entry : entries) {
 if (Files.isDirectory(entry)) {
 String dirName = entry.getFileName().toString();
 if (!EXCLUDED_SKILL_DIRS.contains(dirName)) {
 walkSkillDir(entry, filename, results);
 }
 } else if (entry.getFileName().toString().equals(filename)) {
 results.add(entry);
 }
 }
 } catch (IOException e) {
 log.debug("Failed to walk skill directory: {}", dir);
 }
 }

 // ── Description extraction ───────────────────────────────────────────

 /**
 * S6: Extract a truncated description from parsed frontmatter.
 */
 public static String extractSkillDescription(Map<String, Object> frontmatter) {
 Object rawDesc = frontmatter.get("description");
 if (rawDesc == null) return "";
 String desc = String.valueOf(rawDesc).trim();
 // Strip surrounding quotes
 if ((desc.startsWith("\"") && desc.endsWith("\"")) ||
 (desc.startsWith("'") && desc.endsWith("'"))) {
 desc = desc.substring(1, desc.length() - 1);
 }
 if (desc.length() > 60) {
 return desc.substring(0, 57) + "...";
 }
 return desc;
 }

 // ── Skill config extraction ─────────────────────────────────────────

 /**
 * S6: Extract config variable declarations from parsed frontmatter.
 * Skills declare config.yaml settings via:
 * metadata:
 * hermes:
 * config:
 * - key: wiki.path
 * description: Path to the Wiki
 * default: "~/wiki"
 */
 @SuppressWarnings("unchecked")
 public static List<SkillConfigVar> extractSkillConfigVars(Map<String, Object> frontmatter) {
 Object metadata = frontmatter.get("metadata");
 if (!(metadata instanceof Map<?, ?> metaMap)) {
 return List.of();
 }
 Object hermes = metaMap.get("hermes");
 if (!(hermes instanceof Map<?, ?> hermesMap)) {
 return List.of();
 }
 Object raw = hermesMap.get("config");
 if (raw == null) {
 return List.of();
 }
 List<?> rawList;
 if (raw instanceof List<?> list) {
 rawList = list;
 } else if (raw instanceof Map<?, ?> map) {
 rawList = List.of(map);
 } else {
 return List.of();
 }

 List<SkillConfigVar> result = new ArrayList<>();
 Set<String> seen = new HashSet<>();
 for (Object item : rawList) {
 if (!(item instanceof Map<?, ?> itemMap)) continue;
 Object keyObj = itemMap.get("key");
 if (keyObj == null) continue;
 String key = String.valueOf(keyObj).trim();
 if (key.isEmpty() || seen.contains(key)) continue;
 Object descObj = itemMap.get("description");
 String desc = descObj != null ? String.valueOf(descObj).trim() : "";
 if (desc.isEmpty()) continue;
 Object defaultObj = itemMap.get("default");
 Object promptObj = itemMap.get("prompt");
 String prompt = (promptObj instanceof String s && !s.isBlank()) ? s.trim() : desc;
 result.add(new SkillConfigVar(key, desc, defaultObj, prompt));
 seen.add(key);
 }
 return result;
 }

 /**
 * S6: A single skill config variable declaration.
 */
 public record SkillConfigVar(String key, String description, Object defaultValue, String prompt) {}

 /**
 * S6: Discover all skill config vars across all skills, deduplicated and attributed.
 */
 public List<DiscoveredSkillConfigVar> discoverAllSkillConfigVars() {
 List<DiscoveredSkillConfigVar> allVars = new ArrayList<>();
 Set<String> seenKeys = new HashSet<>();
 Set<String> disabled = getDisabledSkillNames();

 for (Path skillsDir : getAllSkillsDirs()) {
 if (!Files.isDirectory(skillsDir)) continue;
 for (Path skillFile : iterSkillIndexFiles(skillsDir, "SKILL.md")) {
 try {
 String raw = Files.readString(skillFile);
 FrontmatterResult fr = parseFrontmatter(raw);
 Map<String, Object> fm = fr.frontmatter();

 Object nameObj = fm.get("name");
 String skillName = nameObj != null ? String.valueOf(nameObj) : skillFile.getParent().getFileName().toString();
 if (disabled.contains(skillName)) continue;
 if (!skillMatchesPlatform(fm)) continue;

 List<SkillConfigVar> configVars = extractSkillConfigVars(fm);
 for (SkillConfigVar var : configVars) {
 if (!seenKeys.contains(var.key())) {
 allVars.add(new DiscoveredSkillConfigVar(var, skillName));
 seenKeys.add(var.key());
 }
 }
 } catch (Exception e) {
 log.debug("Failed to parse skill file: {}", skillFile);
 }
 }
 }
 return allVars;
 }

 /**
 * S6: A skill config var with source attribution.
 */
 public record DiscoveredSkillConfigVar(SkillConfigVar var, String skill) {}

 /**
 * S6: Resolve current values for skill config vars from AgentProperties.
 * Reads skills.config.<key> from properties, applies defaults, expands paths.
 */
 public Map<String, Object> resolveSkillConfigValues(List<SkillConfigVar> configVars) {
 Map<String, Object> resolved = new LinkedHashMap<>();
 if (configVars == null || configVars.isEmpty()) {
 return resolved;
 }
 Map<String, Object> skillConfigMap = properties != null && properties.getSkills() != null
 ? properties.getSkills().getConfig()
 : Map.of();

 for (SkillConfigVar var : configVars) {
 Object value = resolveDotPath(skillConfigMap, var.key());
 if (value == null || (value instanceof String s && s.isBlank())) {
 value = var.defaultValue();
 }
 if (value instanceof String s && (s.contains("~") || s.contains("${"))) {
 value = expandPath(s);
 }
 resolved.put(var.key(), value != null ? value : "");
 }
 return resolved;
 }

 /**
 * S6: Walk a nested map following a dotted key. Returns null if any part is missing.
 */
 @SuppressWarnings("unchecked")
 private static Object resolveDotPath(Map<String, Object> map, String dottedKey) {
 if (map == null || dottedKey == null) return null;
 String[] parts = dottedKey.split("\\.");
 Object current = map;
 for (String part : parts) {
 if (current instanceof Map<?, ?> m && m.containsKey(part)) {
 current = m.get(part);
 } else {
 return null;
 }
 }
 return current;
 }
}
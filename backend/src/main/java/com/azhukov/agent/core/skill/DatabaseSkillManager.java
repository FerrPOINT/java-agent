package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.persistence.entity.SkillEntity;
import com.azhukov.agent.persistence.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class DatabaseSkillManager implements SkillManager {

 private final SkillRepository skillRepository;
 private final AgentProperties properties;

 // ─── Validation constants (ported from the original project's skill_manager_tool.py) ───

 private static final int MAX_NAME_LENGTH = 64;
 private static final int MAX_DESCRIPTION_LENGTH = 1024;
 private static final int MAX_SKILL_CONTENT_CHARS = 100_000; // ~36k tokens
 private static final int MAX_SUPPORT_FILE_BYTES = 1_048_576; // 1 MiB

 /** Filesystem-safe, URL-friendly skill name pattern. */
 private static final Pattern VALID_NAME_RE = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

 /** Subdirectories allowed for writeSupportFile/removeSupportFile. */
 private static final List<String> ALLOWED_SUBDIRS = List.of("references", "templates", "scripts", "assets");

 public DatabaseSkillManager(SkillRepository skillRepository) {
 this.skillRepository = skillRepository;
 this.properties = null;
 }

 @Override
 public List<String> listSkillNames() {
 return skillRepository.findAll().stream()
 .filter(e -> !e.isArchived())
 .map(SkillEntity::getName)
 .toList();
 }

 @Override
 public String getSkill(String name) {
 return skillRepository.findByName(name)
 .map(SkillEntity::getContent)
 .orElse(null);
 }

 @Override
 public void saveSkill(String name, String content) {
 saveSkill(name, content, WriteOrigin.FOREGROUND);
 }

 @Override
 public void saveSkill(String name, String content, WriteOrigin origin) {
 // P1-9: Validate skill name
 String nameError = validateName(name);
 if (nameError != null) {
 throw new IllegalArgumentException(nameError);
 }

 // P1-9: Validate content size
 String sizeError = validateContentSize(content);
 if (sizeError != null) {
 throw new IllegalArgumentException(sizeError);
 }

 // P1-9: Validate frontmatter structure
 String frontmatterError = validateFrontmatter(content);
 if (frontmatterError != null) {
 throw new IllegalArgumentException(frontmatterError);
 }

 // P1-9: Security scan — block dangerous content for agent-created skills
 TrustLevel trustLevel = determineTrustLevelForSave(name);
 String scanError = SkillSecurityScanner.scanAndGuard(name, content, trustLevel);
 if (scanError != null) {
     log.warn("Security scan blocked skill save '{}': {}", name, scanError);
     throw new SecurityException(scanError);
 }

 // Hermes parity (skill_linter.py create-path contract): advisory convention
 // findings are surfaced as guidance, NEVER as a hard reject — the hard
 // rejects already live in validateFrontmatter/scanAndGuard above.
 List<SkillConventionLinter.LintFinding> lintFindings = SkillConventionLinter.lintContent(name, content);
 if (!lintFindings.isEmpty()) {
     log.info("Skill '{}' advisory convention findings (non-blocking): {}", name,
         SkillConventionLinter.formatFindings(lintFindings));
 }

 SkillEntity e = skillRepository.findByName(name).orElse(new SkillEntity());
 e.setName(name);
 e.setContent(content);
 // Hermes parity: persist frontmatter description/category into their DB
 // columns on save so the system-prompt index doesn't re-parse raw content
 // on every prompt build.
 e.setDescription(extractFrontmatterField(content, "description"));
 String fmCategory = extractFrontmatterField(content, "category");
 if (fmCategory != null && !fmCategory.isBlank()) {
     e.setCategory(fmCategory);
 }
 e.setUpdatedAt(Instant.now());
 if (e.getCreatedAt() == null) {
 e.setCreatedAt(Instant.now());
 }
 // S6: Set write origin
 e.setWriteOrigin(origin != null ? origin.name() : WriteOrigin.FOREGROUND.name());
 // S7: Update telemetry
 e.setManageCount(e.getManageCount() + 1);
 e.setLastActivityAt(Instant.now());
 // S12: Default trust level for agent-created skills
 if (e.getTrustLevel() == null) {
     e.setTrustLevel(TrustLevel.AGENT_CREATED.name());
 }
 skillRepository.save(e);
 }

 /**
  * Finding 4.4: Save skill with absorbedInto metadata.
  */
 @Override
 public void saveSkill(String name, String content, WriteOrigin origin, String absorbedInto) {
     // Reuse the existing saveSkill logic by calling the 3-arg version,
     // then set absorbedInto if provided. We need to find the entity again
     // since the 3-arg version already saved it.
     saveSkill(name, content, origin);
     if (absorbedInto != null && !absorbedInto.isBlank()) {
         skillRepository.findByName(name).ifPresent(e -> {
             e.setAbsorbedInto(absorbedInto);
             skillRepository.save(e);
         });
     }
 }

 @Override
 public boolean deleteSkill(String name) {
     return deleteSkill(name, null);
 }

 // S: Delete with absorbed_into — set absorbedInto on the entity before deletion
 @Override
 public boolean deleteSkill(String name, String absorbedInto) {
     return skillRepository.findByName(name).map(e -> {
         // P1-9: Pinned-skill guard — prevent deletion of pinned skills
         if (e.isPinned()) {
             log.warn("Skill '{}' is pinned and cannot be deleted by skill manager. " +
                 "Ask the user to unpin it first.", name);
             throw new IllegalStateException(
                 "Skill '" + name + "' is pinned and cannot be deleted. " +
                 "Ask the user to unpin it first."
             );
         }
         if (absorbedInto != null && !absorbedInto.isBlank()) {
             e.setAbsorbedInto(absorbedInto);
             e.setUpdatedAt(Instant.now());
             skillRepository.save(e);
             log.info("Skill '{}' marked as absorbed into '{}' before deletion.", name, absorbedInto);
         }
         skillRepository.delete(e);
         return true;
     }).orElse(false);
 }

 // S7: Telemetry
 @Override
 public void incrementViewCount(String name) {
 skillRepository.findByName(name).ifPresent(e -> {
 e.setViewCount(e.getViewCount() + 1);
 e.setLastActivityAt(Instant.now());
 skillRepository.save(e);
 });
 }

 // S7: Telemetry
 @Override
 public void incrementManageCount(String name) {
 skillRepository.findByName(name).ifPresent(e -> {
 e.setManageCount(e.getManageCount() + 1);
 e.setLastActivityAt(Instant.now());
 skillRepository.save(e);
 });
 }

 // S9: Rich listing
 @Override
 public List<SkillInfo> listSkills() {
 return skillRepository.findAll().stream()
 .filter(e -> !e.isArchived())
 .map(this::toSkillInfo)
 .toList();
 }

 // S9: Get skill info with metadata
 @Override
 public SkillInfo getSkillInfo(String name) {
     return skillRepository.findByName(name)
         .map(this::toSkillInfo)
         .orElse(null);
 }

 /**
  * Multi-strategy skill lookup (mirrors Hermes skills_tool.py lines 1000-1078).
  * <ul>
  *   <li>Strategy 1: Direct DB lookup by name</li>
  *   <li>Strategy 2: Recursive filesystem search by directory name</li>
  *   <li>Strategy 3: Frontmatter {@code name:} field match</li>
  * </ul>
  * If multiple strategies find different skills, a collision is reported.
  */
 @Override
 public SkillLookupResult getSkillInfoMultiStrategy(String name) {
     List<SkillInfo> candidates = new ArrayList<>();
     List<String> candidatePaths = new ArrayList<>();

     // Strategy 1: Direct DB lookup by name
     SkillInfo dbInfo = skillRepository.findByName(name)
         .map(this::toSkillInfo)
         .orElse(null);
     if (dbInfo != null) {
         candidates.add(dbInfo);
         candidatePaths.add("db:" + name);
     }

     // Strategy 2 & 3: Recursive filesystem search by directory name + frontmatter name match
     Path skillsDir = getSkillsDir();
     if (Files.isDirectory(skillsDir)) {
         for (Path skillFile : SkillUtils.iterSkillIndexFiles(skillsDir, "SKILL.md")) {
             if (SkillUtils.isExcludedSkillPath(skillFile)) continue;
             // Strategy 2: directory name matches
             Path parentDir = skillFile.getParent();
             if (parentDir != null && name.equals(parentDir.getFileName().toString())) {
                 SkillInfo fsInfo = loadSkillFromFile(name, skillFile);
                 if (fsInfo != null) {
                     if (!candidates.contains(fsInfo)) {
                         candidates.add(fsInfo);
                         candidatePaths.add(skillFile.toString());
                     }
                 }
                 continue;
             }
             // Strategy 3: frontmatter name: matches
             try {
                 String content = Files.readString(skillFile);
                 SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(content);
                 Object fmName = fr.frontmatter().get("name");
                 if (fmName != null && name.equals(String.valueOf(fmName))) {
                     SkillInfo fsInfo = loadSkillFromFile(name, skillFile);
                     if (fsInfo != null && !candidates.contains(fsInfo)) {
                         candidates.add(fsInfo);
                         candidatePaths.add(skillFile.toString());
                     }
                 }
             } catch (IOException e) {
                 log.debug("Failed to read skill file for name match: {}", skillFile);
             }
         }

         // Strategy: legacy flat <name>.md files
         for (Path foundMd : findLegacyMdFiles(skillsDir, name)) {
             SkillInfo fsInfo = loadSkillFromFile(name, foundMd);
             if (fsInfo != null && !candidates.contains(fsInfo)) {
                 candidates.add(fsInfo);
                 candidatePaths.add(foundMd.toString());
             }
         }
     }

     // Collision detection
     if (candidates.size() > 1) {
         log.warn("Skill name collision for '{}': {} candidates — {}", name, candidates.size(), String.join("; ", candidatePaths));
         return new SkillLookupResult(
             null,
             candidatePaths,
             "Ambiguous skill name '" + name + "': " + candidates.size() +
             " skills match across DB and filesystem. Refusing to guess — load one explicitly."
         );
     }

     if (candidates.isEmpty()) {
         return new SkillLookupResult(null, List.of(), null);
     }
     return new SkillLookupResult(candidates.get(0), List.of(), null);
 }

 private SkillInfo loadSkillFromFile(String name, Path skillFile) {
     try {
         String content = Files.readString(skillFile);
         SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(content);
         Map<String, Object> fm = fr.frontmatter();
         String skillName = fm.get("name") != null ? String.valueOf(fm.get("name")) : name;
         String category = fm.get("category") != null ? String.valueOf(fm.get("category")) : "";

         // Extract tags and related_skills
         List<String> tags = extractTags(fm);
         List<String> relatedSkills = extractRelatedSkills(fm);

         // BUG 6: Check frontmatter disabled: true for filesystem skills.
         // Previously isSkillDisabled() always returned false, ignoring the frontmatter field.
         // Now we parse the frontmatter directly, same as toSkillInfo() does for DB skills.
         boolean disabled = false;
         Object disabledObj = fm.get("disabled");
         if (disabledObj != null) {
             disabled = Boolean.TRUE.equals(disabledObj) || "true".equals(String.valueOf(disabledObj));
         }

         // List linked files from filesystem
         LinkedFiles linkedFiles = listLinkedFilesFromFilesystem(skillFile.getParent());

         return new SkillInfo(
             skillName, content, extractFrontmatterField(content, "description"), category,
             null, 0, 0, null, false, "AGENT_CREATED",
             tags, relatedSkills, disabled, linkedFiles
         );
     } catch (IOException e) {
         log.debug("Failed to read skill file: {}", skillFile);
         return null;
     }
 }

 private List<Path> findLegacyMdFiles(Path skillsDir, String name) {
     List<Path> results = new ArrayList<>();
     String targetName = name + ".md";
     findLegacyMdRecursive(skillsDir, targetName, results);
     return results;
 }

 private void findLegacyMdRecursive(Path dir, String targetName, List<Path> results) {
     try (var stream = Files.list(dir)) {
         for (Path entry : stream.toList()) {
             if (Files.isDirectory(entry)) {
                 String dirName = entry.getFileName().toString();
                 if (!SkillUtils.getExcludedSkillDirs().contains(dirName)) {
                     findLegacyMdRecursive(entry, targetName, results);
                 }
             } else if (entry.getFileName().toString().equals(targetName) && !targetName.equals("SKILL.md")) {
                 results.add(entry);
             }
         }
     } catch (IOException e) {
         log.debug("Failed to scan for legacy md: {}", dir);
     }
 }

 private LinkedFiles listLinkedFilesFromFilesystem(Path skillDir) {
     if (skillDir == null || !Files.isDirectory(skillDir)) {
         return new LinkedFiles(List.of(), List.of(), List.of(), List.of());
     }
     List<String> refs = new ArrayList<>();
     List<String> tmpl = new ArrayList<>();
     List<String> scr = new ArrayList<>();
     List<String> ast = new ArrayList<>();

     Path refsDir = skillDir.resolve("references");
     if (Files.isDirectory(refsDir)) {
         try (var stream = Files.list(refsDir)) {
             stream.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".md"))
                 .forEach(p -> refs.add(skillDir.relativize(p).toString().replace('\\', '/')));
         } catch (IOException e) { /* ignore */ }
     }

     Path tmplDir = skillDir.resolve("templates");
     if (Files.isDirectory(tmplDir)) {
         try (var stream = Files.walk(tmplDir, 3)) {
             stream.filter(Files::isRegularFile)
                 .forEach(p -> {
                     String name = p.getFileName().toString();
                     if (name.endsWith(".md") || name.endsWith(".py") || name.endsWith(".yaml") ||
                         name.endsWith(".yml") || name.endsWith(".json") || name.endsWith(".tex") ||
                         name.endsWith(".sh")) {
                         tmpl.add(skillDir.relativize(p).toString().replace('\\', '/'));
                     }
                 });
         } catch (IOException e) { /* ignore */ }
     }

     Path scriptsDir = skillDir.resolve("scripts");
     if (Files.isDirectory(scriptsDir)) {
         try (var stream = Files.list(scriptsDir)) {
             stream.filter(Files::isRegularFile)
                 .forEach(p -> {
                     String name = p.getFileName().toString();
                     if (name.endsWith(".py") || name.endsWith(".sh") || name.endsWith(".bash") ||
                         name.endsWith(".js") || name.endsWith(".ts") || name.endsWith(".rb")) {
                         scr.add(skillDir.relativize(p).toString().replace('\\', '/'));
                     }
                 });
         } catch (IOException e) { /* ignore */ }
     }

     Path assetsDir = skillDir.resolve("assets");
     if (Files.isDirectory(assetsDir)) {
         try (var stream = Files.walk(assetsDir, 3)) {
             stream.filter(Files::isRegularFile)
                 .forEach(p -> ast.add(skillDir.relativize(p).toString().replace('\\', '/')));
         } catch (IOException e) { /* ignore */ }
     }

     return new LinkedFiles(List.copyOf(refs), List.copyOf(tmpl), List.copyOf(scr), List.copyOf(ast));
 }

 private List<String> extractTags(Map<String, Object> frontmatter) {
     // Check metadata.hermes.tags first (agentskills.io convention), fall back to top-level
     Object metadata = frontmatter.get("metadata");
     if (metadata instanceof Map<?, ?> metaMap) {
         Object hermes = metaMap.get("hermes");
         if (hermes instanceof Map<?, ?> hermesMap) {
             Object tags = hermesMap.get("tags");
             if (tags != null) {
                 return SkillUtils.parseTags(tags);
             }
         }
     }
     return SkillUtils.parseTags(frontmatter.get("tags"));
 }

 private List<String> extractRelatedSkills(Map<String, Object> frontmatter) {
     Object metadata = frontmatter.get("metadata");
     if (metadata instanceof Map<?, ?> metaMap) {
         Object hermes = metaMap.get("hermes");
         if (hermes instanceof Map<?, ?> hermesMap) {
             Object rs = hermesMap.get("related_skills");
             if (rs != null) {
                 return SkillUtils.parseTags(rs);
             }
         }
     }
     return SkillUtils.parseTags(frontmatter.get("related_skills"));
 }

 private boolean isSkillDisabled(String skillName) {
     if (skillName == null || skillName.isBlank()) return false;
     // Check frontmatter disabled: true — this is checked by the caller via SkillViewTool
     // Here we just check config-level disabled list
     // (the SkillUtils-based config check requires properties injection)
     return false;
 }

 private SkillInfo toSkillInfo(SkillEntity e) {
     String category = e.getCategory() != null ? e.getCategory() : extractCategory(e.getContent());
     // Parse frontmatter for tags and related_skills
     List<String> tags = List.of();
     List<String> relatedSkills = List.of();
     boolean disabled = false;
     LinkedFiles linkedFiles = null;
     if (e.getContent() != null) {
         SkillUtils.FrontmatterResult fr = SkillUtils.parseFrontmatter(e.getContent());
         Map<String, Object> fm = fr.frontmatter();
         tags = extractTags(fm);
         relatedSkills = extractRelatedSkills(fm);
         // Check disabled: true in frontmatter
         Object disabledObj = fm.get("disabled");
         disabled = disabledObj != null && (Boolean.TRUE.equals(disabledObj) || "true".equals(String.valueOf(disabledObj)));
     }
     // List linked files from filesystem
     Path skillDir = getSkillsDir().resolve(e.getName());
     if (Files.isDirectory(skillDir)) {
         linkedFiles = listLinkedFilesFromFilesystem(skillDir);
     }
     return new SkillInfo(
         e.getName(),
         e.getContent(),
         e.getDescription(),
         category,
         e.getUpdatedAt(),
         e.getViewCount(),
         e.getManageCount(),
         e.getLastActivityAt(),
         e.isArchived(),
         e.getTrustLevel() != null ? e.getTrustLevel() : TrustLevel.AGENT_CREATED.name(),
         tags,
         relatedSkills,
         disabled,
         linkedFiles
     );
 }

 // S9: Parse a scalar field from YAML frontmatter (e.g. description, category)
 private String extractFrontmatterField(String content, String field) {
 if (content == null || content.isBlank()) return null;
 if (!content.startsWith("---")) return null;
 int end = content.indexOf("---", 3);
 if (end > 0) {
 String yaml = content.substring(3, end);
 String prefix = field + ":";
 for (String line : yaml.lines().toList()) {
 String trimmed = line.trim();
 if (trimmed.startsWith(prefix)) {
 String value = trimmed.substring(prefix.length()).trim();
 // Strip surrounding quotes
 if (value.length() >= 2
 && ((value.startsWith("\"") && value.endsWith("\""))
 || (value.startsWith("'") && value.endsWith("'")))) {
 value = value.substring(1, value.length() - 1);
 }
 return value;
 }
 }
 }
 return null;
 }

 // S9: Parse YAML frontmatter category
 private String extractCategory(String content) {
 if (content == null || content.isBlank()) return "";
 // Try to parse YAML frontmatter
 if (content.startsWith("---")) {
 int end = content.indexOf("---", 3);
 if (end > 0) {
 String yaml = content.substring(3, end);
 for (String line : yaml.lines().toList()) {
 if (line.trim().startsWith("category:")) {
 return line.substring("category:".length()).trim();
 }
 }
 }
 }
 // Fallback: first heading
 for (String line : content.lines().toList()) {
 if (line.startsWith("# ")) {
 return line.substring(2).trim();
 }
 }
 return "";
 }

 // S3: Write support file
 @Override
 public void writeSupportFile(String skillName, String filePath, String content) {
     validateSupportFilePath(filePath);
     // P1-9: Validate support file content size
     if (content != null && content.getBytes().length > MAX_SUPPORT_FILE_BYTES) {
         throw new IllegalArgumentException(
             "Support file content exceeds " + MAX_SUPPORT_FILE_BYTES + " bytes (limit: 1 MiB)."
         );
     }
     // P2-49: Security scan — block dangerous content in support files
     TrustLevel trustLevel = determineTrustLevelForSave(skillName);
     String scanError = SkillSecurityScanner.scanAndGuard(skillName, content, trustLevel);
     if (scanError != null) {
         log.warn("Security scan blocked support file save '{}/{}': {}", skillName, filePath, scanError);
         throw new SecurityException(scanError);
     }
     Path dir = getSkillsDir().resolve(skillName);
     Path target = dir.resolve(filePath);
     try {
         Files.createDirectories(target.getParent());
         Files.writeString(target, content);
         log.debug("Wrote support file: {}/{}", skillName, filePath);
     } catch (IOException e) {
         throw new RuntimeException("Failed to write support file: " + e.getMessage(), e);
     }
 }

 // S3: Remove support file
 @Override
 public boolean removeSupportFile(String skillName, String filePath) {
 validateSupportFilePath(filePath);
 Path target = getSkillsDir().resolve(skillName).resolve(filePath);
 try {
 return Files.deleteIfExists(target);
 } catch (IOException e) {
 log.warn("Failed to remove support file {}/{}: {}", skillName, filePath, e.getMessage());
 return false;
 }
 }

 // S3: Read support file
 @Override
 public String readSupportFile(String skillName, String filePath) {
 validateSupportFilePath(filePath);
 Path target = getSkillsDir().resolve(skillName).resolve(filePath);
 try {
 return Files.exists(target) ? Files.readString(target) : null;
 } catch (IOException e) {
 return null;
 }
 }

 // S3: List support files
 @Override
 public List<String> listSupportFiles(String skillName) {
 Path dir = getSkillsDir().resolve(skillName);
 if (!Files.isDirectory(dir)) return List.of();
 List<String> result = new ArrayList<>();
 for (String subdir : ALLOWED_SUBDIRS) {
 Path sub = dir.resolve(subdir);
 if (Files.isDirectory(sub)) {
 try (Stream<Path> stream = Files.walk(sub, 3)) {
 stream.filter(Files::isRegularFile)
 .forEach(p -> result.add(dir.relativize(p).toString().replace('\\', '/')));
 } catch (IOException e) {
 log.debug("Failed to walk {}/{}", skillName, subdir);
 }
 }
 }
 return result;
 }

 // S2: Archive
 @Override
 public boolean archiveSkill(String name) {
 return skillRepository.findByName(name).map(e -> {
 e.setArchived(true);
 e.setUpdatedAt(Instant.now());
 skillRepository.save(e);
 log.info("Archived skill: {}", name);
 return true;
 }).orElse(false);
 }

 // S2: Unarchive
 @Override
 public boolean unarchiveSkill(String name) {
 return skillRepository.findByName(name).map(e -> {
 e.setArchived(false);
 e.setUpdatedAt(Instant.now());
 skillRepository.save(e);
 log.info("Unarchived skill: {}", name);
 return true;
 }).orElse(false);
 }

 @Override
 public void reload() {
 // Database is always live — no cache to invalidate, but force a query to verify connectivity
 log.info("Reloading skills from database: {} active skills", listSkillNames().size());
 }

 private Path getSkillsDir() {
     // Skills are stored in the project directory: <project>/skills/
     // This keeps them versioned with the codebase and separate from Hermes.
     if (properties != null && properties.getCore() != null) {
         String wd = properties.getCore().getWorkingDirectory();
         if (wd != null && !wd.isBlank()) {
             return Path.of(wd, "skills");
         }
     }
     // Fall back to user home ~/.java-agent/skills if workdir not set
     String userHome = System.getProperty("user.home");
     if (userHome != null && !userHome.isBlank()) {
         Path agentSkills = Path.of(userHome, ".java-agent", "skills");
         if (Files.isDirectory(agentSkills)) {
             return agentSkills;
         }
     }
     return Path.of("skills");
 }

 // ─── P1-9: Validation helpers (ported from the original project's skill_manager_tool.py) ───

 /**
 * Validate a skill name. Returns error message or {@code null} if valid.
 */
 private static String validateName(String name) {
 if (name == null || name.isBlank()) {
 return "Skill name is required.";
 }
 if (name.length() > MAX_NAME_LENGTH) {
 return "Skill name exceeds " + MAX_NAME_LENGTH + " characters.";
 }
 if (!VALID_NAME_RE.matcher(name).matches()) {
 return "Invalid skill name '" + name + "'. Use lowercase letters, numbers, " +
 "hyphens, dots, and underscores. Must start with a letter or digit.";
 }
 return null;
 }

 /**
 * Validate that SKILL.md content has proper YAML frontmatter with required fields.
 * Returns error message or {@code null} if valid.
 */
 private static String validateFrontmatter(String content) {
     if (content == null || content.isBlank()) {
         return "Content cannot be empty.";
     }
     // h79: Strip a leading UTF-8 BOM (\uFEFF) before validating frontmatter.
     if (content.startsWith("\uFEFF")) {
         content = content.substring(1);
     }
     if (!content.startsWith("---")) {
 return "SKILL.md must start with YAML frontmatter (---). See existing skills for format.";
 }
 // Find closing ---
 int endIdx = content.indexOf("\n---", 3);
 if (endIdx < 0) {
 // Try without newline (content might be just "---\n---")
 return "SKILL.md frontmatter is not closed. Ensure you have a closing '---' line.";
 }
 String yamlContent = content.substring(3, endIdx).trim();

 // Check for required 'name' field
 if (!yamlContent.contains("name:")) {
 return "Frontmatter must include 'name' field.";
 }
 // Check for required 'description' field
 if (!yamlContent.contains("description:")) {
 return "Frontmatter must include 'description' field.";
 }

 // Check body after frontmatter
 int bodyStart = endIdx + 4; // skip "\n---"
 if (bodyStart < content.length()) {
 String body = content.substring(bodyStart).strip();
 if (body.isEmpty()) {
 return "SKILL.md must have content after the frontmatter (instructions, procedures, etc.).";
 }
 }

 return null;
 }

 /**
 * Check that content doesn't exceed the character limit for agent writes.
 */
 private static String validateContentSize(String content) {
 if (content == null) return "Content cannot be null.";
 if (content.length() > MAX_SKILL_CONTENT_CHARS) {
 return "SKILL.md content is " + content.length() + " characters " +
 "(limit: " + MAX_SKILL_CONTENT_CHARS + "). " +
 "Consider splitting into a smaller SKILL.md with supporting files " +
 "in references/ or templates/.";
 }
 return null;
 }

 /**
 * Determine the trust level for a skill being saved.
 * Existing skills keep their trust level; new skills default to AGENT_CREATED.
 */
 private TrustLevel determineTrustLevelForSave(String name) {
 return skillRepository.findByName(name)
 .map(e -> {
 String tl = e.getTrustLevel();
 if (tl != null) {
 try { return TrustLevel.valueOf(tl); }
 catch (IllegalArgumentException ex) { log.warn("Unknown trust level '{}' in database, defaulting to AGENT_CREATED", tl); }
 }
 return TrustLevel.AGENT_CREATED;
 })
 .orElse(TrustLevel.AGENT_CREATED);
 }

 // S3: Validate file path — only references/, templates/, scripts/, assets/
 private void validateSupportFilePath(String filePath) {
 if (filePath == null || filePath.isBlank()) {
 throw new IllegalArgumentException("File path must not be blank");
 }
 String normalized = filePath.replace('\\', '/');
 if (normalized.contains("..")) {
 throw new IllegalArgumentException("Path traversal not allowed: " + filePath);
 }
 boolean valid = ALLOWED_SUBDIRS.stream().anyMatch(normalized::startsWith);
 if (!valid) {
 throw new IllegalArgumentException(
 "Support file must be under one of: " + String.join(", ", ALLOWED_SUBDIRS) +
 ". Got: '" + filePath + "'"
 );
 }
 }
}
package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SIMPLIFIED PORT of Hermes tools/skills_hub.py (core functionality only).
 *
 * Where Hermes routes through NINE sources (official optional-skills index,
 * hermes-index, skills.sh, well-known, direct URL, GitHub search, ClawHub,
 * LobeHub, browse.sh) with auth, taps and pinning, this simplified version
 * uses ONE configurable GitHub repository as the skill source:
 *   agent.skills.hub-repo (default https://github.com/FerrPOINT/skills).
 *
 * Kept from Hermes:
 *  - install = fetch SKILL.md + references/ templates/ scripts/ subdirs
 *  - security scan (MemoryThreatScanner) BEFORE install, block on findings
 *  - path traversal protection, size caps, overwrite confirmation
 * Dropped (simplification): multi-source routing, GitHub auth, taps,
 * pinning, category prompts, provenance ledger.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillsHubService {

 private final SkillManager skillManager;
 private final AgentProperties properties;
 private final MemoryThreatScanner threatScanner;

 private static final Pattern PATH_TRAVERSAL = Pattern.compile("\\.\\.[/\\\\]");
 private static final int MAX_FILE_SIZE = 100_000;
 private static final int MAX_DESCRIPTION_LENGTH = 120;

 /** SIMPLIFIED: single source of truth (Hermes routes 9 sources). */
 public static final String DEFAULT_HUB_REPO = "https://github.com/FerrPOINT/skills";

 private String hubRepo() {
  String configured = properties.getSkills() != null ? properties.getSkills().getHubRepo() : null;
  return configured != null && !configured.isBlank() ? configured : DEFAULT_HUB_REPO;
 }

 /** Optional token for PRIVATE hub repos (env HUB_GITHUB_TOKEN or agent.skills.hub-token). */
 private String hubToken() {
  String fromProps = properties.getSkills() != null ? properties.getSkills().getHubToken() : null;
  if (fromProps != null && !fromProps.isBlank()) return fromProps;
  String fromEnv = System.getenv("HUB_GITHUB_TOKEN");
  return fromEnv != null && !fromEnv.isBlank() ? fromEnv : null;
 }

 // S6 FIX: Use Jackson ObjectMapper for JSON parsing
 private final ObjectMapper objectMapper = new ObjectMapper();

 /**
 * SIMPLIFIED: list skills from the configured hub repo (default FerrPOINT/skills).
 */
 public List<RemoteSkillInfo> listRemoteSkills() {
  return listRemoteSkills(hubRepo());
 }

 /**
 * SIMPLIFIED: search by substring over names + SKILL.md descriptions.
 * (Hermes searches across 9 sources with server-side search APIs.)
 */
 public List<RemoteSkillInfo> searchRemoteSkills(String query) {
  String q = query == null ? "" : query.toLowerCase();
  List<RemoteSkillInfo> all = new ArrayList<>();
  String repoUrl = hubRepo();
  for (RemoteSkillInfo info : listRemoteSkills(repoUrl)) {
   String description = fetchSkillDescription(repoUrl, info.name());
   if (q.isBlank() || info.name().toLowerCase().contains(q) || description.toLowerCase().contains(q)) {
    all.add(new RemoteSkillInfo(info.name(), description, info.url()));
   }
  }
  return all;
 }

 private String fetchSkillDescription(String repoUrl, String skillName) {
  String rawUrl = repoUrlToRawUrl(repoUrl) + "/" + skillName + "/SKILL.md";
  String content = fetchQuietly(rawUrl);
  if (content == null) return "";
  // front-matter description: or first non-heading, non-empty line
  for (String line : content.split("\n", 30)) {
   String t = line.trim();
   if (t.startsWith("description:")) return t.substring("description:".length()).trim();
  }
  for (String line : content.split("\n", 15)) {
   String t = line.strip();
   if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("---")) return t.length() > MAX_DESCRIPTION_LENGTH ? t.substring(0, MAX_DESCRIPTION_LENGTH) + "…" : t;
  }
  return "";
 }

 private String fetchQuietly(String url) {
  try {
   return fetchUrl(url);
  } catch (Exception e) {
   return null;
  }
 }

 /**
 * S11: List available skills from a remote repo.
 * Fetches the repo's contents via GitHub API.
 */
 public List<RemoteSkillInfo> listRemoteSkills(String repoUrl) {
 try {
 String apiUrl = repoUrlToApiUrl(repoUrl);
 String json = fetchUrl(apiUrl);
 if (json == null || json.isBlank()) {
 return List.of();
 }

 // S6 FIX: Parse JSON using Jackson ObjectMapper
 List<RemoteSkillInfo> skills = new ArrayList<>();
 JsonNode root = objectMapper.readTree(json);
 if (root.isArray()) {
 for (JsonNode item : root) {
 JsonNode nameNode = item.get("name");
 if (nameNode != null && !nameNode.isNull()) {
 String name = nameNode.asText();
 if (!name.endsWith(".md") && !name.equals("README.md") && !name.equals(".gitignore")) {
 JsonNode typeNode = item.get("type");
 String type = typeNode != null ? typeNode.asText() : "";
 // Only include directories (type=dir) as skills
 if ("dir".equals(type) || type.isEmpty()) {
 skills.add(new RemoteSkillInfo(name, "", ""));
 }
 }
 }
 }
 }
 return skills;
 } catch (Exception e) {
 log.warn("Failed to list remote skills from {}: {}", repoUrl, e.getMessage());
 return List.of();
 }
 }

 /**
 * S11: Install a remote skill by name from a repo URL.
 */
 public InstallResult install(String repoUrl, String skillName, boolean overwrite) {
 try {
 // Check if skill already exists
 if (!overwrite && skillManager.getSkill(skillName) != null) {
 return InstallResult.fail("Skill '" + skillName + "' already exists. Use overwrite=true to replace.");
 }

 // Fetch SKILL.md from the repo
 String rawUrl = repoUrlToRawUrl(repoUrl) + "/" + skillName + "/SKILL.md";
 String content = fetchUrl(rawUrl);

 if (content == null || content.isBlank()) {
 return InstallResult.fail("SKILL.md not found at: " + rawUrl);
 }

 // S12: Scan for threats before installing
 MemoryThreatScanner.ScanResult scanResult = threatScanner.scanDetailed(content);
 if (threatScanner.shouldBlock("COMMUNITY", scanResult)) {
 log.warn("Blocked skill installation '{}' — dangerous patterns: {}", skillName, scanResult.findings());
 return InstallResult.fail("Blocked: dangerous patterns detected: " + scanResult.findings());
 }

 // S6: Save with HUB_INSTALL origin
 skillManager.saveSkill(skillName, content, WriteOrigin.HUB_INSTALL);

 // S11: Try to fetch support files (references/, templates/, scripts/)
 int supportFiles = 0;
 for (String subdir : List.of("references", "templates", "scripts")) {
 String dirListingUrl = repoUrlToApiUrl(repoUrl) + "/" + skillName + "/" + subdir;
 try {
 String dirListing = fetchUrl(dirListingUrl);
 if (dirListing != null && !dirListing.isBlank()) {
 // S6 FIX: Parse JSON using Jackson
 JsonNode root = objectMapper.readTree(dirListing);
 if (root.isArray()) {
 for (JsonNode item : root) {
 JsonNode nameNode = item.get("name");
 if (nameNode == null || nameNode.isNull()) continue;
 String fileName = nameNode.asText();
 if (PATH_TRAVERSAL.matcher(fileName).find()) continue;
 String fileUrl = rawUrl.replace("/SKILL.md", "/" + subdir + "/" + fileName);
 try {
 String fileContent = fetchUrl(fileUrl);
 if (fileContent != null && fileContent.length() <= MAX_FILE_SIZE) {
 skillManager.writeSupportFile(skillName, subdir + "/" + fileName, fileContent);
 supportFiles++;
 }
 } catch (Exception e) {
 log.debug("Failed to fetch support file {}/{}: {}", subdir, fileName, e.getMessage());
 }
 }
 }
 }
 } catch (Exception e) {
 log.debug("No {} directory for skill {}", subdir, skillName);
 }
 }

 log.info("Installed skill '{}' from {} ({} support files)", skillName, repoUrl, supportFiles);
 return InstallResult.ok("Skill '" + skillName + "' installed successfully (" + supportFiles + " support files).");
 } catch (Exception e) {
 log.error("Failed to install skill '{}' from {}: {}", skillName, repoUrl, e.getMessage());
 return InstallResult.fail("Installation failed: " + e.getMessage());
 }
 }

 /**
 * S11: Uninstall a skill.
 */
 public boolean uninstall(String skillName) {
 boolean deleted = skillManager.deleteSkill(skillName);
 if (deleted) {
 log.info("Uninstalled skill '{}'", skillName);
 }
 return deleted;
 }

 /**
 * Convert GitHub repo URL to raw content URL.
 */
 private String repoUrlToRawUrl(String repoUrl) {
 // https://github.com/user/repo -> https://raw.githubusercontent.com/user/repo/main
 String url = repoUrl.replace("github.com", "raw.githubusercontent.com");
 if (!url.endsWith("/main") && !url.endsWith("/master")) {
 url = url + "/main";
 }
 return url;
 }

 /**
 * Convert GitHub repo URL to API URL.
 */
 private String repoUrlToApiUrl(String repoUrl) {
 // https://github.com/user/repo -> https://api.github.com/repos/user/repo/contents
 String url = repoUrl.replace("github.com", "api.github.com/repos");
 if (!url.endsWith("/contents")) {
 url = url + "/contents";
 }
 return url;
 }

 /**
 * Fetch a URL and return the body as string.
 */
 private String fetchUrl(String url) throws IOException, InterruptedException {
 try (HttpClient client = HttpClient.newHttpClient()) {
 HttpRequest.Builder rb = HttpRequest.newBuilder()
 .uri(URI.create(url))
 .header("Accept", "application/vnd.github.v3+json")
 .header("User-Agent", "JavaAgent/1.0")
 .GET();
 String token = hubToken();
 if (token != null) {
  rb.header("Authorization", "token " + token);
 }
 HttpRequest request = rb.build();
 HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
 if (response.statusCode() == 200) {
 return response.body();
 }
 log.debug("Fetch {} returned status {}", url, response.statusCode());
 return null;
 }
 }

 /**
 * S11: Remote skill info.
 */
 public record RemoteSkillInfo(String name, String description, String url) {}

 /**
 * S11: Install result.
 */
 public record InstallResult(boolean success, String message) {
 public static InstallResult ok(String msg) { return new InstallResult(true, msg); }
 public static InstallResult fail(String msg) { return new InstallResult(false, msg); }
 }
}
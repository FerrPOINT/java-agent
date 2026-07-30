package com.azhukov.agent.core.skill;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.memory.MemoryThreatScanner;
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
 * S11: Skills Hub — remote skill installation.
 * Fetches skill content from a configurable GitHub repo URL and installs locally.
 * Includes path traversal protection and overwrite confirmation.
 * <p>
 * Simplified port of Hermes' skills_hub.py (3888 lines → core functionality only).
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

    /**
     * S11: List available skills from a remote repo.
     * Fetches the repo's contents via GitHub API.
     */
    public List<RemoteSkillInfo> listRemoteSkills(String repoUrl) {
        try {
            String apiUrl = repoUrlToApiUrl(repoUrl);
            String json = fetchUrl(apiUrl);
            // Simple JSON parsing — look for "name" fields
            List<RemoteSkillInfo> skills = new ArrayList<>();
            // Very basic parsing: find "name":"xxx" patterns
            var matcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            while (matcher.find()) {
                String name = matcher.group(1);
                if (!name.endsWith(".md") && !name.equals("README.md") && !name.equals(".gitignore")) {
                    skills.add(new RemoteSkillInfo(name, "", ""));
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
                String dirUrl = repoUrlToRawUrl(repoUrl) + "/" + skillName + "/" + subdir;
                try {
                    String dirListing = fetchUrl(repoUrlToApiUrl(repoUrl) + "/" + skillName + "/" + subdir);
                    if (dirListing != null && dirListing.contains("\"name\"")) {
                        var matcher = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(dirListing);
                        while (matcher.find()) {
                            String fileName = matcher.group(1);
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
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "JavaAgent/1.0")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return response.body();
        }
        log.debug("Fetch {} returned status {}", url, response.statusCode());
        return null;
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
package com.azhukov.agent.core.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stage 15: Coding Context Detection.
 * <p>
 * Detects the coding context of a working directory by examining common project files
 * (pom.xml, build.gradle, package.json, requirements.txt, etc.).
 */
@Component
@Slf4j
public class CodingContextDetector {

    /**
     * Detected coding context of a project directory.
     */
    public record CodingContext(String language, String framework, String buildTool, boolean isGitRepo) {
        public static CodingContext empty() {
            return new CodingContext(null, null, null, false);
        }
    }

    /**
     * Detect the coding context from a working directory.
     *
     * @param workingDir the working directory to inspect
     * @return a {@link CodingContext} with detected values, or {@link CodingContext#empty()} if nothing was found
     */
    public CodingContext detect(String workingDir) {
        if (workingDir == null || workingDir.isBlank()) {
            return CodingContext.empty();
        }

        Path dir = Path.of(workingDir);
        if (!Files.isDirectory(dir)) {
            return CodingContext.empty();
        }

        boolean isGitRepo = Files.isDirectory(dir.resolve(".git"));

        String language = null;
        String framework = null;
        String buildTool = null;

        // Check for Maven (pom.xml)
        if (Files.exists(dir.resolve("pom.xml"))) {
            language = "Java";
            buildTool = "Maven";
        }

        // Check for Gradle (build.gradle or build.gradle.kts)
        if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
            language = "Java";
            buildTool = "Gradle";
        }

        // Check for Node.js (package.json)
        Path packageJson = dir.resolve("package.json");
        if (Files.exists(packageJson)) {
            language = "JavaScript/TypeScript";
            buildTool = "npm";
            framework = detectNodeFramework(packageJson);
        }

        // Check for Python (requirements.txt, setup.py, pyproject.toml)
        if (Files.exists(dir.resolve("requirements.txt"))
                || Files.exists(dir.resolve("setup.py"))
                || Files.exists(dir.resolve("pyproject.toml"))) {
            language = "Python";
            buildTool = "pip";
        }

        // Check for Dockerfile (note only, doesn't override language)
        if (Files.exists(dir.resolve("Dockerfile"))) {
            log.debug("Dockerfile found in {}", workingDir);
        }

        // Check for CI/CD workflows (.github/workflows)
        Path workflowsDir = dir.resolve(".github").resolve("workflows");
        if (Files.isDirectory(workflowsDir)) {
            log.debug("CI/CD workflows found in {}", workflowsDir);
        }

        return new CodingContext(language, framework, buildTool, isGitRepo);
    }

    /**
     * Detect the JS/TS framework from package.json contents.
     */
    private String detectNodeFramework(Path packageJson) {
        try {
            String content = Files.readString(packageJson);
            String lower = content.toLowerCase();
            if (lower.contains("react")) {
                return "react";
            }
            if (lower.contains("vue")) {
                return "vue";
            }
            if (lower.contains("angular")) {
                return "angular";
            }
            if (lower.contains("next")) {
                return "next";
            }
            if (lower.contains("express")) {
                return "express";
            }
        } catch (IOException e) {
            log.warn("Failed to read package.json for framework detection: {}", e.getMessage());
        }
        return null;
    }
}
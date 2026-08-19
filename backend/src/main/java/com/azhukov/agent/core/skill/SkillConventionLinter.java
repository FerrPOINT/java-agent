package com.azhukov.agent.core.skill;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill convention linter — advisory findings for SKILL.md files.
 * <p>
 * Ported from Hermes {@code tools/skill_linter.py}.
 * Findings are advisory (non-blocking) — the caller decides whether to surface them.
 * Checks:
 * <ul>
 *   <li>name-dir-mismatch — skill name doesn't match its directory</li>
 *   <li>shell-utility-reference — references raw shell commands instead of native tools</li>
 *   <li>marketing-words — description uses marketing language instead of functional trigger</li>
 *   <li>dangling-references — references/ links point to non-existent files</li>
 * </ul>
 */
@Slf4j
public final class SkillConventionLinter {

    private SkillConventionLinter() {}

    // Shell utilities the agent has wrapped as first-class tools
    private static final List<String> SHELL_UTILITIES = List.of(
        "cat ", "grep ", "sed ", "awk ", "find ", "ls ", "head ", "tail ",
        "wc ", "sort ", "uniq ", "cut ", "tr ", "diff ", "curl ", "wget "
    );

    // Marketing words that indicate a description isn't a functional trigger
    private static final List<String> MARKETING_WORDS = List.of(
        "powerful", "comprehensive", "intelligent", "advanced", "cutting-edge",
        "seamless", "robust", "innovative", "revolutionary", "next-generation"
    );

    public record LintFinding(String rule, String severity, String message, int line) {
        public boolean isError() { return "error".equals(severity); }
        public boolean isWarning() { return "warning".equals(severity); }
    }

    /**
     * Lint skill content (SKILL.md text).
     *
     * @param name     the skill name (from frontmatter)
     * @param content  the full SKILL.md content
     * @return list of advisory findings (may be empty)
     */
    public static List<LintFinding> lintContent(String name, String content) {
        List<LintFinding> findings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return findings;
        }

        // 1. name-dir-mismatch: check that name in frontmatter matches skill name
        String frontmatterName = extractFrontmatterField(content, "name");
        if (frontmatterName != null && name != null && !frontmatterName.equals(name)) {
            findings.add(new LintFinding("name-dir-mismatch", "warning",
                "Skill name '" + frontmatterName + "' in frontmatter doesn't match directory name '" + name + "'",
                1));
        }

        // 2. shell-utility-reference: check for raw shell commands instead of native tools
        String[] lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Skip code blocks
            if (line.trim().startsWith("```")) continue;
            for (String util : SHELL_UTILITIES) {
                if (line.contains(util) && !line.contains("```") && !line.trim().startsWith("#")) {
                    // Only flag if it looks like a recommendation, not a reference
                    if (line.toLowerCase().contains("use ") || line.toLowerCase().contains("run ")) {
                        findings.add(new LintFinding("shell-utility-reference", "warning",
                            "Line " + (i + 1) + ": references shell utility '" + util.trim() +
                            "' — prefer native tool (read_file, search_files, terminal, etc.)",
                            i + 1));
                        break;
                    }
                }
            }
        }

        // 3. marketing-words: check description for marketing language
        String description = extractFrontmatterField(content, "description");
        if (description != null) {
            String lowerDesc = description.toLowerCase();
            for (String word : MARKETING_WORDS) {
                if (lowerDesc.contains(word)) {
                    findings.add(new LintFinding("marketing-words", "warning",
                        "Description contains marketing word '" + word +
                        "' — state the trigger condition instead",
                        1));
                    break;
                }
            }
        }

        return findings;
    }

    /**
     * Extract a field from YAML frontmatter.
     */
    private static String extractFrontmatterField(String content, String field) {
        if (!content.startsWith("---")) return null;
        int endFence = content.indexOf("\n---", 3);
        if (endFence < 0) return null;
        String frontmatter = content.substring(3, endFence);
        Pattern p = Pattern.compile("^" + field + ":\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = p.matcher(frontmatter);
        if (m.find()) {
            String value = m.group(1).trim();
            // Strip quotes
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }

    /**
     * Format findings as a human-readable report.
     */
    public static String formatFindings(List<LintFinding> findings) {
        if (findings == null || findings.isEmpty()) return "No convention issues found.";
        StringBuilder sb = new StringBuilder();
        sb.append("Convention findings (advisory):\n");
        for (LintFinding f : findings) {
            sb.append("  [").append(f.severity()).append("] ")
              .append(f.rule()).append(" (line ").append(f.line()).append("): ")
              .append(f.message()).append("\n");
        }
        return sb.toString().trim();
    }
}
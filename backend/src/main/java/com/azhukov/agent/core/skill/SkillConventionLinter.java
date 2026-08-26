package com.azhukov.agent.core.skill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill convention linter — advisory findings for SKILL.md files.
 * <p>
 * Full port of Hermes {@code tools/skill_linter.py} (all 10 rules).
 * The hard validator in {@code DatabaseSkillManager.validateFrontmatter} guards the
 * non-negotiables and is a create/edit BLOCKER; this class is the softer, broader
 * companion encoding the "Skill authoring standards" conventions:
 * shell-utility references instead of native tools, missing author/license/metadata,
 * name that doesn't match its directory, dangling references/ links, marketing words,
 * platforms: gating vs POSIX-only primitives, forbidden scaffolding files,
 * missing "When to Use" section, name format, platforms values.
 * <p>
 * Findings are advisory (non-blocking) — the caller decides whether to surface them.
 */
public final class SkillConventionLinter {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    /** Hermes parity: SKILL_PROMPT_DESC_LIMIT (skill_utils.py:872). */
    static final int SKILL_PROMPT_DESC_LIMIT = 60;

    // ── Rule data (skill_linter.py:42-89) ──

    /** Banned shell-utility tokens in prose → the native tool to name instead. */
    private static final Map<String, String> SHELL_UTIL_TO_TOOL = Map.of(
        "grep", "search_files",
        "rg", "search_files",
        "cat", "read_file",
        "head", "read_file",
        "tail", "read_file",
        "sed", "patch",
        "awk", "patch",
        "find", "search_files (target='files')",
        "ls", "search_files (target='files')"
    );

    /** Marketing words the description must not contain (rule 1). */
    private static final List<String> MARKETING_WORDS = List.of(
        "powerful", "comprehensive", "seamless", "advanced", "cutting-edge",
        "state-of-the-art", "revolutionary", "robust"
    );

    /** POSIX-only primitives that require platforms: gating (rule 3). */
    private static final List<String> POSIX_PRIMITIVES = List.of(
        "fcntl", "termios", "os.setsid", "signal.SIGKILL",
        "osascript", "/proc/", "apt-get", "systemctl"
    );

    /** Scaffolding files a skill should not ship. */
    private static final List<String> FORBIDDEN_FILES = List.of(
        "README.md", "CHANGELOG.md", "install.sh", ".env", ".env.example", ".gitignore"
    );

    /** Load-bearing sections we check for presence (rule 5). */
    private static final List<String> EXPECTED_SECTIONS = List.of("When to Use", "When to use");

    private static final Pattern FENCE_RE = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern NAME_FORMAT_RE = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern SECTION_RE = Pattern.compile("^#+\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern REFERENCE_LINK_RE = Pattern.compile("(references|templates|assets)/[\\w./-]+");

    private SkillConventionLinter() {
    }

    public record LintFinding(String rule, String severity, String message, int line) {
        public boolean isError() { return ERROR.equals(severity); }
        public boolean isWarning() { return WARNING.equals(severity); }

        /** Hermes parity: LintFinding.format() — "✗ [rule] message" / "⚠ [rule] message". */
        public String format() {
            String badge = isError() ? "✗" : "⚠";
            return badge + " [" + rule() + "] " + message();
        }
    }

    /**
     * Lint raw SKILL.md content with all content-only checks.
     * Mirrors Hermes {@code lint_content}.
     *
     * @param name    the skill directory name (for name/dir match), or null
     * @param content the full SKILL.md content
     * @return advisory findings, never null
     */
    public static List<LintFinding> lintContent(String name, String content) {
        List<LintFinding> findings = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return findings;
        }
        Map<String, String> frontmatter = parseFrontmatter(content);
        String body = stripFrontmatter(content);
        String prose = stripCodeBlocks(body);

        findings.addAll(checkNameFormat(frontmatter));
        findings.addAll(checkNameMatchesDir(frontmatter, name));
        findings.addAll(checkDescription(frontmatter));
        findings.addAll(checkMetadataBlock(frontmatter));
        findings.addAll(checkPlatformListValid(frontmatter));
        findings.addAll(checkShellUtilities(prose));
        findings.addAll(checkSections(body));
        return findings;
    }

    // ── Individual checks ──

    private static List<LintFinding> checkNameFormat(Map<String, String> fm) {
        String name = fm.getOrDefault("name", "").trim();
        if (name.isEmpty()) return List.of();
        if (!NAME_FORMAT_RE.matcher(name).matches()) {
            return List.of(new LintFinding("name-format", ERROR,
                "name '" + name + "' must be lowercase letters, digits, hyphens, and underscores only.", 1));
        }
        return List.of();
    }

    private static List<LintFinding> checkNameMatchesDir(Map<String, String> fm, String dirName) {
        if (dirName == null) return List.of();
        String name = fm.getOrDefault("name", "").trim();
        if (name.isEmpty()) return List.of();
        if (!name.equals(dirName)) {
            return List.of(new LintFinding("name-dir-mismatch", ERROR,
                "frontmatter name '" + name + "' does not match directory '" + dirName + "'; they must be identical.", 1));
        }
        return List.of();
    }

    private static List<LintFinding> checkDescription(Map<String, String> fm) {
        List<LintFinding> findings = new ArrayList<>();
        String desc = stripQuotes(fm.getOrDefault("description", "").trim());
        if (desc.isEmpty()) return findings;
        if (desc.length() > SKILL_PROMPT_DESC_LIMIT) {
            findings.add(new LintFinding("description-length", WARNING,
                "description is " + desc.length() + " chars; the skill index truncates past "
                    + SKILL_PROMPT_DESC_LIMIT + " chars + '...', losing routing signal. Keep it to one sentence.", 1));
        }
        String lower = desc.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (String w : MARKETING_WORDS) {
            if (lower.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) hits.add(w);
        }
        if (!hits.isEmpty()) {
            findings.add(new LintFinding("description-marketing", WARNING,
                "description contains marketing words " + hits + "; state the capability, not adjectives.", 1));
        }
        return findings;
    }

    private static List<LintFinding> checkMetadataBlock(Map<String, String> fm) {
        List<LintFinding> findings = new ArrayList<>();
        for (String key : List.of("version", "author", "license")) {
            if (!fm.containsKey(key)) {
                findings.add(new LintFinding("missing-metadata", WARNING,
                    "frontmatter is missing '" + key + "'; every peer skill has it.", 1));
            }
        }
        // metadata.hermes.{tags, related_skills} — flattened key check.
        // Hermes: missing metadata.hermes dict → both findings; else check tags.
        boolean hasHermesMeta = fm.keySet().stream().anyMatch(k -> k.startsWith("metadata.hermes."));
        if (!hasHermesMeta) {
            findings.add(new LintFinding("missing-metadata", WARNING,
                "frontmatter is missing metadata.hermes.{tags, related_skills}.", 1));
        } else if (!fm.containsKey("metadata.hermes.tags")) {
            findings.add(new LintFinding("missing-metadata", WARNING,
                "metadata.hermes.tags is missing.", 1));
        }
        String author = fm.getOrDefault("author", "").trim();
        String authorLower = author.toLowerCase(Locale.ROOT);
        if (!author.isEmpty() && List.of("hermes", "agent", "hermes agent").contains(authorLower)
            && !"Hermes Agent".equals(author)) {
            findings.add(new LintFinding("author-caps", WARNING,
                "author '" + author + "' should be 'Hermes Agent' (proper caps) or a real contributor name.", 1));
        }
        return findings;
    }

    private static List<LintFinding> checkPlatformListValid(Map<String, String> fm) {
        String platforms = fm.get("platforms");
        if (platforms == null || platforms.isBlank()) return List.of();
        List<String> valid = List.of("linux", "macos", "windows", "darwin");
        List<String> items = Arrays.stream(platforms.replaceAll("[\\[\\]]", "").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> bad = items.stream()
            .map(p -> p.toLowerCase(Locale.ROOT))
            .filter(p -> !valid.contains(p))
            .toList();
        if (!bad.isEmpty()) {
            return List.of(new LintFinding("platforms-value", WARNING,
                "platforms contains unrecognized value(s) " + bad + "; expected a subset of "
                    + List.of("darwin", "linux", "macos", "windows") + ".", 1));
        }
        return List.of();
    }

    /** Flag banned shell utilities named in PROSE (backtick-wrapped), not in code blocks. */
    private static List<LintFinding> checkShellUtilities(String prose) {
        List<LintFinding> findings = new ArrayList<>();
        for (var entry : SHELL_UTIL_TO_TOOL.entrySet()) {
            String util = entry.getKey();
            if (prose.contains("`" + util + "`")) {
                findings.add(new LintFinding("shell-utility-reference", WARNING,
                    "prose references `" + util + "`; name the native tool `" + entry.getValue() + "` instead.", 1));
            }
        }
        return findings;
    }

    private static List<LintFinding> checkSections(String body) {
        Matcher m = SECTION_RE.matcher(body);
        while (m.find()) {
            String heading = m.group(1);
            for (String expected : EXPECTED_SECTIONS) {
                if (heading.startsWith(expected)) return List.of();
            }
        }
        return List.of(new LintFinding("missing-section", WARNING,
            "no '## When to Use' section found; skills need explicit trigger conditions near the top.", 1));
    }

    // ── Helpers ──

    /** Parse YAML frontmatter into a flat map with dot-keys for nesting (metadata.hermes.tags). */
    static Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null || !content.startsWith("---")) return result;
        int endFence = content.indexOf("\n---", 3);
        if (endFence < 0) return result;
        String[] lines = content.substring(3, endFence).split("\n");
        // Track the key-path by indentation: each level appends to the parent path.
        java.util.Deque<String[]> path = new java.util.ArrayDeque<>(); // [indent, key]
        for (String line : lines) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).trim();
            String value = colon < trimmed.length() - 1 ? trimmed.substring(colon + 1).trim() : "";

            // Pop path entries whose indent is >= current (they are siblings or ended)
            while (!path.isEmpty() && Integer.parseInt(path.peek()[0]) >= indent) {
                path.pop();
            }
            // Build the full key from ROOT to LEAF — reverse the stack first
            StringBuilder fullKey = new StringBuilder();
            java.util.Iterator<String[]> it = path.descendingIterator();
            while (it.hasNext()) {
                fullKey.append(it.next()[1]).append('.');
            }
            fullKey.append(key);
            result.put(fullKey.toString(), value);
            if (value.isEmpty()) {
                // container key — push for nested children
                path.push(new String[]{String.valueOf(indent), key});
            }
        }
        return result;
    }

    static String stripFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) return content == null ? "" : content;
        int endFence = content.indexOf("\n---", 3);
        if (endFence < 0) return content;
        return content.substring(endFence + 4);
    }

    /** Remove fenced code blocks so prose-only checks don't fire on examples. */
    static String stripCodeBlocks(String body) {
        return FENCE_RE.matcher(body).replaceAll("");
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
            || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Hermes parity: has_errors — true if any ERROR-severity finding. */
    public static boolean hasErrors(List<LintFinding> findings) {
        return findings != null && findings.stream().anyMatch(LintFinding::isError);
    }

    /** Hermes parity: format_findings — newline-joined formatted findings. */
    public static String formatFindings(List<LintFinding> findings) {
        if (findings == null || findings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (LintFinding f : findings) {
            sb.append(f.format()).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
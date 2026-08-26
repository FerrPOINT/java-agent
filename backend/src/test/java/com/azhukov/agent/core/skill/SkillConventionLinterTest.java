package com.azhukov.agent.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermes parity tests for {@link SkillConventionLinter} — all 10 rules from
 * tools/skill_linter.py.
 */
class SkillConventionLinterTest {

    private static final String CLEAN_SKILL = """
        ---
        name: test-skill
        description: Use when porting Python linters to Java
        version: 1.0
        author: Hermes Agent
        license: MIT
        metadata:
          hermes:
            tags: [java, linting]
        ---
        # Test Skill

        ## When to Use
        Use this skill when a linter port is needed.
        """;

    @Test
    void cleanSkill_producesNoFindings() {
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", CLEAN_SKILL);
        assertThat(findings).isEmpty();
    }

    @Test
    void nameFormat_uppercaseIsError() {
        String content = CLEAN_SKILL.replace("name: test-skill", "name: TestSkill");
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("TestSkill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("name-format") && f.isError());
    }

    @Test
    void nameDirMismatch_isError() {
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("other-dir", CLEAN_SKILL);
        assertThat(findings).anyMatch(f -> f.rule().equals("name-dir-mismatch") && f.isError());
    }

    @Test
    void descriptionTooLong_isWarning() {
        String longDesc = "d".repeat(80);
        String content = CLEAN_SKILL.replace(
            "description: Use when porting Python linters to Java",
            "description: " + longDesc);
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("description-length") && f.isWarning());
    }

    @Test
    void descriptionMarketingWords_isWarning() {
        String content = CLEAN_SKILL.replace(
            "description: Use when porting Python linters to Java",
            "description: A powerful comprehensive solution for linting");
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("description-marketing") && f.isWarning());
    }

    @Test
    void missingMetadata_isWarning() {
        String content = """
            ---
            name: test-skill
            description: Use when porting linters
            ---
            # Test
            ## When to Use
            now
            """;
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("missing-metadata") && f.isWarning());
    }

    @Test
    void shellUtilityReference_inProseIsWarning() {
        String content = CLEAN_SKILL + "\nUse `grep` to find things in the repo.\n";
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("shell-utility-reference") && f.isWarning());
    }

    @Test
    void shellUtilityReference_inCodeBlockIsIgnored() {
        String content = CLEAN_SKILL + "\n```\ngrep -r pattern .\n```\n";
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).noneMatch(f -> f.rule().equals("shell-utility-reference"));
    }

    @Test
    void missingWhenToUseSection_isWarning() {
        String content = """
            ---
            name: test-skill
            description: Use when porting linters
            ---
            # Test Skill
            Just some content without trigger conditions.
            """;
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("missing-section") && f.isWarning());
    }

    @Test
    void invalidPlatformsValue_isWarning() {
        String content = CLEAN_SKILL.replace("license: MIT", "license: MIT\nplatforms: [linux, amiga]");
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("platforms-value") && f.isWarning());
    }

    @Test
    void validPlatforms_noFinding() {
        String content = CLEAN_SKILL.replace("license: MIT", "license: MIT\nplatforms: [linux, macos]");
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).noneMatch(f -> f.rule().equals("platforms-value"));
    }

    @Test
    void authorWrongCaps_isWarning() {
        String content = CLEAN_SKILL.replace("author: Hermes Agent", "author: hermes");
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        assertThat(findings).anyMatch(f -> f.rule().equals("author-caps") && f.isWarning());
    }

    @Test
    void hasErrors_detectsErrorSeverity() {
        List<SkillConventionLinter.LintFinding> findings =
            SkillConventionLinter.lintContent("mismatch-dir", CLEAN_SKILL);
        assertThat(SkillConventionLinter.hasErrors(findings)).isTrue();

        List<SkillConventionLinter.LintFinding> clean = SkillConventionLinter.lintContent("test-skill", CLEAN_SKILL);
        assertThat(SkillConventionLinter.hasErrors(clean)).isFalse();
    }

    @Test
    void formatFindings_usesHermesBadges() {
        String content = CLEAN_SKILL.replace("name: test-skill", "name: TestSkill");
        List<SkillConventionLinter.LintFinding> findings = SkillConventionLinter.lintContent("test-skill", content);
        String formatted = SkillConventionLinter.formatFindings(findings);
        assertThat(formatted).contains("✗ [name-format]");
        assertThat(formatted).contains("[name-dir-mismatch]");
    }

    @Test
    void emptyContent_returnsEmpty() {
        assertThat(SkillConventionLinter.lintContent("x", "")).isEmpty();
        assertThat(SkillConventionLinter.lintContent("x", null)).isEmpty();
    }
}
package com.azhukov.agent.core.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Hermes-sync bug fixes in SkillUtils:
 * - h79: SKILL.md BOM stripping
 */
class SkillUtilsHermesSyncTest {

    // ── h79: BOM stripping ──

    @Test
    void parseFrontmatterStripsBOM() {
        // Create content with BOM prefix
        String content = "\uFEFF---\nname: test-skill\ndescription: A test skill\n---\n# Test Skill\nContent here.";
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(content);
        assertNotNull(result.frontmatter());
        assertEquals("test-skill", result.frontmatter().get("name"));
        assertEquals("A test skill", result.frontmatter().get("description"));
    }

    @Test
    void parseFrontmatterWithoutBOMStillWorks() {
        String content = "---\nname: test-skill\ndescription: A test skill\n---\n# Test Skill\nContent here.";
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(content);
        assertNotNull(result.frontmatter());
        assertEquals("test-skill", result.frontmatter().get("name"));
    }

    @Test
    void parseFrontmatterNullContentHandled() {
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter(null);
        assertNotNull(result);
        assertTrue(result.frontmatter().isEmpty());
    }

    @Test
    void parseFrontmatterOnlyBOM() {
        SkillUtils.FrontmatterResult result = SkillUtils.parseFrontmatter("\uFEFF");
        assertNotNull(result);
        assertTrue(result.frontmatter().isEmpty());
    }
}
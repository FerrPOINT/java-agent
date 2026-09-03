package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebsitePolicyTest {

    private AgentProperties properties;
    private WebsitePolicy policy;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        policy = new WebsitePolicy(properties);
    }

    @Test
    @DisplayName("Should allow normal https URL when no restrictions")
    void shouldAllowNormalHttpsUrl() {
        assertNull(policy.checkAccess("https://example.com/page"));
    }

    @Test
    @DisplayName("Should allow normal http URL when no restrictions")
    void shouldAllowNormalHttpUrl() {
        assertNull(policy.checkAccess("http://example.com/page"));
    }

    @Test
    @DisplayName("Should reject non-http schemes")
    void shouldRejectNonHttpSchemes() {
        assertNotNull(policy.checkAccess("file:///etc/passwd"));
        assertNotNull(policy.checkAccess("ftp://example.com"));
        assertNotNull(policy.checkAccess("javascript:alert(1)"));
    }

    @Test
    @DisplayName("Should reject null URL")
    void shouldRejectNullUrl() {
        assertNotNull(policy.checkAccess(null));
    }

    @Test
    @DisplayName("Should reject blank URL")
    void shouldRejectBlankUrl() {
        assertNotNull(policy.checkAccess(""));
        assertNotNull(policy.checkAccess("   "));
    }

    @Test
    @DisplayName("Should reject URL without host")
    void shouldRejectUrlWithoutHost() {
        assertNotNull(policy.checkAccess("https:///path"));
    }

    @Test
    @DisplayName("Should block domains in blocked list")
    void shouldBlockBlockedDomains() {
        properties.getWeb().getBlockedDomains().add("malicious.com");

        String reason = policy.checkAccess("https://malicious.com/page");
        assertNotNull(reason);
        assertTrue(reason.contains("blocked"));
    }

    @Test
    @DisplayName("Should block subdomains of blocked domains")
    void shouldBlockSubdomainsOfBlocked() {
        properties.getWeb().getBlockedDomains().add("bad.com");

        assertNotNull(policy.checkAccess("https://sub.bad.com/page"));
        assertNotNull(policy.checkAccess("https://www.bad.com/page"));
    }

    @Test
    @DisplayName("Should support wildcard rules for subdomains only")
    void shouldSupportWildcardRules() {
        properties.getWeb().getBlockedDomains().add("*.bad.com");

        assertNull(policy.checkAccess("https://bad.com/page"));
        assertNotNull(policy.checkAccess("https://sub.bad.com/page"));
        assertNotNull(policy.checkAccess("https://deep.sub.bad.com/page"));
    }

    @Test
    @DisplayName("Should normalize URL-shaped and www-prefixed rules")
    void shouldNormalizeUrlShapedRules() {
        properties.getWeb().getBlockedDomains().add("https://www.malicious.com/some/path");

        assertNotNull(policy.checkAccess("https://malicious.com/page"));
        assertNotNull(policy.checkAccess("https://cdn.malicious.com/page"));
    }

    @Test
    @DisplayName("Should ignore blank and commented rules")
    void shouldIgnoreBlankAndCommentRules() {
        properties.getWeb().getBlockedDomains().add("  ");
        properties.getWeb().getBlockedDomains().add("# disabled.example");
        properties.getWeb().getAllowedDomains().add("  ");

        assertNull(policy.checkAccess("https://example.com/page"));
    }

    @Test
    @DisplayName("Should enforce allow-list when non-empty")
    void shouldEnforceAllowList() {
        properties.getWeb().getAllowedDomains().add("trusted.com");

        assertNull(policy.checkAccess("https://trusted.com/page"));
        assertNotNull(policy.checkAccess("https://untrusted.com/page"));
    }

    @Test
    @DisplayName("Should allow subdomains of allowed domains")
    void shouldAllowSubdomainsOfAllowed() {
        properties.getWeb().getAllowedDomains().add("trusted.com");

        assertNull(policy.checkAccess("https://api.trusted.com/page"));
        assertNull(policy.checkAccess("https://www.trusted.com/page"));
    }

    @Test
    @DisplayName("Should check blocked before allowed")
    void shouldCheckBlockedBeforeAllowed() {
        properties.getWeb().getAllowedDomains().add("example.com");
        properties.getWeb().getBlockedDomains().add("blocked.example.com");

        assertNotNull(policy.checkAccess("https://blocked.example.com/page"));
    }

    @Test
    @DisplayName("isAllowed should return true for allowed URLs")
    void isAllowedShouldReturnTrueForAllowed() {
        assertTrue(policy.isAllowed("https://example.com"));
    }

    @Test
    @DisplayName("isAllowed should return false for blocked URLs")
    void isAllowedShouldReturnFalseForBlocked() {
        properties.getWeb().getBlockedDomains().add("bad.com");
        assertFalse(policy.isAllowed("https://bad.com"));
    }

    @Test
    @DisplayName("Should reject invalid URL")
    void shouldRejectInvalidUrl() {
        assertNotNull(policy.checkAccess("ht tp://broken url"));
    }
}

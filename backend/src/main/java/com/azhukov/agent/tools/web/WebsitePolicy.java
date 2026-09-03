package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

/**
 * Internal access checker for web tools.
 * Determines whether a URL should be allowed based on domain allow/block lists
 * configured in agent.web.allowed-domains and agent.web.blocked-domains.
 *
 * If allowedDomains is non-empty, only those domains are permitted (allow-list mode).
 * If allowedDomains is empty, all domains are permitted except those in blockedDomains.
 */
@Component
@RequiredArgsConstructor
public class WebsitePolicy {

    private final AgentProperties properties;

    /**
     * Check if a URL is allowed by the website policy.
     * @param url the URL to check
     * @return null if allowed, a human-readable block reason if blocked
     */
    public String checkAccess(String url) {
        if (url == null || url.isBlank()) {
            return "URL is empty";
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "Invalid URL: " + e.getMessage();
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL has no host";
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return "Only http and https schemes are allowed";
        }

        String lowerHost = normalizeHost(host);

        // Check blocked domains first
        List<String> blocked = properties.getWeb().getBlockedDomains();
        if (blocked != null) {
            for (String b : blocked) {
                String rule = normalizeRule(b);
                if (rule != null && matchesDomain(lowerHost, rule)) {
                    return "Blocked by website policy: '" + lowerHost
                        + "' matched rule '" + rule + "' from agent.web.blocked-domains";
                }
            }
        }

        // If allowed domains list is non-empty, enforce allow-list mode
        List<String> allowed = properties.getWeb().getAllowedDomains();
        if (allowed != null && !allowed.isEmpty()) {
            boolean hasRules = false;
            boolean found = false;
            for (String a : allowed) {
                String rule = normalizeRule(a);
                if (rule == null) {
                    continue;
                }
                hasRules = true;
                if (matchesDomain(lowerHost, rule)) {
                    found = true;
                    break;
                }
            }
            if (hasRules && !found) {
                return "Blocked by website policy: '" + lowerHost
                    + "' is not in agent.web.allowed-domains";
            }
        }

        return null; // allowed
    }

    private String normalizeHost(String host) {
        return (host == null ? "" : host).strip().toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
    }

    private String normalizeRule(String rawRule) {
        if (rawRule == null) {
            return null;
        }
        String rule = rawRule.strip().toLowerCase(Locale.ROOT);
        if (rule.isBlank() || rule.startsWith("#")) {
            return null;
        }
        if (rule.contains("://")) {
            try {
                URI parsed = new URI(rule);
                if (parsed.getHost() != null && !parsed.getHost().isBlank()) {
                    rule = parsed.getHost();
                } else if (parsed.getRawAuthority() != null && !parsed.getRawAuthority().isBlank()) {
                    rule = parsed.getRawAuthority();
                }
            } catch (URISyntaxException e) {
                // Fall through to plain rule normalization.
            }
        }
        int slash = rule.indexOf('/');
        if (slash >= 0) {
            rule = rule.substring(0, slash);
        }
        int at = rule.lastIndexOf('@');
        if (at >= 0) {
            rule = rule.substring(at + 1);
        }
        if (!rule.startsWith("*.") && rule.startsWith("www.")) {
            rule = rule.substring(4);
        }
        rule = rule.replaceAll("\\.+$", "");
        return rule.isBlank() ? null : rule;
    }

    private boolean matchesDomain(String host, String rule) {
        if (host.isBlank() || rule == null || rule.isBlank()) {
            return false;
        }
        if (rule.startsWith("*.")) {
            String suffix = rule.substring(1); // includes leading dot
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(rule) || host.endsWith("." + rule);
    }

    /**
     * Convenience method returning true if the URL is allowed.
     */
    public boolean isAllowed(String url) {
        return checkAccess(url) == null;
    }
}

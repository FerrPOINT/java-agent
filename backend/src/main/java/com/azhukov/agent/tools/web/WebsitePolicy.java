package com.azhukov.agent.tools.web;

import com.azhukov.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

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

        String lowerHost = host.toLowerCase();

        // Check blocked domains first
        List<String> blocked = properties.getWeb().getBlockedDomains();
        if (blocked != null) {
            for (String b : blocked) {
                String bl = b.toLowerCase();
                if (lowerHost.equals(bl) || lowerHost.endsWith("." + bl)) {
                    return "Domain blocked by policy: " + host;
                }
            }
        }

        // If allowed domains list is non-empty, enforce allow-list mode
        List<String> allowed = properties.getWeb().getAllowedDomains();
        if (allowed != null && !allowed.isEmpty()) {
            boolean found = false;
            for (String a : allowed) {
                String al = a.toLowerCase();
                if (lowerHost.equals(al) || lowerHost.endsWith("." + al)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return "Domain not in allow-list: " + host;
            }
        }

        return null; // allowed
    }

    /**
     * Convenience method returning true if the URL is allowed.
     */
    public boolean isAllowed(String url) {
        return checkAccess(url) == null;
    }
}
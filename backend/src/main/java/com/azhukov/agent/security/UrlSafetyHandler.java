package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UrlSafety;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class UrlSafetyHandler {

    private final AgentProperties properties;
    private final UrlSafety urlSafety;

    public String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            return "URL is empty";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return "Invalid URL: " + e.getMessage();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return "Only http/https URLs are allowed";
        }
        if (uri.getHost() == null) {
            return "URL host is missing";
        }
        // Block URLs with embedded credentials
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return "URL with embedded credentials is not allowed: " + uri.getHost();
        }
        if (!properties.getSecurity().isUrlSafetyEnabled()) {
            return null;
        }
        // Delegate to UrlSafety for comprehensive checks (IP resolution, private ranges, etc.)
        if (!urlSafety.isUrlAllowed(url)) {
            return "URL blocked by safety policy: " + uri.getHost();
        }
        // Check web.blockedDomains (separate from security.blockedUrlHosts)
        if (properties.getWeb().getBlockedDomains().contains(uri.getHost())) {
            return "Domain is blocked: " + uri.getHost();
        }
        // Enforce HTTPS only when safety is enabled
        if (!"https".equalsIgnoreCase(scheme)) {
            return "Insecure transport (http) not allowed";
        }
        return null;
    }
}
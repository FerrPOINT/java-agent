package com.azhukov.agent.tools.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class SafetyGuard {

    private final AgentProperties properties;

    public SafetyGuard(AgentProperties properties) {
        this.properties = properties;
    }

    public boolean isUrlAllowed(String url) {
        if (!properties.getSecurity().isUrlSafetyEnabled()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        List<String> blockedHosts = properties.getSecurity().getBlockedUrlHosts();
        if (blockedHosts != null) {
            String lowerHost = host.toLowerCase();
            for (String blocked : blockedHosts) {
                if (lowerHost.equals(blocked.toLowerCase()) || lowerHost.endsWith("." + blocked.toLowerCase())) {
                    return false;
                }
            }
        }
        return true;
    }

    public String redact(String output) {
        if (!properties.getSecurity().isRedactEnabled() || output == null) {
            return output;
        }
        List<String> patterns = properties.getSecurity().getSecretPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return output;
        }
        String result = output;
        for (String regex : patterns) {
            try {
                result = result.replaceAll(regex, "[REDACTED]");
            } catch (Exception e) {
                // ignore invalid regex
            }
        }
        return result;
    }
}

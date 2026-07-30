package com.azhukov.agent.core.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DefaultUrlSafety implements UrlSafety {

    private final AgentProperties properties;


    @Override
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
        return !isHostBlocked(host);
    }

    @Override
    public boolean isHostBlocked(String host) {
        List<String> blockedHosts = properties.getSecurity().getBlockedUrlHosts();
        if (blockedHosts == null || host == null) {
            return false;
        }
        String lowerHost = host.toLowerCase();
        for (String blocked : blockedHosts) {
            String b = blocked.toLowerCase();
            if (lowerHost.equals(b) || lowerHost.endsWith("." + b)) {
                return true;
            }
        }
        return false;
    }
}
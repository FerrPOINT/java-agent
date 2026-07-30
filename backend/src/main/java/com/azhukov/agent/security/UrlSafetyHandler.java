package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UrlSafetyHandler {

    private final AgentProperties properties;


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
        if (!properties.getSecurity().isUrlSafetyEnabled()) {
            return null;
        }
        if (isBlockedHost(uri.getHost())) {
            return "Host is blocked: " + uri.getHost();
        }
        if (properties.getWeb().getBlockedDomains().contains(uri.getHost())) {
            return "Domain is blocked: " + uri.getHost();
        }
        try {
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
                return "Private/loopback URL not allowed: " + uri.getHost();
            }
        } catch (UnknownHostException e) {
            // allow unresolvable host; downstream HTTP client will handle
        }
        if (!"https".equalsIgnoreCase(scheme)) {
            return "Insecure transport (http) not allowed";
        }
        return null;
    }

    private boolean isBlockedHost(String host) {
        List<String> blocked = properties.getSecurity().getBlockedUrlHosts();
        String lower = host.toLowerCase();
        for (String b : blocked) {
            if (lower.equals(b.toLowerCase()) || lower.endsWith("." + b.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
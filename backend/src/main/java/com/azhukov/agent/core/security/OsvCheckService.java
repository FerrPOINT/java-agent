package com.azhukov.agent.core.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature 4: OSV (Open Source Vulnerabilities) malware check for MCP servers.
 *
 * Mirrors Hermes tools/osv_check.py — checks OSV API before launching MCP servers.
 * Only MAL-* advisories (confirmed malware) are blocked. Regular CVEs are ignored.
 * Fail-open: network errors or API failures allow the package to proceed.
 *
 * API: POST https://api.osv.io/v1/query with {"package":{"name":...,"ecosystem":...}}
 */
@Slf4j
public class OsvCheckService {

    private static final String OSV_ENDPOINT = "https://api.osv.io/v1/query";
    private static final int TIMEOUT_SECONDS = 10;
    private final String endpoint;

    private static final Pattern NPM_PACKAGE_PATTERN = Pattern.compile("^(@[^/]+/[^@]+|[^@]+)(?:@(.+))?$");
    private static final Pattern PYPI_PACKAGE_PATTERN = Pattern.compile("^([a-zA-Z0-9._-]+)(?:\\[[^\\]]*\\])?(?:==(.+))?$");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final boolean enabled;

    /** Test seam: inject a stub HttpClient and endpoint URL (real ones are used by the plain constructor). */
    OsvCheckService(boolean enabled, HttpClient httpClient, String endpoint) {
        this.enabled = enabled;
        this.httpClient = httpClient;
        this.endpoint = endpoint;
    }

    public OsvCheckService(boolean enabled) {
        this.enabled = enabled;
        this.endpoint = OSV_ENDPOINT;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .build();
    }

    /**
     * Check if an MCP server package has known malware advisories.
     *
     * @param command the launch command (e.g. "npx", "uvx")
     * @param args the command arguments
     * @return error message string if malware is found, or null if clean/unknown
     */
    public String checkPackageForMalware(String command, List<String> args) {
        if (!enabled) {
            return null;
        }

        String ecosystem = inferEcosystem(command);
        if (ecosystem == null) {
            return null; // not npx/uvx — skip
        }

        PackageInfo pkg = parsePackageFromArgs(args, ecosystem);
        if (pkg == null || pkg.name() == null) {
            return null;
        }

        try {
            List<Advisory> malware = queryOsv(pkg.name(), ecosystem, pkg.version());
            if (!malware.isEmpty()) {
                String ids = malware.stream().limit(3).map(Advisory::id).reduce((a, b) -> a + ", " + b).orElse("");
                String summaries = malware.stream().limit(3).map(a -> a.summary().length() > 100 ? a.summary().substring(0, 100) : a.summary()).reduce((a, b) -> a + "; " + b).orElse("");
                return String.format(
                    "BLOCKED: Package '%s' (%s) has known malware advisories: %s. Details: %s",
                    pkg.name(), ecosystem, ids, summaries
                );
            }
        } catch (Exception e) {
            // Fail-open: network errors, timeouts, parse failures → allow
            log.debug("OSV check failed for {}/{} (allowing): {}", ecosystem, pkg.name(), e.getMessage());
            return null;
        }
        return null;
    }

    private String inferEcosystem(String command) {
        if (command == null) return null;
        String base = command.toLowerCase();
        // Handle paths like /usr/local/bin/npx
        int lastSlash = base.lastIndexOf('/');
        if (lastSlash >= 0) base = base.substring(lastSlash + 1);
        // Handle .cmd extension (Windows)
        if (base.endsWith(".cmd")) base = base.substring(0, base.length() - 4);

        if (base.equals("npx") || base.equals("npx.cmd")) return "npm";
        if (base.equals("uvx") || base.equals("uvx.cmd") || base.equals("pipx")) return "PyPI";
        return null;
    }

    private PackageInfo parsePackageFromArgs(List<String> args, String ecosystem) {
        if (args == null || args.isEmpty()) return null;

        // Skip flags to find the package token
        String packageToken = null;
        boolean takeNext = false;
        for (String arg : args) {
            if (takeNext) {
                packageToken = arg;
                break;
            }
            if (arg.equals("--package") || arg.equals("-p")) {
                takeNext = true;
                continue;
            }
            if (arg.startsWith("--package=")) {
                packageToken = arg.substring("--package=".length());
                break;
            }
            if (arg.startsWith("-")) continue;
            packageToken = arg;
            break;
        }

        if (packageToken == null) return null;

        if ("npm".equals(ecosystem)) {
            return parseNpmPackage(packageToken);
        } else if ("PyPI".equals(ecosystem)) {
            return parsePypiPackage(packageToken);
        }
        return new PackageInfo(packageToken, null);
    }

    private PackageInfo parseNpmPackage(String token) {
        if (token.startsWith("@")) {
            // Scoped: @scope/name@version
            Matcher m = NPM_PACKAGE_PATTERN.matcher(token);
            if (m.matches()) {
                return new PackageInfo(m.group(1), m.group(2));
            }
            return new PackageInfo(token, null);
        }
        // Unscoped: name@version
        int atIdx = token.lastIndexOf('@');
        if (atIdx > 0) {
            String name = token.substring(0, atIdx);
            String version = token.substring(atIdx + 1);
            if ("latest".equals(version)) version = null;
            return new PackageInfo(name, version);
        }
        return new PackageInfo(token, null);
    }

    private PackageInfo parsePypiPackage(String token) {
        Matcher m = PYPI_PACKAGE_PATTERN.matcher(token);
        if (m.matches()) {
            return new PackageInfo(m.group(1), m.group(2));
        }
        return new PackageInfo(token, null);
    }

    private List<Advisory> queryOsv(String packageName, String ecosystem, String version) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode packageNode = payload.putObject("package");
        packageNode.put("name", packageName);
        packageNode.put("ecosystem", ecosystem);
        if (version != null && !version.isBlank()) {
            payload.put("version", version);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("User-Agent", "java-agent-osv-check/1.0")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        // Non-2xx means the API did not answer the query: treat as unknown, not "clean".
        // Throwing keeps fail-open semantics in the caller (network-error → allow), but a
        // malformed body with no "vulns" array can never be mistaken for a clean verdict.
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "OSV API returned HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode vulnsNode = root.path("vulns");

        List<Advisory> malware = new ArrayList<>();
        if (vulnsNode.isArray()) {
            for (JsonNode v : vulnsNode) {
                String id = v.path("id").asText("");
                if (id.startsWith("MAL-")) {
                    String summary = v.path("summary").asText(id);
                    malware.add(new Advisory(id, summary));
                }
            }
        }
        return malware;
    }

    private record PackageInfo(String name, String version) {}

    private record Advisory(String id, String summary) {}
}
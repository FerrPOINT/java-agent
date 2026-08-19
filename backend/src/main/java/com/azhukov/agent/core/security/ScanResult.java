package com.azhukov.agent.core.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of an MCP security scan. Carries severity, threat description,
 * list of individual findings, and optionally sanitized output text.
 */
public class ScanResult {

    private final Severity severity;
    private final String threatDescription;
    private final List<String> findings;
    private final String sanitizedText;

    private ScanResult(Severity severity, String threatDescription, List<String> findings, String sanitizedText) {
        this.severity = severity;
        this.threatDescription = threatDescription;
        this.findings = findings != null ? new ArrayList<>(findings) : new ArrayList<>();
        this.sanitizedText = sanitizedText;
    }

    public static ScanResult clean() {
        return new ScanResult(Severity.CLEAN, "No threats detected", Collections.emptyList(), null);
    }

    public static ScanResult of(Severity severity, String threatDescription, List<String> findings) {
        return new ScanResult(severity, threatDescription, findings, null);
    }

    public static ScanResult withSanitizedText(Severity severity, String threatDescription,
                                               List<String> findings, String sanitizedText) {
        return new ScanResult(severity, threatDescription, findings, sanitizedText);
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getThreatDescription() {
        return threatDescription;
    }

    public List<String> getFindings() {
        return Collections.unmodifiableList(findings);
    }

    public String getSanitizedText() {
        return sanitizedText;
    }

    public boolean isClean() {
        return severity == Severity.CLEAN;
    }

    public boolean isBlocked() {
        return severity == Severity.CRITICAL || severity == Severity.HIGH;
    }
}
package com.azhukov.agent.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanResultTest {

    @Test
    void cleanResultHasCleanSeverity() {
        ScanResult result = ScanResult.clean();
        assertThat(result.getSeverity()).isEqualTo(Severity.CLEAN);
        assertThat(result.isClean()).isTrue();
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.getFindings()).isEmpty();
        assertThat(result.getThreatDescription()).isEqualTo("No threats detected");
        assertThat(result.getSanitizedText()).isNull();
    }

    @Test
    void ofCreatesResultWithFindings() {
        ScanResult result = ScanResult.of(Severity.HIGH, "Threat found", List.of("HIGH: issue1"));
        assertThat(result.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(result.isClean()).isFalse();
        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getFindings()).hasSize(1);
        assertThat(result.getThreatDescription()).isEqualTo("Threat found");
    }

    @Test
    void withSanitizedTextCreatesResultWithSanitizedText() {
        ScanResult result = ScanResult.withSanitizedText(Severity.MEDIUM, "Issues", List.of("MEDIUM: x"), "safe text");
        assertThat(result.getSanitizedText()).isEqualTo("safe text");
        assertThat(result.getSeverity()).isEqualTo(Severity.MEDIUM);
        assertThat(result.isBlocked()).isFalse();
    }

    @Test
    void criticalIsBlocked() {
        ScanResult result = ScanResult.of(Severity.CRITICAL, "Critical", List.of("CRITICAL: x"));
        assertThat(result.isBlocked()).isTrue();
    }

    @Test
    void highIsBlocked() {
        ScanResult result = ScanResult.of(Severity.HIGH, "High", List.of("HIGH: x"));
        assertThat(result.isBlocked()).isTrue();
    }

    @Test
    void mediumIsNotBlocked() {
        ScanResult result = ScanResult.of(Severity.MEDIUM, "Medium", List.of("MEDIUM: x"));
        assertThat(result.isBlocked()).isFalse();
    }

    @Test
    void lowIsNotBlocked() {
        ScanResult result = ScanResult.of(Severity.LOW, "Low", List.of("LOW: x"));
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.isClean()).isFalse();
    }

    @Test
    void cleanIsNotBlocked() {
        ScanResult result = ScanResult.clean();
        assertThat(result.isBlocked()).isFalse();
        assertThat(result.isClean()).isTrue();
    }

    @Test
    void findingsAreUnmodifiable() {
        ScanResult result = ScanResult.of(Severity.HIGH, "Threat", List.of("HIGH: issue"));
        org.assertj.core.api.Assertions.assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> result.getFindings().add("new"));
    }

    @Test
    void sanitizedTextNullForOf() {
        ScanResult result = ScanResult.of(Severity.HIGH, "Threat", List.of("HIGH: x"));
        assertThat(result.getSanitizedText()).isNull();
    }

    @Test
    void sanitizedTextSetForWithSanitizedText() {
        ScanResult result = ScanResult.withSanitizedText(Severity.CLEAN, "OK", List.of(), "clean text");
        assertThat(result.getSanitizedText()).isEqualTo("clean text");
    }
}
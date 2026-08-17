package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * h74: Tests for backend unavailability detection in CronJobService.
 */
class CronJobServiceBackendUnavailableTest {

    @Test
    void isBackendUnavailable_connectionRefused() {
        assertThat(CronJobService.isBackendUnavailable("Connection refused")).isTrue();
    }

    @Test
    void isBackendUnavailable_connectionReset() {
        assertThat(CronJobService.isBackendUnavailable("Connection reset by peer")).isTrue();
    }

    @Test
    void isBackendUnavailable_connectionClosed() {
        assertThat(CronJobService.isBackendUnavailable("Connection closed")).isTrue();
    }

    @Test
    void isBackendUnavailable_connectionTimedOut() {
        assertThat(CronJobService.isBackendUnavailable("Connection timed out")).isTrue();
    }

    @Test
    void isBackendUnavailable_connectException() {
        assertThat(CronJobService.isBackendUnavailable("ConnectException: failed to connect")).isTrue();
    }

    @Test
    void isBackendUnavailable_unknownHost() {
        assertThat(CronJobService.isBackendUnavailable("UnknownHostException: api.example.com")).isTrue();
    }

    @Test
    void isBackendUnavailable_serviceUnavailable() {
        assertThat(CronJobService.isBackendUnavailable("503 Service unavailable")).isTrue();
    }

    @Test
    void isBackendUnavailable_noRouteToHost() {
        assertThat(CronJobService.isBackendUnavailable("No route to host")).isTrue();
    }

    @Test
    void isBackendUnavailable_networkUnreachable() {
        assertThat(CronJobService.isBackendUnavailable("Network is unreachable")).isTrue();
    }

    @Test
    void isBackendUnavailable_regularError_notFlagged() {
        assertThat(CronJobService.isBackendUnavailable("Invalid prompt format")).isFalse();
        assertThat(CronJobService.isBackendUnavailable("Skill not found")).isFalse();
        assertThat(CronJobService.isBackendUnavailable("Timeout waiting for LLM response")).isFalse();
    }

    @Test
    void isBackendUnavailable_nullInput() {
        assertThat(CronJobService.isBackendUnavailable(null)).isFalse();
    }

    @Test
    void isBackendUnavailable_emptyInput() {
        assertThat(CronJobService.isBackendUnavailable("")).isFalse();
    }

    @Test
    void isBackendUnavailable_blankInput() {
        assertThat(CronJobService.isBackendUnavailable("  ")).isFalse();
    }
}
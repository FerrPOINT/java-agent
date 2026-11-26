package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiRunAdmissionServiceTest {

    @Test
    void defaultLimitIsTenLikeHermes() {
        AgentProperties properties = new AgentProperties();
        ApiRunAdmissionService service = new ApiRunAdmissionService(properties, null);

        assertThat(service.maxConcurrentRuns()).isEqualTo(10);
        assertThat(service.activeRunCount()).isZero();
    }

    @Test
    void acquireRejectsAtConfiguredLimitAndCloseIsIdempotent() {
        AgentProperties properties = new AgentProperties();
        properties.getApi().setMaxConcurrentRuns(1);
        ApiRunAdmissionService service = new ApiRunAdmissionService(properties, null);

        ApiRunAdmissionService.Reservation reservation = service.tryAcquire().orElseThrow();

        assertThat(service.tryAcquire()).isEmpty();
        assertThat(service.activeRunCount()).isEqualTo(1);

        reservation.close();
        reservation.close();

        assertThat(service.activeRunCount()).isZero();
        assertThat(service.tryAcquire()).isPresent();
    }

    @Test
    void zeroDisablesLimitWithoutCountingActiveRuns() {
        AgentProperties properties = new AgentProperties();
        properties.getApi().setMaxConcurrentRuns(0);
        ApiRunAdmissionService service = new ApiRunAdmissionService(properties, null);

        try (ApiRunAdmissionService.Reservation ignored = service.tryAcquire().orElseThrow()) {
            assertThat(service.activeRunCount()).isZero();
            assertThat(service.tryAcquire()).isPresent();
        }
        assertThat(service.activeRunCount()).isZero();
    }

    @Test
    void admissionAndReconfigurationUseOnlyTheSelectedProfile() throws Exception {
        var profiles = mock(ProfileService.class);
        var service = new ApiRunAdmissionService(new AgentProperties(), profiles);
        when(profiles.readExecutionConfig("alpha")).thenReturn(Map.of("max_concurrent_runs", 1));
        when(profiles.readExecutionConfig("beta")).thenReturn(Map.of("max_concurrent_runs", 2));
        try (var firstAlpha = service.tryAcquire("alpha", service.maxConcurrentRuns("alpha")).orElseThrow();
             var firstBeta = service.tryAcquire("beta", service.maxConcurrentRuns("beta")).orElseThrow();
             var secondBeta = service.tryAcquire("beta", service.maxConcurrentRuns("beta")).orElseThrow()) {
            assertThat(service.tryAcquire("alpha", service.maxConcurrentRuns("alpha"))).isEmpty();
            assertThat(service.tryAcquire("beta", service.maxConcurrentRuns("beta"))).isEmpty();
            when(profiles.readExecutionConfig("alpha")).thenReturn(Map.of("max_concurrent_runs", 2));
            try (var secondAlpha = service.tryAcquire("alpha", service.maxConcurrentRuns("alpha")).orElseThrow()) {
                assertThat(service.tryAcquire("beta", service.maxConcurrentRuns("beta"))).isEmpty();
                assertThat(service.activeRunCount()).isEqualTo(4);
            }
        }
    }
}

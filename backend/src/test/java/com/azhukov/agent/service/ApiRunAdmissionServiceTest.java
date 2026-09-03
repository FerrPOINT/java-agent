package com.azhukov.agent.service;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRunAdmissionServiceTest {

    @Test
    void defaultLimitIsTenLikeHermes() {
        AgentProperties properties = new AgentProperties();
        ApiRunAdmissionService service = new ApiRunAdmissionService(properties);

        assertThat(service.maxConcurrentRuns()).isEqualTo(10);
        assertThat(service.activeRunCount()).isZero();
    }

    @Test
    void acquireRejectsAtConfiguredLimitAndCloseIsIdempotent() {
        AgentProperties properties = new AgentProperties();
        properties.getApi().setMaxConcurrentRuns(1);
        ApiRunAdmissionService service = new ApiRunAdmissionService(properties);

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
        ApiRunAdmissionService service = new ApiRunAdmissionService(properties);

        try (ApiRunAdmissionService.Reservation ignored = service.tryAcquire().orElseThrow()) {
            assertThat(service.activeRunCount()).isZero();
            assertThat(service.tryAcquire()).isPresent();
        }
        assertThat(service.activeRunCount()).isZero();
    }
}

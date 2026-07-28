package com.azhukov.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaAgentApplicationTest {

    @Test
    void createsWebApplicationByDefault() {
        SpringApplication app = JavaAgentApplication.createApplication(new String[]{});
        assertThat(app).isNotNull();
        assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
    }

    @Test
    void createsNonWebApplicationForCliProfile() {
        SpringApplication app = JavaAgentApplication.createApplication(new String[]{"--spring.profiles.active=cli,noop"});
        assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
    }
}

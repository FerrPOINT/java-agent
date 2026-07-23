package com.ferrpoint.agent;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    public GenericContainer<?> chromeContainer() {
        return new GenericContainer<>(DockerImageName.parse("chromedp/headless-shell:latest"))
            .withExposedPorts(9222);
    }
}

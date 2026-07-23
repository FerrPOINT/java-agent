package com.ferrpoint.hermes

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection(name = "openwebui/ollama")
    fun ollamaContainer(): GenericContainer<*> {
        return GenericContainer(DockerImageName.parse("openwebui/ollama:latest"))
            .withExposedPorts(11434)
    }
}

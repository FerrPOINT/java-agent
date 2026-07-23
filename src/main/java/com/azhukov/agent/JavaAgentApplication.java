package com.azhukov.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.azhukov.agent.config.AgentProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class JavaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaAgentApplication.class, args);
    }
}

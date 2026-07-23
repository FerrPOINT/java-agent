package com.azhukov.agent;

import com.azhukov.agent.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class JavaAgentApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JavaAgentApplication.class);
        if (isCliProfileActive(args)) {
            app.setWebApplicationType(WebApplicationType.NONE);
        }
        app.run(args);
    }

    private static boolean isCliProfileActive(String[] args) {
        for (String arg : args) {
            if (arg.contains("cli") && (arg.startsWith("--spring.profiles.active=") || arg.startsWith("-Dspring.profiles.active="))) {
                return true;
            }
        }
        String profiles = System.getProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE"));
        return profiles != null && profiles.contains("cli");
    }
}

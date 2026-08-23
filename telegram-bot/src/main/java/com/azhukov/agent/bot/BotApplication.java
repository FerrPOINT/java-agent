package com.azhukov.agent.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// UserDetailsServiceAutoConfiguration excluded by NAME: security lands in
// the runtime classpath transitively (project(':backend') implementation
// dep -> bot bootJar lib), but NOT on the bot's compile classpath, so the
// class literal isn't referenceable here. The bot exposes only actuator
// health/info (already permitted by the shared SecurityConfig); the
// generated-password WARN on every start guards nothing.
@SpringBootApplication(
    scanBasePackages = "com.azhukov.agent.bot",
    excludeName = "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
@EnableScheduling
public class BotApplication {

    public static void main(String[] args) {
        SpringApplication.run(BotApplication.class, args);
    }
}
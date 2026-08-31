package com.azhukov.agent.bot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Exposes bot health and Telegram webhook without interactive login. */
@Configuration
@EnableWebSecurity
public class BotSecurityConfig {

    @Bean
    public SecurityFilterChain botSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/bot/health", "/actuator/health/**", "/actuator/info", "/webhook/telegram").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}

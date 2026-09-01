package com.azhukov.agent.config;

import com.azhukov.agent.api.filter.ApiKeyAuthFilter;
import com.azhukov.agent.api.filter.SecurityHeadersFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.DispatcherType;

/**
 * Spring Security configuration.
 * <p>
 * Registers the {@link ApiKeyAuthFilter} and configures stateless, API-only security:
 * <ul>
 *   <li>CSRF disabled (no browser forms)</li>
 *   <li>Form login and HTTP basic disabled</li>
 *   <li>No session management (stateless)</li>
 *   <li>Health/info and Telegram webhook endpoints are public</li>
 *   <li>All other endpoints require API key authentication</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final SecurityHeadersFilter securityHeadersFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // ASYNC dispatch types (SSE streaming) are permitted — authentication
                // was already validated on the initial REQUEST dispatch by ApiKeyAuthFilter.
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**",
                    "/actuator/health/**", "/actuator/info").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(securityHeadersFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
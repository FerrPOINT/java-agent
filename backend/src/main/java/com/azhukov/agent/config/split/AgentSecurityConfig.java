package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.ApprovalQueue;
import com.azhukov.agent.core.security.CommandApprovalManager;
import com.azhukov.agent.core.security.DefaultFileSafety;
import com.azhukov.agent.core.security.DefaultRedactor;
import com.azhukov.agent.core.security.DefaultToolCallGuardrail;
import com.azhukov.agent.core.security.DefaultToolGuardrails;
import com.azhukov.agent.core.security.DefaultUrlSafety;
import com.azhukov.agent.core.security.FileSafety;
import com.azhukov.agent.core.security.FileSafetyValidator;
import com.azhukov.agent.core.security.MessageSanitizer;
import com.azhukov.agent.core.security.Redactor;
import com.azhukov.agent.core.security.SecretRedactor;
import com.azhukov.agent.core.security.SsrfSafeHttpClient;
import com.azhukov.agent.core.security.ToolCallGuardrail;
import com.azhukov.agent.core.security.ToolGuardrails;
import com.azhukov.agent.core.security.UrlSafety;
import com.azhukov.agent.core.security.UrlSafetyHandler;
import com.azhukov.agent.core.security.UserInputSanitizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Security-related beans: sanitizers, redactors, file/URL safety, guardrails,
 * approval manager, SSRF-safe HTTP client.
 */
@Configuration
public class AgentSecurityConfig {

    @Bean
    public MessageSanitizer messageSanitizer(SecretRedactor redactor) {
        return new MessageSanitizer(redactor);
    }

    @Bean
    public UserInputSanitizer userInputSanitizer() {
        return new UserInputSanitizer();
    }

    @Bean
    public SecretRedactor secretRedactor(AgentProperties properties) {
        return new SecretRedactor(properties);
    }

    @Bean
    public FileSafetyValidator fileSafetyValidator(AgentProperties properties) {
        return new FileSafetyValidator(properties);
    }

    @Bean
    public UrlSafetyHandler urlSafetyHandler(AgentProperties properties, UrlSafety urlSafety) {
        return new UrlSafetyHandler(properties, urlSafety);
    }

    @Bean
    public SsrfSafeHttpClient ssrfSafeHttpClient(UrlSafetyHandler urlSafetyHandler, SecretRedactor redactor,
                                                  AgentProperties properties) {
        return new SsrfSafeHttpClient(urlSafetyHandler, redactor, properties);
    }

    @Bean
    public CommandApprovalManager commandApprovalManager(AgentProperties properties) {
        return new CommandApprovalManager(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolCallGuardrail.class)
    public ToolCallGuardrail toolCallGuardrail(AgentProperties properties) {
        return new DefaultToolCallGuardrail(properties);
    }

    @Bean
    @ConditionalOnMissingBean(FileSafety.class)
    public FileSafety fileSafety(AgentProperties properties) {
        return new DefaultFileSafety(properties);
    }

    @Bean
    @ConditionalOnMissingBean(UrlSafety.class)
    public UrlSafety urlSafety(AgentProperties properties) {
        return new DefaultUrlSafety(properties);
    }

    @Bean
    @ConditionalOnMissingBean(Redactor.class)
    public Redactor redactor(AgentProperties properties) {
        return new DefaultRedactor(properties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolGuardrails.class)
    public ToolGuardrails toolGuardrails(AgentProperties properties, ApprovalQueue approvalQueue) {
        return new DefaultToolGuardrails(properties, approvalQueue);
    }
}
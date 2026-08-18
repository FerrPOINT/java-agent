package com.azhukov.agent.config.split;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.security.ApprovalQueue;
import com.azhukov.agent.security.CommandApprovalManager;
import com.azhukov.agent.security.DefaultFileSafety;
import com.azhukov.agent.security.DefaultRedactor;
import com.azhukov.agent.security.DefaultToolCallGuardrail;
import com.azhukov.agent.security.DefaultToolGuardrails;
import com.azhukov.agent.security.DefaultUrlSafety;
import com.azhukov.agent.security.FileSafety;
import com.azhukov.agent.security.FileSafetyValidator;
import com.azhukov.agent.security.MessageSanitizer;
import com.azhukov.agent.security.Redactor;
import com.azhukov.agent.security.SecretRedactor;
import com.azhukov.agent.security.SsrfSafeHttpClient;
import com.azhukov.agent.security.ToolCallGuardrail;
import com.azhukov.agent.security.ToolGuardrails;
import com.azhukov.agent.security.UrlSafety;
import com.azhukov.agent.security.UrlSafetyHandler;
import com.azhukov.agent.security.UserInputSanitizer;
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
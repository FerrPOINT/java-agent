package com.azhukov.agent.config.split;

import org.springframework.context.annotation.Configuration;

/**
 * Tool-related beans.
 * <p>
 * {@code ToolRegistry}, {@code ToolExecutionService}, and tool parallel-safety
 * components are auto-detected via component scanning (they are annotated with
 * {@code @Component}). This config class exists to provide a home for any
 * future tool-related {@code @Bean} definitions and to keep the domain split
 * complete.
 */
@Configuration(proxyBeanMethods = false)
public class ToolConfig {
}
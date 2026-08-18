package com.azhukov.agent.security;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.azhukov.agent.config.AgentProperties;

/**
 * A Logback {@link PatternLayout} wrapper that applies {@link DefaultRedactor#redact(String)}
 * to every formatted log message, ensuring secrets never appear in log output.
 * <p>
 * The {@link DefaultRedactor} is instantiated statically (without Spring) so that
 * Logback can use this layout even before the Spring context is ready.  The static
 * instance uses default {@link AgentProperties} settings, which enable redaction
 * with the built-in vendor patterns.  If instantiation fails (e.g. in test
 * environments without Logback on the classpath), the layout falls back to the
 * plain pattern without redaction.
 */
public class RedactingLayout extends PatternLayout {

    private static final Redactor STATIC_REDACTOR;

    static {
        Redactor redactor = null;
        try {
            redactor = new DefaultRedactor(new AgentProperties());
        } catch (Throwable t) {
            // Fallback: no redaction available
        }
        STATIC_REDACTOR = redactor;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        String formatted = super.doLayout(event);
        if (STATIC_REDACTOR == null) {
            return formatted;
        }
        try {
            return STATIC_REDACTOR.redact(formatted);
        } catch (Exception e) {
            // If redaction fails, return the original formatted message
            return formatted;
        }
    }
}
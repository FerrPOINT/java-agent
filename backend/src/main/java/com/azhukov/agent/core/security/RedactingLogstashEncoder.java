package com.azhukov.agent.core.security;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.EncoderBase;
import com.azhukov.agent.config.AgentProperties;
import net.logstash.logback.encoder.LogstashEncoder;

import java.nio.charset.StandardCharsets;

/**
 * A Logback encoder that wraps {@link LogstashEncoder} and applies
 * {@link DefaultRedactor#redact(String)} to the JSON output, ensuring
 * secrets are masked even in structured (prod) JSON logs.
 * <p>
 * The {@link DefaultRedactor} is instantiated statically (without Spring)
 * so Logback can use this encoder even before the Spring context is ready.
 */
public class RedactingLogstashEncoder extends EncoderBase<ILoggingEvent> {

    private static final Redactor STATIC_REDACTOR;
    private final LogstashEncoder delegate = new LogstashEncoder();

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
    public byte[] encode(ILoggingEvent event) {
        byte[] raw = delegate.encode(event);
        if (raw == null || STATIC_REDACTOR == null) {
            return raw;
        }
        String json = new String(raw, StandardCharsets.UTF_8);
        try {
            String redacted = STATIC_REDACTOR.redact(json);
            return redacted.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw;
        }
    }

    @Override
    public byte[] headerBytes() {
        return delegate.headerBytes();
    }

    @Override
    public byte[] footerBytes() {
        return delegate.footerBytes();
    }

    @Override
    public void start() {
        super.start();
        delegate.setContext(getContext());
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
        super.stop();
    }
}
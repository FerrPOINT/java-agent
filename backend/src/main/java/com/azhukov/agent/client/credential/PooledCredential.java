package com.azhukov.agent.client.credential;

/**
 * A single credential in the pool.
 * Mutable: status, request count, and cooldown are updated at runtime.
 */
public class PooledCredential {

    private final String id;
    private final String provider;
    private final String label;
    private final String source;
    private final int priority;
    private final String apiKey;
    private final String baseUrl;

    private volatile CredentialPool.Status status = CredentialPool.Status.OK;
    private volatile long lastStatusAt = 0;
    private volatile int lastErrorCode = 0;
    private volatile String lastErrorReason;
    private volatile long cooldownUntil = 0;
    private final java.util.concurrent.atomic.AtomicLong requestCount = new java.util.concurrent.atomic.AtomicLong(0);

    public PooledCredential(String id, String provider, String apiKey, String baseUrl, int priority) {
        this(id, provider, "manual", apiKey, baseUrl, priority);
    }

    public PooledCredential(String id, String provider, String source, String apiKey, String baseUrl, int priority) {
        this.id = id;
        this.provider = provider;
        this.label = source != null ? source : provider;
        this.source = source != null ? source : "manual";
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.priority = priority;
    }

    public String id() { return id; }
    public String provider() { return provider; }
    public String label() { return label; }
    public String source() { return source; }
    public int priority() { return priority; }
    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }

    public CredentialPool.Status status() { return status; }
    public void setStatus(CredentialPool.Status status) { this.status = status; }

    public long lastStatusAt() { return lastStatusAt; }
    public void setLastStatusAt(long lastStatusAt) { this.lastStatusAt = lastStatusAt; }

    public int lastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(int lastErrorCode) { this.lastErrorCode = lastErrorCode; }

    public String lastErrorReason() { return lastErrorReason; }
    public void setLastErrorReason(String lastErrorReason) { this.lastErrorReason = lastErrorReason; }

    public long cooldownUntil() { return cooldownUntil; }
    public void setCooldownUntil(long cooldownUntil) { this.cooldownUntil = cooldownUntil; }

    public long requestCount() { return requestCount.get(); }
    public void incrementRequestCount() { requestCount.incrementAndGet(); }
}
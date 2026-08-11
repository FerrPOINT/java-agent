package com.azhukov.agent.client.credential;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-credential pool for same-provider failover.
 * <p>
 * Mirrors the original project's agent/credential_pool.py — supports multiple credentials
 * per provider with rotation strategies (fill_first, round_robin, least_used),
 * dead credential pruning, and exhaustion cooldowns.
 */
@Slf4j
public class CredentialPool {

 public enum Strategy {
 FILL_FIRST,
 ROUND_ROBIN,
 LEAST_USED
 }

 public enum Status {
 OK,
 EXHAUSTED,
 DEAD
 }

 private final String provider;
 private final List<PooledCredential> entries;
 private final Strategy strategy;
 private final Object lock = new Object();
 private int currentIndex = 0;
 private final AtomicInteger rotationCount = new AtomicInteger(0);

 // Cooldown TTLs (seconds)
 private static final long EXHAUSTED_TTL_401 = 5 * 60;
 private static final long EXHAUSTED_TTL_429 = 60 * 60;
 private static final long EXHAUSTED_TTL_DEFAULT = 60 * 60;
 // Dead manual credentials are pruned after this window
 private static final long DEAD_MANUAL_PRUNE_TTL = 24 * 60 * 60;

 public CredentialPool(String provider, List<PooledCredential> entries, Strategy strategy) {
 this.provider = provider;
 this.strategy = strategy != null ? strategy : Strategy.FILL_FIRST;
 // Sort by priority
 this.entries = new CopyOnWriteArrayList<>();
 if (entries != null) {
 List<PooledCredential> sorted = new ArrayList<>(entries);
 sorted.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
 this.entries.addAll(sorted);
 }
 }

 public boolean hasCredentials() {
 return !entries.isEmpty();
 }

 public boolean hasAvailable() {
 return entries.stream().anyMatch(this::isAvailable);
 }

 public List<PooledCredential> entries() {
 return List.copyOf(entries);
 }

 /**
 * Get the current credential based on the rotation strategy.
 */
 public PooledCredential current() {
 synchronized (lock) {
 if (entries.isEmpty()) return null;

 // Filter to available entries
 List<PooledCredential> available = new ArrayList<>();
 for (PooledCredential entry : entries) {
 if (isAvailable(entry)) {
 available.add(entry);
 }
 }
 if (available.isEmpty()) return null;

 return switch (strategy) {
 case FILL_FIRST -> available.get(0);
 case ROUND_ROBIN -> {
 int idx = rotationCount.getAndIncrement() % available.size();
 yield available.get(idx);
 }
 case LEAST_USED -> available.stream()
 .min((a, b) -> Long.compare(a.requestCount(), b.requestCount()))
 .orElse(available.get(0));
 };
 }
 }

 /**
 * Mark a credential as used (increment request count).
 */
 public void markUsed(PooledCredential credential) {
 if (credential != null) {
 credential.incrementRequestCount();
 }
 }

 /**
 * Mark a credential as exhausted with an error code.
 * The credential enters a cooldown period based on the error type.
 */
 public void markExhausted(PooledCredential credential, int errorCode, String errorMessage) {
 if (credential == null) return;
 synchronized (lock) {
 credential.setStatus(Status.EXHAUSTED);
 credential.setLastStatusAt(System.currentTimeMillis());
 credential.setLastErrorCode(errorCode);
 credential.setLastErrorReason(errorMessage);
 long ttl = exhaustedTtl(errorCode);
 credential.setCooldownUntil(System.currentTimeMillis() + ttl * 1000);
 log.debug("Credential {} for provider {} exhausted (code={}): cooldown {}s",
 credential.id(), provider, errorCode, ttl);
 }
 }

 /**
 * Mark a credential as dead (permanently invalid — token revoked, etc.).
 */
 public void markDead(PooledCredential credential, String reason) {
 if (credential == null) return;
 synchronized (lock) {
 credential.setStatus(Status.DEAD);
 credential.setLastStatusAt(System.currentTimeMillis());
 credential.setLastErrorReason(reason);
 log.warn("Credential {} for provider {} marked dead: {}", credential.id(), provider, reason);
 }
 }

 /**
 * Prune dead credentials that have been in that state past the prune TTL.
 * Manual entries are pruned; singleton-seeded entries are kept.
 */
 public void pruneDead() {
 long now = System.currentTimeMillis();
 entries.removeIf(entry -> {
 if (entry.status() == Status.DEAD && entry.lastStatusAt() > 0) {
 long ageSeconds = (now - entry.lastStatusAt()) / 1000;
 if ("manual".equals(entry.source()) && ageSeconds > DEAD_MANUAL_PRUNE_TTL) {
 log.info("Pruning dead manual credential {} for provider {}", entry.id(), provider);
 return true;
 }
 }
 return false;
 });
 }

 /**
 * Check if a credential is currently available (not exhausted, not dead, not in cooldown).
 */
 public boolean isAvailable(PooledCredential entry) {
 if (entry.status() == Status.DEAD) return false;
 if (entry.status() == Status.EXHAUSTED) {
 if (entry.cooldownUntil() > 0 && System.currentTimeMillis() < entry.cooldownUntil()) {
 return false;
 }
 // Cooldown expired — mark as OK
 entry.setStatus(Status.OK);
 entry.setCooldownUntil(0);
 }
 return true;
 }

 private long exhaustedTtl(int errorCode) {
 if (errorCode == 401) return EXHAUSTED_TTL_401;
 if (errorCode == 429) return EXHAUSTED_TTL_429;
 return EXHAUSTED_TTL_DEFAULT;
 }
}
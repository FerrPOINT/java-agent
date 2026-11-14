package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import com.azhukov.agent.core.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP-9 (ADR-015): pub channel registry.
 *
 * <p>Named in-memory channels with a per-channel ACL (authenticated owner and profile),
 * bounded retained-event rings and cursor replay. Slow consumers drop the
 * OLDEST retained frames (documented backpressure) — memory is bounded per
 * channel, never an unbounded queue. Pub is best-effort live transport by
 * design; durable replay belongs to console tasks and the run event log.
 */
@Service
@Slf4j
public class PubChannelRegistry {

    public static final int MAX_RETAINED_PER_CHANNEL = 500;

    public record PubEvent(long seq, String type, Map<String, Object> payload) {}

    public record SubscribeResult(boolean allowed, String reason) {}

    private static final class Channel {
        final String ownerProfile;
        final String ownerId;
        final ArrayDeque<PubEvent> retained = new ArrayDeque<>();
        long seq = 0;

        Channel(String ownerProfile, String ownerId) {
            this.ownerProfile = ownerProfile;
            this.ownerId = ownerId;
        }
    }

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();

    /** Create (or verify) a channel bound to its authenticated owner and profile. */
    public SubscribeResult create(String profile, String name) {
        String canonProfile = canon(profile);
        if (name == null || name.isBlank() || !name.matches("[a-zA-Z0-9._-]{1,64}")) {
            return new SubscribeResult(false, "invalid channel name");
        }
        String ownerId = UserContext.getUserId();
        Channel existing = channels.putIfAbsent(name, new Channel(canonProfile, ownerId));
        if (existing != null) {
            return existing.ownerProfile.equals(canonProfile) && Objects.equals(existing.ownerId, ownerId)
                ? new SubscribeResult(true, null)
                : new SubscribeResult(false, "channel owned by another scope");
        }
        return new SubscribeResult(true, null);
    }

    /** Publish an event only within the channel owner/profile scope. */
    public long publish(String profile, String name, String type, Map<String, Object> payload) {
        Channel channel = owned(profile, name);
        if (channel == null) {
            return -1;
        }
        synchronized (channel.retained) {
            channel.retained.addLast(new PubEvent(++channel.seq, type,
                payload == null ? Map.of() : payload));
            // backpressure: drop oldest beyond the retention bound
            while (channel.retained.size() > MAX_RETAINED_PER_CHANNEL) {
                channel.retained.removeFirst();
            }
            return channel.seq;
        }
    }

    /** Replay retained events strictly after the cursor (bounded). */
    public List<PubEvent> replay(String profile, String name, long after, int limit) {
        Channel channel = owned(profile, name);
        if (channel == null) {
            return List.of();
        }
        int bounded = Math.min(Math.max(limit, 1), MAX_RETAINED_PER_CHANNEL);
        List<PubEvent> events = new ArrayList<>();
        synchronized (channel.retained) {
            for (PubEvent event : channel.retained) {
                if (event.seq() > after) {
                    events.add(event);
                    if (events.size() >= bounded) {
                        break;
                    }
                }
            }
        }
        return events;
    }

    public boolean delete(String profile, String name) {
        Channel channel = owned(profile, name);
        if (channel == null) {
            return false;
        }
        return channels.remove(name, channel);
    }

    public Set<String> list(String profile) {
        String canonProfile = canon(profile);
        String ownerId = UserContext.getUserId();
        return channels.entrySet().stream()
            .filter(entry -> entry.getValue().ownerProfile.equals(canonProfile)
                && Objects.equals(entry.getValue().ownerId, ownerId))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private Channel owned(String profile, String name) {
        Channel channel = channels.get(name);
        if (channel == null || !channel.ownerProfile.equals(canon(profile))
                || !Objects.equals(channel.ownerId, UserContext.getUserId())) {
            return null;
        }
        return channel;
    }

    private static String canon(String profile) {
        return profile == null || profile.isBlank() ? "default" : profile.trim().toLowerCase();
    }
}

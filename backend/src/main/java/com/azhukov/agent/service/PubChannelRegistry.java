package com.azhukov.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WP-9 (ADR-015): pub channel registry.
 *
 * <p>Named in-memory channels with a per-channel ACL (profile scope),
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
        final String name;
        final String ownerProfile;
        final ArrayDeque<PubEvent> retained = new ArrayDeque<>();
        long seq = 0;

        Channel(String name, String ownerProfile) {
            this.name = name;
            this.ownerProfile = ownerProfile;
        }
    }

    private final ConcurrentHashMap<String, Channel> channels = new ConcurrentHashMap<>();

    /** Create (or verify) a channel bound to a profile scope. */
    public SubscribeResult create(String profile, String name) {
        String canonProfile = canon(profile);
        if (name == null || name.isBlank() || !name.matches("[a-zA-Z0-9._-]{1,64}")) {
            return new SubscribeResult(false, "invalid channel name");
        }
        Channel existing = channels.get(name);
        if (existing != null) {
            return existing.ownerProfile.equals(canonProfile)
                ? new SubscribeResult(true, null)
                : new SubscribeResult(false, "channel owned by another profile");
        }
        channels.put(name, new Channel(name, canonProfile));
        return new SubscribeResult(true, null);
    }

    /** Publish an event; profile must own the channel. */
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
        channels.remove(name);
        return true;
    }

    public Set<String> list(String profile) {
        String canonProfile = canon(profile);
        return channels.entrySet().stream()
            .filter(entry -> entry.getValue().ownerProfile.equals(canonProfile))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private Channel owned(String profile, String name) {
        Channel channel = channels.get(name);
        if (channel == null || !channel.ownerProfile.equals(canon(profile))) {
            return null;
        }
        return channel;
    }

    private static String canon(String profile) {
        return profile == null || profile.isBlank() ? "default" : profile.trim().toLowerCase();
    }
}

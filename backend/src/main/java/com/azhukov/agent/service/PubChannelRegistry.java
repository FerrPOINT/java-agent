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

    private record ChannelKey(String ownerId, String profile, String name) {}

    private static final class Channel {
        final ArrayDeque<PubEvent> retained = new ArrayDeque<>();
        long seq = 0;
    }

    private final ConcurrentHashMap<ChannelKey, Channel> channels = new ConcurrentHashMap<>();

    /** Legacy/system profile-scoped channel API. */
    public SubscribeResult create(String profile, String name) {
        if (!validName(name)) {
            return new SubscribeResult(false, "invalid channel name");
        }
        ChannelKey legacyKey = key(profile, null, name);
        if (channels.containsKey(legacyKey)) {
            return new SubscribeResult(true, null);
        }
        boolean claimedByAnotherLegacyProfile = channels.keySet().stream()
            .anyMatch(existing -> existing.ownerId() == null && existing.name().equals(name));
        if (claimedByAnotherLegacyProfile) {
            return new SubscribeResult(false, "channel owned by another profile");
        }
        channels.putIfAbsent(legacyKey, new Channel());
        return new SubscribeResult(true, null);
    }

    /** Create (or verify) a channel bound to both profile and authenticated owner. */
    public SubscribeResult create(String profile, String ownerId, String name) {
        if (ownerId == null) {
            return create(profile, name);
        }
        if (!validName(name)) {
            return new SubscribeResult(false, "invalid channel name");
        }
        channels.putIfAbsent(key(profile, ownerId, name), new Channel());
        return new SubscribeResult(true, null);
    }

    /** Publish an event; profile must own the channel. */
    public long publish(String profile, String name, String type, Map<String, Object> payload) {
        return publish(profile, null, name, type, payload);
    }

    /** Publish an event; both profile and authenticated owner must match. */
    public long publish(String profile, String ownerId, String name, String type, Map<String, Object> payload) {
        Channel channel = owned(profile, ownerId, name);
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
        return replay(profile, null, name, after, limit);
    }

    /** Replay events only from a channel owned by the authenticated user. */
    public List<PubEvent> replay(String profile, String ownerId, String name, long after, int limit) {
        Channel channel = owned(profile, ownerId, name);
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
        return delete(profile, null, name);
    }

    /** Delete only the exact owner/profile channel, preserving concurrent replacements. */
    public boolean delete(String profile, String ownerId, String name) {
        ChannelKey key = key(profile, ownerId, name);
        Channel channel = channels.get(key);
        return channel != null && channels.remove(key, channel);
    }

    public Set<String> list(String profile) {
        return list(profile, null);
    }

    public Set<String> list(String profile, String ownerId) {
        String canonProfile = canon(profile);
        return channels.keySet().stream()
            .filter(key -> key.profile().equals(canonProfile) && java.util.Objects.equals(key.ownerId(), ownerId))
            .map(ChannelKey::name)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private Channel owned(String profile, String ownerId, String name) {
        return channels.get(key(profile, ownerId, name));
    }

    private static ChannelKey key(String profile, String ownerId, String name) {
        return new ChannelKey(ownerId, canon(profile), name);
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.matches("[a-zA-Z0-9._-]{1,64}");
    }

    private static String canon(String profile) {
        return profile == null || profile.isBlank() ? "default" : profile.trim().toLowerCase();
    }
}

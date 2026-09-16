package com.azhukov.agent.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-9 (ADR-015): PTY sessions and pub channels — lifecycle, isolation,
 * cursor replay, bounded backpressure. PTY tests require /usr/bin/script.
 */
class PtyAndPubServiceTest {

    // ── PTY ──────────────────────────────────────────────────────────────

    @Test
    void ptyStartWriteReadCloseLifecycle() throws Exception {
        if (!PtySessionService.ptyAvailable()) {
            return; // fail-closed host — nothing to assert
        }
        PtySessionService pty = new PtySessionService();

        var started = pty.start("default", "u1", UUID.randomUUID(), null);
        assertThat(started.id()).isNotNull();

        assertThat(pty.write("default", started.id(), "echo PTY_MARKER_$((40+2))\n")).isTrue();

        // wait for the marker to appear in the ring
        String marker = null;
        for (int i = 0; i < 40 && marker == null; i++) {
            Thread.sleep(250);
            var read = pty.read("default", started.id(), 0, 100);
            marker = read.lines().stream()
                .map(PtySessionService.RingLine::text)
                .filter(t -> t.contains("PTY_MARKER_42"))
                .findFirst().orElse(null);
        }
        assertThat(marker).isNotNull();

        var tail = pty.read("default", started.id(), 0, 2_000);
        assertThat(tail.alive()).isTrue();
        assertThat(tail.cursor()).isGreaterThan(0);

        assertThat(pty.close("default", started.id())).isTrue();
        assertThat(pty.close("default", started.id())).isFalse(); // idempotent absence
    }

    @Test
    void ptyCrossProfileDenied() {
        if (!PtySessionService.ptyAvailable()) {
            return;
        }
        PtySessionService pty = new PtySessionService();
        var started = pty.start("profile-a", "u1", UUID.randomUUID(), null);
        assertThat(started.id()).isNotNull();

        assertThat(pty.write("profile-b", started.id(), "whoami\n")).isFalse();
        assertThat(pty.read("profile-b", started.id(), 0, 10).lines()).isEmpty();
        assertThat(pty.close("profile-b", started.id())).isFalse();
        pty.close("profile-a", started.id());
    }

    @Test
    void ptyCursorReplayNoDuplicates() throws Exception {
        if (!PtySessionService.ptyAvailable()) {
            return;
        }
        PtySessionService pty = new PtySessionService();
        var started = pty.start("default", "u1", UUID.randomUUID(), null);
        pty.write("default", started.id(), "echo line_one\n");
        Thread.sleep(1_500);

        var first = pty.read("default", started.id(), 0, 100);
        var tail = pty.read("default", started.id(), first.cursor(), 100);

        // strictly-after semantics: tail lines all have seq > first.cursor
        assertThat(tail.lines())
            .allSatisfy(line -> assertThat(line.seq()).isGreaterThan(first.cursor()));
        pty.close("default", started.id());
    }

    // ── pub channels ─────────────────────────────────────────────────────

    @Test
    void pubChannelAclAndReplay() {
        PubChannelRegistry pub = new PubChannelRegistry();

        assertThat(pub.create("profile-a", "events").allowed()).isTrue();
        // cross-profile cannot access
        assertThat(pub.create("profile-b", "events").allowed()).isFalse();
        assertThat(pub.publish("profile-b", "events", "tick", Map.of())).isEqualTo(-1);
        assertThat(pub.replay("profile-b", "events", 0, 10)).isEmpty();

        long s1 = pub.publish("profile-a", "events", "tick", Map.of("n", 1));
        long s2 = pub.publish("profile-a", "events", "tick", Map.of("n", 2));
        assertThat(s1).isEqualTo(1);
        assertThat(s2).isEqualTo(2);

        List<PubChannelRegistry.PubEvent> full = pub.replay("profile-a", "events", 0, 10);
        List<PubChannelRegistry.PubEvent> tail = pub.replay("profile-a", "events", 1, 10);
        assertThat(full).hasSize(2);
        assertThat(tail).hasSize(1);
        assertThat(tail.get(0).seq()).isEqualTo(2);

        assertThat(pub.list("profile-a")).containsExactly("events");
        assertThat(pub.list("profile-b")).isEmpty();
        assertThat(pub.delete("profile-a", "events")).isTrue();
        assertThat(pub.delete("profile-a", "events")).isFalse();
    }

    @Test
    void pubBackpressureDropsOldest() {
        PubChannelRegistry pub = new PubChannelRegistry();
        pub.create("default", "flood");
        for (int i = 1; i <= PubChannelRegistry.MAX_RETAINED_PER_CHANNEL + 100; i++) {
            pub.publish("default", "flood", "tick", Map.of("i", i));
        }
        List<PubChannelRegistry.PubEvent> retained = pub.replay("default", "flood", 0,
            Integer.MAX_VALUE);
        assertThat(retained).hasSize(PubChannelRegistry.MAX_RETAINED_PER_CHANNEL);
        // oldest dropped — the newest survived
        assertThat(retained.get(retained.size() - 1).payload()).containsEntry("i",
            PubChannelRegistry.MAX_RETAINED_PER_CHANNEL + 100);
    }

    @Test
    void pubInvalidNamesRejected() {
        PubChannelRegistry pub = new PubChannelRegistry();
        assertThat(pub.create("default", "bad name!").allowed()).isFalse();
        assertThat(pub.create("default", "../escape").allowed()).isFalse();
        assertThat(pub.create("default", "").allowed()).isFalse();
    }
}

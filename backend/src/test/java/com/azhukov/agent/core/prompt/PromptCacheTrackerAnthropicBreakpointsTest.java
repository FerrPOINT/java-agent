package com.azhukov.agent.core.prompt;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 coverage: Anthropic cache_control breakpoint injection of
 * {@link PromptCacheTracker#applyAnthropicCacheControl} — empty input guard,
 * system-prompt breakpoint, tool-message breakpoint, last-messages
 * breakpoints (capped at 4), String→parts content conversion, list-content
 * marker placement and the 1h TTL marker shape.
 */
class PromptCacheTrackerAnthropicBreakpointsTest {

    private final PromptCacheTracker tracker = new PromptCacheTracker(new AgentProperties());

    private static Map<String, Object> msg(String role, Object content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    void emptyOrNullInputIsReturnedAsIs() {
        assertThat(tracker.applyAnthropicCacheControl(null, "5m")).isNull();
        assertThat(tracker.applyAnthropicCacheControl(List.of(), "5m")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void systemPromptGetsFirstBreakpointWithStringContentConvertedToParts() {
        List<Map<String, Object>> api = List.of(
            msg("system", "you are an agent"),
            msg("user", "hello"));

        List<Map<String, Object>> out = tracker.applyAnthropicCacheControl(api, "5m");

        Map<String, Object> system = out.get(0);
        assertThat(system.get("content"))
            .isInstanceOf(List.class)
            .asList()
            .first()
            .isInstanceOf(Map.class);
        Map<String, Object> part = (Map<String, Object>) ((List<?>) system.get("content")).get(0);
        assertThat(part.get("text")).isEqualTo("you are an agent");
        Map<String, Object> marker = (Map<String, Object>) part.get("cache_control");
        assertThat(marker.get("type")).isEqualTo("ephemeral");
        assertThat(marker).doesNotContainKey("ttl");
    }

    @Test
    void oneHourTtlIsAppliedWhenRequested() {
        List<Map<String, Object>> api = List.of(msg("user", "hi"));
        List<Map<String, Object>> out = tracker.applyAnthropicCacheControl(api, "1h");
        Map<String, Object> content = (Map<String, Object>) ((List<?>) out.get(0).get("content")).get(0);
        Map<String, Object> marker = (Map<String, Object>) content.get("cache_control");
        assertThat(marker.get("ttl")).isEqualTo("1h");
    }

    @Test
    void toolMessageConsumesSecondBreakpoint() {
        List<Map<String, Object>> api = List.of(
            msg("system", "sys"),
            msg("tool", ""),
            msg("user", "u1"),
            msg("user", "u2"));

        List<Map<String, Object>> out = tracker.applyAnthropicCacheControl(api, "5m");

        // system + tool marked, then remaining breakpoints to the tail users
        assertThat(hasMarker(out.get(0))).isTrue();
        // empty-string tool content takes the bare cache_control field path
        assertThat(out.get(1)).containsKey("cache_control");
        assertThat(hasMarker(out.get(3))).isTrue();
    }

    @Test
    void atMostFourBreakpointsAreInjected() {
        List<Map<String, Object>> api = new ArrayList<>();
        api.add(msg("system", "sys"));
        for (int i = 0; i < 8; i++) {
            api.add(msg("user", "u" + i));
        }

        List<Map<String, Object>> out = tracker.applyAnthropicCacheControl(api, "5m");

        long marked = out.stream().filter(this::hasMarkerDeep).count();
        assertThat(marked).isLessThanOrEqualTo(4);
        // earliest non-system user is NOT marked (breakpoints went to the tail)
        assertThat(hasMarkerDeep(out.get(1))).isFalse();
    }

    @Test
    void listContentGetsMarkerOnLastElement() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "text");
        first.put("text", "part one");
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("type", "text");
        last.put("text", "part two");
        List<Map<String, Object>> api = List.of(msg("user", List.of(first, last)));

        List<Map<String, Object>> out = tracker.applyAnthropicCacheControl(api, "5m");

        List<?> content = (List<?>) out.get(0).get("content");
        Map<Object, Object> firstOut = (Map<Object, Object>) content.get(0);
        Map<Object, Object> lastOut = (Map<Object, Object>) content.get(1);
        assertThat(firstOut).doesNotContainKey((Object) "cache_control");
        assertThat(lastOut).containsKey((Object) "cache_control");
    }

    @Test
    void nullContentGetsBareCacheControlField() {
        List<Map<String, Object>> api = List.of(msg("user", null));
        List<Map<String, Object>> out = tracker.applyAnthropicCacheControl(api, "5m");
        assertThat(out.get(0)).containsKey("cache_control");
    }

    private boolean hasMarker(Map<String, Object> m) {
        Object c = m.get("content");
        if (m.containsKey("cache_control")) return true;
        if (c instanceof List<?> list && !list.isEmpty()
                && list.get(list.size() - 1) instanceof Map<?, ?> last) {
            return last.containsKey("cache_control");
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean hasMarkerDeep(Map<String, Object> m) {
        Object c = m.get("content");
        if (m.containsKey("cache_control")) return true;
        if (c instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> mm && ((Map<String, Object>) mm).containsKey("cache_control")) {
                    return true;
                }
            }
        }
        return false;
    }
}

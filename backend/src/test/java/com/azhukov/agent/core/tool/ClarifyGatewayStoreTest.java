package com.azhukov.agent.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClarifyGatewayStore contract (clarify_gateway.py parity): register → await
 * blocks → resolve unblocks with the answer; timeout returns null and drops
 * the entry; clearSession cancels waiters; double resolve is rejected.
 */
class ClarifyGatewayStoreTest {

    @Test
    void resolveUnblocksAwaitingToolThread() throws Exception {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        ClarifyGatewayStore.PendingClarify entry = store.register("s1", "Which?", List.of("a", "b"), false);

        Thread resolver = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            store.resolve(entry.clarifyId(), "b");
        });
        resolver.start();

        long start = System.nanoTime();
        String answer = store.awaitResponse(entry, 30);
        long waitedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(answer).isEqualTo("b");
        assertThat(waitedMs).isBetween(100L, 5000L);
        // entry cleaned up after resolution
        assertThat(store.pendingForSession("s1")).isNull();
        resolver.join();
    }

    @Test
    void timeoutReturnsNullAndDropsEntry() {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        ClarifyGatewayStore.PendingClarify entry = store.register("s1", "Q", List.of("a"), false);

        String answer = store.awaitResponse(entry, 1);

        assertThat(answer).isNull();
        assertThat(store.pendingForSession("s1")).isNull();
        // late resolve is refused — the tool already timed out
        assertThat(store.resolve(entry.clarifyId(), "a")).isFalse();
    }

    @Test
    void doubleResolveRejected() {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        ClarifyGatewayStore.PendingClarify entry = store.register("s1", "Q", List.of("a"), false);

        assertThat(store.resolve(entry.clarifyId(), "first")).isTrue();
        assertThat(store.resolve(entry.clarifyId(), "second")).isFalse();
    }

    @Test
    void clearSessionCancelsWaitersWithEmptyAnswer() throws Exception {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        ClarifyGatewayStore.PendingClarify entry = store.register("s1", "Q", List.of("a"), false);

        Thread clearer = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            store.clearSession("s1");
        });
        clearer.start();

        String answer = store.awaitResponse(entry, 30);

        // Cancelled waiter sees "" (Hermes clear_session), not a real reply
        assertThat(answer).isEmpty();
        clearer.join();
    }

    @Test
    void multiSelectFlagRequiresChoices() {
        ClarifyGatewayStore store = new ClarifyGatewayStore();
        ClarifyGatewayStore.PendingClarify withChoices = store.register("s1", "Q", List.of("a", "b"), true);
        ClarifyGatewayStore.PendingClarify openEnded = store.register("s2", "Q", List.of(), true);

        assertThat(withChoices.multiSelect()).isTrue();
        assertThat(openEnded.multiSelect()).isFalse();
        assertThat(openEnded.isAwaitingText()).isTrue();
    }
}

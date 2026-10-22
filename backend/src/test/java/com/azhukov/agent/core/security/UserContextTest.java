package com.azhukov.agent.core.security;

import com.pdlc.auth.HumanApplicationAccess;
import com.azhukov.agent.core.agent.StreamContext;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;

class UserContextTest {
    @AfterEach
    void clear() { UserContext.clear(); }

    private HumanApplicationAccess access(String role, boolean expired) {
        return new HumanApplicationAccess("human-fixture", role, Set.of("java-agent"), 1, true,
                Instant.now().plusSeconds(60), System.nanoTime() + (expired ? -1 : 60_000_000_000L));
    }

    @Test
    void humanAdministratorCannotSupplyAnotherAuthorButTechnicalGatewayRetainsItsContract() {
        UserContext.setCentral(access("admin", false));
        assertThat(UserContext.isAdmin()).isTrue();
        assertThat(UserContext.effectiveUserId("foreign-author")).isEqualTo("human-fixture");
        UserContext.set("technical-gateway", "admin");
        assertThat(UserContext.centralAccess()).isNull();
        assertThat(UserContext.effectiveUserId("telegram-author")).isEqualTo("telegram-author");
    }

    @Test
    void expiredProofCannotBeReusedAsRoleOrOwnerAndCallbackContextKeepsOriginalDeadline() {
        var proof = access("admin", true);
        UserContext.setCentral(proof);
        assertThat(UserContext.isAdmin()).isFalse();
        assertThatThrownBy(UserContext::getUserId).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(UserContext::getRole).isInstanceOf(AccessDeniedException.class);
        assertThat(new StreamContext(proof).hasCurrentAccess()).isFalse();
        UserContext.clear();
        assertThat(UserContext.centralAccess()).isNull();
        assertThat(new StreamContext().hasCurrentAccess()).isTrue();
    }

    @Test
    void queuedAndParallelTasksKeepOriginalProofAndRestoreWorkerIdentity() throws Exception {
        var proof = access("user", false);
        UserContext.setCentral(proof);
        var result = UserContext.wrapSupplier(() -> {
            assertThat(UserContext.centralAccess()).isSameAs(proof);
            assertThat(UserContext.getUserId()).isEqualTo("human-fixture");
            assertThat(UserContext.isAdmin()).isFalse();
            assertThat(UserContext.accessWaitMillis(120_000)).isLessThanOrEqualTo(60_000);
            return "owned";
        });
        UserContext.set("worker-owner", "admin");
        assertThat(result.get()).isEqualTo("owned");
        assertThat(UserContext.getUserId()).isEqualTo("worker-owner");
        assertThat(UserContext.isAdmin()).isTrue();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(result::get).get()).isEqualTo("owned");
            assertThat(executor.submit(UserContext::getUserId).get()).isNull();
        }
    }

    @Test
    void expiredQueuedProofCannotBecomeWorkerAdminOrRenewItsWindow() {
        var proof = access("admin", true);
        UserContext.setCentral(proof);
        var result = UserContext.wrapSupplier(UserContext::getUserId);
        var wait = UserContext.wrap(() -> UserContext.accessWaitMillis(30_000));
        UserContext.set("worker-owner", "admin");
        assertThatThrownBy(result::get).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(wait::run).isInstanceOf(AccessDeniedException.class);
        assertThat(UserContext.getUserId()).isEqualTo("worker-owner");
        assertThat(UserContext.isAdmin()).isTrue();
    }
}

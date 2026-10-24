package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.model.IdentityDeletionProtocol;
import com.azhukov.agent.core.ports.IdentityReferenceRegistration;
import com.azhukov.agent.core.security.CentralJwtVerifier;
import com.azhukov.agent.core.security.NativeIdentityOrigin;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.UserAccessService;
import com.pdlc.auth.HumanApplicationAccess;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** An admitted Auth identity still needs its identity-reference fence before controller dispatch. */
@ExtendWith(MockitoExtension.class)
class ApiKeyIdentityFenceTest {
    private static final String SUBJECT = "b4416454-387b-43cb-9502-41b24ab2854a";
    private static final String ISSUER = "https://auth.fixture.invalid";

    @AfterEach
    void clearIdentity() {
        UserContext.clear();
        NativeIdentityOrigin.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void aReferenceFailureBlocksTheControllerWithoutLeakingBearerOrFallingBackToGatewayAdmin() throws Exception {
        var verifier = mock(CentralJwtVerifier.class);
        var references = mock(IdentityReferenceRegistration.class);
        var users = mock(UserAccessService.class);
        var access = new HumanApplicationAccess(SUBJECT, "user", Set.of("java-agent"), 1, true,
                Instant.now().plusSeconds(60), System.nanoTime() + 60_000_000_000L);
        var window = new IdentityDeletionProtocol.Window(access.deadlineNanos(), access.expiresAt());
        when(verifier.verifyIdentity("synthetic-bearer", "POST"))
                .thenReturn(new CentralJwtVerifier.VerifiedIdentity(ISSUER, access, window));
        doThrow(new IdentityDeletionProtocol.Failure(503, "identity_reference_unavailable"))
                .when(references).central(ISSUER, SUBJECT, window);
        var properties = new AgentProperties();
        properties.getSecurity().setApiKey("synthetic-gateway");
        var filter = new ApiKeyAuthFilter(properties, users);
        var beans = new StaticListableBeanFactory();
        beans.addBean("central", verifier);
        beans.addBean("references", references);
        filter.configureCentralVerifier(beans.getBeanProvider(CentralJwtVerifier.class));
        filter.configureIdentityReferences(beans.getBeanProvider(IdentityReferenceRegistration.class));
        var request = new MockHttpServletRequest("POST", "/api/v2/sessions");
        request.addHeader("Authorization", "Bearer synthetic-bearer");
        request.addHeader("X-API-Key", "synthetic-gateway");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, outgoing) -> {
            throw new AssertionError("A refused identity reference reached the controller");
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("identity_reference_unavailable")
                .doesNotContain("synthetic-bearer", "synthetic-gateway", SUBJECT);
        assertThat(UserContext.getUserId()).isNull();
        assertThat(NativeIdentityOrigin.current()).isNull();
        verifyNoInteractions(users);
        verify(references).central(ISSUER, SUBJECT, window);
    }

    @Test
    void aNativeUserKeyCannotNameAnotherStorageScopeAndIsClearedAfterControllerFailure() {
        var properties = new AgentProperties();
        properties.getSecurity().setApiKey("synthetic-gateway");
        var users = mock(UserAccessService.class);
        when(users.authenticate("synthetic-user-key"))
                .thenReturn(new UserAccessService.AuthenticatedUser("native-user", "user"));
        var filter = new ApiKeyAuthFilter(properties, users);
        var request = new MockHttpServletRequest("POST", "/api/v2/sessions");
        request.addHeader("X-API-Key", "synthetic-user-key");
        var failure = new IllegalStateException("synthetic controller failure");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) -> {
            assertThat(UserContext.scopeUserId()).isEqualTo("native-user");
            assertThat(NativeIdentityOrigin.current().kind()).isEqualTo(NativeIdentityOrigin.Kind.USER_API_KEY);
            assertThat(NativeIdentityOrigin.current().permits("native-user")).isTrue();
            assertThat(NativeIdentityOrigin.current().permits("foreign-user")).isFalse();
            throw failure;
        })).isSameAs(failure);
        assertThat(UserContext.getUserId()).isNull();
        assertThat(NativeIdentityOrigin.current()).isNull();
    }
}

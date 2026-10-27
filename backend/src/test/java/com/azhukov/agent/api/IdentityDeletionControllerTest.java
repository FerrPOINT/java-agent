package com.azhukov.agent.api;

import com.azhukov.agent.api.filter.IdentityDeletionBoundaryFilter;
import com.azhukov.agent.core.model.IdentityDeletionProtocol.*;
import com.azhukov.agent.core.ports.IdentityDeletionOwner;
import com.azhukov.agent.core.security.IdentityCoordinatorVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.WebAsyncTask;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real private controller callable without a DB, Spring server, or
 * trusted transport substitution: malformed input must stop before owner IO. */
class IdentityDeletionControllerTest {
    private static final String BEARER = "Bearer pdlc_pat_own-fixture-not-a-credential";
    private final ObjectMapper mapper = new ObjectMapper();
    private final IdentityDeletionOwner owner = mock(IdentityDeletionOwner.class);
    private final IdentityCoordinatorVerifier verifier = mock(IdentityCoordinatorVerifier.class);
    private final Request request = new Request(1, UUID.randomUUID(),
            new Identity("http://auth.example.test", UUID.randomUUID().toString()), UUID.randomUUID());

    private IdentityDeletionController controller(boolean withOwner, boolean withVerifier) {
        var beans = new StaticListableBeanFactory();
        if (withOwner) beans.addBean("owner", owner);
        if (withVerifier) beans.addBean("verifier", verifier);
        return new IdentityDeletionController(beans.getBeanProvider(IdentityDeletionOwner.class),
                beans.getBeanProvider(IdentityCoordinatorVerifier.class), mapper);
    }

    private MockHttpServletRequest servlet(String body) {
        var servlet = new MockHttpServletRequest("POST", IdentityDeletionBoundaryFilter.PREFIX
                + request.operationId() + "/check");
        servlet.setContentType("application/json");
        servlet.setContent(body.getBytes(StandardCharsets.UTF_8));
        servlet.addHeader("Authorization", BEARER);
        return servlet;
    }

    private MockHttpServletRequest servlet() throws Exception {
        return servlet(mapper.writeValueAsString(request));
    }

    private ResponseEntity<?> response(WebAsyncTask<?> task) throws Exception {
        Object result = task.getCallable().call();
        assertThat(result).isInstanceOf(ResponseEntity.class);
        return (ResponseEntity<?>) result;
    }

    private void error(ResponseEntity<?> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(Map.of("error", Map.of("code", code)));
        assertThat(response.getBody().toString()).doesNotContain(BEARER);
    }

    @Test
    void eachCommandReturnsItsOwnReceiptAndPreservesOriginalBoundaryDeadline() throws Exception {
        Window window = new Window(System.nanoTime() + Duration.ofSeconds(8).toNanos(), Instant.now().plusSeconds(60));
        Summary summary = new Summary(request, "java-agent", 7, 3, true);
        Freeze freeze = new Freeze(request, "java-agent", UUID.randomUUID(), 7, 3);
        Release release = new Release(request, "java-agent", true);
        when(verifier.verify(eq(BEARER), eq(request), anyLong())).thenReturn(window);
        when(owner.check(request, window)).thenReturn(summary);
        when(owner.freeze(request, window)).thenReturn(freeze);
        when(owner.release(request, window)).thenReturn(release);
        long original = System.nanoTime() + Duration.ofSeconds(6).toNanos();
        var servlet = servlet();
        servlet.setAttribute(IdentityDeletionBoundaryFilter.DEADLINE_ATTRIBUTE, original);
        var controller = controller(true, true);
        Object[] receipts = {summary, freeze, release};
        int index = 0;
        for (var response : new ResponseEntity<?>[]{
                response(controller.check(request.operationId(), servlet)),
                response(controller.freeze(request.operationId(), servlet())),
                response(controller.release(request.operationId(), servlet()))}) {
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).isEqualTo(receipts[index++]);
        }
        verify(verifier).verify(BEARER, request, original);
        verify(owner).check(request, window);
        verify(owner).freeze(request, window);
        verify(owner).release(request, window);

    }

    @Test
    void strictWireRejectsMalformedUnknownDuplicateNullAndMismatchedOperationBeforeAuthority() throws Exception {
        String valid = mapper.writeValueAsString(request);
        for (String body : new String[]{"null", "{", valid.substring(0, valid.length() - 1) + ",\"secret\":\"fixture\"}",
                valid.replace("\"schema_version\":1", "\"schema_version\":null"),
                valid.substring(0, valid.length() - 1) + ",\"schema_version\":1}",
                valid.replace(request.identity().subject(), "native-untyped-principal")}) {
            error(response(controller(true, true).check(request.operationId(), servlet(body))),
                    400, "invalid_identity_deletion_request");
        }
        error(response(controller(true, true).check(UUID.randomUUID(), servlet())),
                400, "invalid_identity_deletion_request");
        verifyNoInteractions(owner, verifier);
    }

    @Test
    void streamedBodyLimitIsEnforcedEvenWithoutContentLength() throws Exception {
        var servlet = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        servlet.setContent(" ".repeat(16385).getBytes(StandardCharsets.UTF_8));
        error(response(controller(true, true).check(request.operationId(), servlet)),
                413, "identity_owner_body_too_large");
        verifyNoInteractions(owner, verifier);
    }

    @Test
    void missingConfigurationCannotReturnAnEmptyReferenceReceipt() throws Exception {
        for (boolean withOwner : new boolean[]{false, true}) {
            error(response(controller(withOwner, false).check(request.operationId(), servlet())),
                    503, "identity_owner_not_configured");
        }
        error(response(controller(false, true).check(request.operationId(), servlet())),
                503, "identity_owner_not_configured");
        verifyNoInteractions(owner, verifier);
    }

    @Test
    void missingOrAmbiguousCredentialCannotReachVerifierOrOwner() throws Exception {
        var absent = servlet();
        absent.removeHeader("Authorization");
        error(response(controller(true, true).check(request.operationId(), absent)),
                401, "identity_coordinator_required");
        var ambiguous = servlet();
        ambiguous.addHeader("Authorization", "Bearer fixture-second");
        error(response(controller(true, true).freeze(request.operationId(), ambiguous)),
                400, "identity_coordinator_header_ambiguous");
        verifyNoInteractions(owner, verifier);
    }

    @Test
    void verifierDenialAndStorageConflictKeepTheirSafeStatusAndNoStore() throws Exception {
        when(verifier.verify(eq(BEARER), eq(request), anyLong()))
                .thenThrow(new Failure(403, "identity_coordinator_capability_required"));
        error(response(controller(true, true).check(request.operationId(), servlet())),
                403, "identity_coordinator_capability_required");
        verifyNoInteractions(owner);
        Window window = new Window(System.nanoTime() + Duration.ofSeconds(8).toNanos(), Instant.now().plusSeconds(60));
        doReturn(window).when(verifier).verify(eq(BEARER), eq(request), anyLong());
        when(owner.freeze(request, window)).thenThrow(new Failure(409, "identity_deletion_operation_conflict"));
        error(response(controller(true, true).freeze(request.operationId(), servlet())),
                409, "identity_deletion_operation_conflict");
    }

    @Test
    void evenAnOwnerResultCannotEscapeAnExpiredOriginalWindow() throws Exception {
        Window expired = new Window(System.nanoTime() - 1, Instant.now().plusSeconds(60));
        when(verifier.verify(eq(BEARER), eq(request), anyLong())).thenReturn(expired);
        when(owner.check(request, expired)).thenReturn(new Summary(request, "java-agent", 7, 0, true));
        error(response(controller(true, true).check(request.operationId(), servlet())),
                401, "identity_authority_expired");
        verify(owner).check(request, expired);
    }

    @Test
    void inputIOExceptionReturnsOnlyStaticBadRequestWithoutAuthorityIO() throws Exception {
        HttpServletRequest broken = mock(HttpServletRequest.class);
        when(broken.getInputStream()).thenThrow(new IOException("synthetic fixture detail must not escape"));
        error(response(controller(true, true).release(request.operationId(), broken)),
                400, "invalid_identity_deletion_request");
        verifyNoInteractions(owner, verifier);
    }
}

package com.azhukov.agent.api.filter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class IdentityDeletionBoundaryFilterTest {
    private final IdentityDeletionBoundaryFilter filter = new IdentityDeletionBoundaryFilter();
    private static final String PRIVATE = IdentityDeletionBoundaryFilter.PREFIX
            + "11111111-1111-4111-8111-111111111111/check";

    @Test
    void privateRequestGetsNoStoreAndOriginalDeadlineBeforeDownstreamWork() throws Exception {
        var request = new MockHttpServletRequest("POST", PRIVATE);
        var response = new MockHttpServletResponse();
        long before = System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            calls.incrementAndGet();
            assertThat(incoming).isSameAs(request);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(request.getAttribute(IdentityDeletionBoundaryFilter.DEADLINE_ATTRIBUTE)).isInstanceOf(Long.class);
            long deadline = (Long) request.getAttribute(IdentityDeletionBoundaryFilter.DEADLINE_ATTRIBUTE);
            assertThat(deadline).isBetween(before + Duration.ofSeconds(9).toNanos(),
                    System.nanoTime() + Duration.ofSeconds(9).toNanos());
        });
        assertThat(calls.get()).isOne();
    }

    @Test
    void oversizedDeclaredBodyCannotReachAnyAuthenticationOrOwnerWork() throws Exception {
        var request = new MockHttpServletRequest("POST", PRIVATE);
        request.setContent(new byte[16385]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> fail("Oversized private body admitted"));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void exactLimitAndUnknownLengthStillEnterTheControllersOwnStreamBound() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                new MockHttpServletRequest("POST", PRIVATE),
                new MockHttpServletRequest("POST", PRIVATE) {
                    @Override public long getContentLengthLong() { return -1; }
                }}) {
            request.setContent(new byte[16384]);
            AtomicInteger calls = new AtomicInteger();
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (incoming, outgoing) -> calls.incrementAndGet());
            assertThat(calls.get()).isOne();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void publicAndPrefixLookalikeRoutesKeepTheirExistingBoundary() throws Exception {
        for (String path : new String[]{"/api/v1/sessions", "/actuator/health",
                "/api/internal/v1/user-deletions-other/check", "/api/internal/v1/user-deletions"}) {
            var request = new MockHttpServletRequest("GET", path);
            var response = new MockHttpServletResponse();
            AtomicInteger calls = new AtomicInteger();
            filter.doFilter(request, response, (incoming, outgoing) -> calls.incrementAndGet());
            assertThat(calls.get()).isOne();
            assertThat(IdentityDeletionBoundaryFilter.privateOwner(request)).isFalse();
            assertThat(request.getAttribute(IdentityDeletionBoundaryFilter.DEADLINE_ATTRIBUTE)).isNull();
            assertThat(response.getHeader("Cache-Control")).isNull();
        }
    }
}

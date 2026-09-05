package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import com.azhukov.agent.core.security.UserContext;
import com.azhukov.agent.service.UserAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the multi-user UserContext contract (commit 479026e)
 * that the PR-3 managed merge silently dropped: the filter must establish
 * UserContext on every authenticated path and always clear it afterwards.
 */
class ApiKeyAuthFilterUserContextTest {

    private static final String VALID_KEY = "secret-key-123";

    private final AgentProperties agentProperties = mock(AgentProperties.class);
    private final UserAccessService userAccessService = mock(UserAccessService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final FilterChain chain = mock(FilterChain.class);

    private final String[] captured = new String[2];

    private void securityKey(String key) {
        AgentProperties.SecurityProperties security = mock(AgentProperties.SecurityProperties.class);
        when(agentProperties.getSecurity()).thenReturn(security);
        when(security.getApiKey()).thenReturn(key);
    }

    private void requestTo(String uri, String apiKeyHeader) throws Exception {
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn("GET");
        if (apiKeyHeader != null) {
            when(request.getHeader("X-API-Key")).thenReturn(apiKeyHeader);
        }
        doAnswer(inv -> {
            captured[0] = UserContext.getUserId();
            captured[1] = UserContext.getRole();
            return null;
        }).when(chain).doFilter(any(), any());

        new ApiKeyAuthFilter(agentProperties, userAccessService).doFilter(request, response, chain);
    }

    @AfterEach
    void cleanup() {
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void globalKeyEstablishesAdminUserContext() throws Exception {
        securityKey(VALID_KEY);
        when(userAccessService.authenticate(any())).thenReturn(null);

        requestTo("/api/v1/agent/sessions", VALID_KEY);

        assertThat(captured[0]).isEqualTo(AgentProperties.DEFAULT_USER_ID);
        assertThat(captured[1]).isEqualTo(UserContext.ROLE_ADMIN);
        // ThreadLocal cleared after the request
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void perUserKeyEstablishesScopedUserContext() throws Exception {
        securityKey(VALID_KEY);
        when(userAccessService.authenticate("pk-user-key"))
            .thenReturn(new UserAccessService.AuthenticatedUser("user-42", "user"));

        requestTo("/api/v1/agent/sessions", "pk-user-key");

        assertThat(captured[0]).isEqualTo("user-42");
        assertThat(captured[1]).isEqualTo("user");
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void devModeEstablishesDefaultAdminUserContext() throws Exception {
        securityKey("");

        requestTo("/api/v1/agent/sessions", null);

        assertThat(captured[0]).isEqualTo(AgentProperties.DEFAULT_USER_ID);
        assertThat(captured[1]).isEqualTo(UserContext.ROLE_ADMIN);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void failedAuthLeavesUserContextEmpty() throws Exception {
        securityKey(VALID_KEY);
        when(userAccessService.authenticate(any())).thenReturn(null);
        when(response.getWriter()).thenReturn(mock(java.io.PrintWriter.class));

        requestTo("/api/v1/agent/sessions", "bad-key");

        assertThat(UserContext.getUserId()).isNull();
    }
}

package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyAuthFilterTest {

    private static final String VALID_KEY = "secret-key-123";
    private static final String WRONG_KEY = "wrong-key-999";
    private static final String API_PATH = "/api/v1/agent/chat";
    private static final String HEALTH_PATH = "/actuator/health";
    private static final String HEALTH_LIVENESS_PATH = "/actuator/health/liveness";
    private static final String ROOT_HEALTH_PATH = "/health";
    private static final String V1_HEALTH_PATH = "/v1/health";
    private static final String DETAILED_HEALTH_PATH = "/health/detailed";
    private static final String PROFILE_ROOT_HEALTH_PATH = "/p/work/health";
    private static final String PROFILE_V1_HEALTH_PATH = "/p/work/v1/health";
    private static final String PROFILE_PLATFORM_EVENT_PATH = "/p/work/api/platforms/telegram/events";

    private AgentProperties agentProperties;
    private ApiKeyAuthFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        agentProperties = new AgentProperties();
        filter = new ApiKeyAuthFilter(agentProperties);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    // ─── No key configured → auth disabled ───

    @Test
    void noKeyConfiguredAllowsAllRequests() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("");
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void nullKeyConfiguredAllowsAllRequests() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(null);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void blankKeyConfiguredAllowsAllRequests() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("   ");
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    // ─── Correct key → pass ───

    @Test
    void correctBearerAuthorizationHeaderPassesThrough() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void correctApiKeyHeaderPassesThrough() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn(VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void correctApiKeyQueryParamPassesThrough() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    // ─── Wrong key → 401 ───

    @Test
    void wrongBearerAuthorizationHeaderReturns401() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + WRONG_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
        assertThat(responseWriter.toString()).contains("gateway_auth_error");
        assertThat(responseWriter.toString()).contains("gateway_auth_failed");
    }

    @Test
    void bearerHeaderTakesPrecedenceOverLegacyApiKeyHeader() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + WRONG_KEY);
        when(request.getHeader("X-API-Key")).thenReturn(VALID_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
    }

    @Test
    void wrongApiKeyHeaderReturns401() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn(WRONG_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
        assertThat(responseWriter.toString()).contains("gateway_auth_error");
        assertThat(responseWriter.toString()).contains("Invalid gateway API key");
    }

    @Test
    void wrongApiKeyQueryParamReturns401() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(WRONG_KEY);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
        assertThat(responseWriter.toString()).contains("gateway_auth_error");
    }

    // ─── Missing key when configured → 401 ───

    @Test
    void missingApiKeyWhenConfiguredReturns401() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
        assertThat(responseWriter.toString()).contains("gateway_auth_error");
    }

    @Test
    void emptyApiKeyHeaderWhenConfiguredReturns401() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn("");
        when(request.getParameter("api_key")).thenReturn("");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
    }

    // ─── Health endpoints exempt ───

    @Test
    void healthEndpointExemptFromAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(HEALTH_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void healthLivenessSubpathExemptFromAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(HEALTH_LIVENESS_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void healthReadinessSubpathExemptFromAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn("/actuator/health/readiness");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void actuatorHealthPrefixLookalikeRequiresAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn("/actuator/healthy");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
    }

    @Test
    void rootHermesHealthEndpointExemptFromAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(ROOT_HEALTH_PATH);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void v1HermesHealthEndpointExemptFromAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(V1_HEALTH_PATH);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void profilePrefixedHermesHealthEndpointsExemptFromAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(PROFILE_ROOT_HEALTH_PATH, PROFILE_V1_HEALTH_PATH);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);
        filter.doFilter(request, response, chain);

        verify(chain, times(2)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void detailedHermesHealthEndpointRequiresAuth() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(DETAILED_HEALTH_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response, times(1)).setStatus(401);
    }

    @Test
    void platformEventCallbackBypassesApiServerKey() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn("/api/platforms/telegram/events");
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer platform-token");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    @Test
    void profilePrefixedPlatformEventCallbackBypassesApiServerKey() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(PROFILE_PLATFORM_EVENT_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Authorization")).thenReturn("Bearer platform-token");

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }

    // ─── Response format ───

    @Test
    void unauthorizedResponseIsJsonWithCorrectContentType() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("X-API-Key")).thenReturn(WRONG_KEY);

        filter.doFilter(request, response, chain);

        verify(response).setStatus(401);
        verify(response).setContentType("application/json");
        assertThat(responseWriter.toString()).contains("\"error\"");
        assertThat(responseWriter.toString()).contains("\"type\":\"gateway_auth_error\"");
        assertThat(responseWriter.toString()).contains("\"code\":\"gateway_auth_failed\"");
        assertThat(responseWriter.toString()).contains("\"message\":\"Invalid gateway API key");
    }

    // ─── Header takes precedence over query param ───

    @Test
    void headerTakesPrecedenceOverQueryParam() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey(VALID_KEY);
        when(request.getRequestURI()).thenReturn(API_PATH);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(VALID_KEY);
        when(request.getParameter("api_key")).thenReturn(WRONG_KEY);

        filter.doFilter(request, response, chain);

        // Header is correct → passes through even though query param is wrong
        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(401);
    }
}

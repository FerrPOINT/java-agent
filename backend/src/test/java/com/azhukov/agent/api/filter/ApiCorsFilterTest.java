package com.azhukov.agent.api.filter;

import com.azhukov.agent.config.AgentProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiCorsFilterTest {

    private AgentProperties agentProperties;
    private ApiCorsFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        agentProperties = new AgentProperties();
        filter = new ApiCorsFilter(agentProperties);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @Test
    void requestWithoutOriginPassesThrough() throws ServletException, IOException {
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(403);
    }

    @Test
    void preflightWithoutOriginReturnsForbidden() throws ServletException, IOException {
        when(request.getHeader("Origin")).thenReturn(null);
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void browserOriginWithoutConfiguredAllowlistReturnsForbidden() throws ServletException, IOException {
        when(request.getHeader("Origin")).thenReturn("https://app.example");
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void localhostOriginIsAllowedByDefaultLikeHermesDashboardCors() throws ServletException, IOException {
        when(request.getHeader("Origin")).thenReturn("http://localhost:5173");
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "http://localhost:5173");
        verify(response).setHeader("Vary", "Origin");
        verify(chain).doFilter(request, response);
    }

    @Test
    void allowedOriginAddsCorsHeadersAndPassesThrough() throws ServletException, IOException {
        agentProperties.getApi().setCorsOrigins(List.of("https://app.example"));
        when(request.getHeader("Origin")).thenReturn("https://app.example");
        when(request.getMethod()).thenReturn("POST");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "https://app.example");
        verify(response).setHeader("Access-Control-Allow-Methods", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT");
        verify(response).setHeader("Access-Control-Allow-Headers",
            "Authorization, Content-Type, Idempotency-Key, X-API-Key, X-Hermes-Session-Id, X-Hermes-Session-Key, X-Hermes-Session-Token");
        verify(response).setHeader("Access-Control-Max-Age", "600");
        verify(response).setHeader("Vary", "Origin");
        verify(chain).doFilter(request, response);
    }

    @Test
    void allowedPreflightCompletesBeforeAuthChain() throws ServletException, IOException {
        agentProperties.getApi().setCorsOrigins(List.of("https://app.example"));
        when(request.getHeader("Origin")).thenReturn("https://app.example");
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(200);
        verify(response).setHeader("Access-Control-Allow-Origin", "https://app.example");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowedPreflightEchoesRequestedHeadersLikeWildcardCors() throws ServletException, IOException {
        when(request.getHeader("Origin")).thenReturn("http://127.0.0.1:3000");
        when(request.getHeader("Access-Control-Request-Headers"))
            .thenReturn("authorization, x-custom-dashboard-header");
        when(request.getMethod()).thenReturn("OPTIONS");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(200);
        verify(response).setHeader("Access-Control-Allow-Headers",
            "authorization, x-custom-dashboard-header");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void wildcardOriginAllowsAnyBrowserOrigin() throws ServletException, IOException {
        agentProperties.getApi().setCorsOrigins(List.of("*"));
        when(request.getHeader("Origin")).thenReturn("https://random.example");
        when(request.getMethod()).thenReturn("GET");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Access-Control-Allow-Origin", "*");
        verify(response, never()).setHeader("Vary", "Origin");
        verify(chain).doFilter(request, response);
    }
}

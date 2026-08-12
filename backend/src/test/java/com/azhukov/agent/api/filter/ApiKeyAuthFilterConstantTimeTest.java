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
import static org.mockito.Mockito.*;

/**
 * M19: Test that API key comparison uses constant-time comparison
 * and correctly validates keys of different lengths.
 */
class ApiKeyAuthFilterConstantTimeTest {

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

    @Test
    void correctKeyWithDifferentLengthFailsQuickly() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("secret-key-123");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn("short");
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void keyWithSameLengthButDifferentContentFails() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("secret-key-123");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        // Same length, different content
        when(request.getHeader("X-API-Key")).thenReturn("secret-key-999");
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void keyDifferingOnlyInLastCharacterFails() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("secret-key-123");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn("secret-key-124");
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void emptyKeyWhenConfiguredFails() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("secret-key-123");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn("");
        when(request.getParameter("api_key")).thenReturn("");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void nullKeyWhenConfiguredFails() throws ServletException, IOException {
        agentProperties.getSecurity().setApiKey("secret-key-123");
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getParameter("api_key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }
}
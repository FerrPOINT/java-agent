package com.azhukov.agent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiBodyLimitFilterTest {

    private ApiBodyLimitFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws IOException {
        filter = new ApiBodyLimitFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);

        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    void postWithinLimitPassesThrough() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Content-Length")).thenReturn(String.valueOf(ApiBodyLimitFilter.MAX_REQUEST_BYTES));

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(any(HttpServletRequest.class), eq(response));
        verify(response, never()).setStatus(413);
    }

    @Test
    void getWithLargeContentLengthPassesThroughLikeHermes() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Content-Length")).thenReturn(String.valueOf(ApiBodyLimitFilter.MAX_REQUEST_BYTES + 1));

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
        verify(response, never()).setStatus(413);
    }

    @Test
    void postOverLimitReturnsHermesBodyTooLargeError() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Content-Length")).thenReturn(String.valueOf(ApiBodyLimitFilter.MAX_REQUEST_BYTES + 1));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(413);
        verify(response).setContentType("application/json");
        verify(response).setCharacterEncoding("UTF-8");
        assertThat(responseWriter.toString()).contains("\"message\":\"Request body too large.\"");
        assertThat(responseWriter.toString()).contains("\"type\":\"invalid_request_error\"");
        assertThat(responseWriter.toString()).contains("\"param\":null");
        assertThat(responseWriter.toString()).contains("\"code\":\"body_too_large\"");
    }

    @Test
    void invalidContentLengthReturnsHermesError() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("PATCH");
        when(request.getHeader("Content-Length")).thenReturn("oops");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(400);
        assertThat(responseWriter.toString()).contains("\"message\":\"Invalid Content-Length header.\"");
        assertThat(responseWriter.toString()).contains("\"param\":null");
        assertThat(responseWriter.toString()).contains("\"code\":\"invalid_content_length\"");
    }

    @Test
    void negativeContentLengthReturnsInvalidContentLength() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("PUT");
        when(request.getHeader("Content-Length")).thenReturn("-1");

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(400);
        assertThat(responseWriter.toString()).contains("\"code\":\"invalid_content_length\"");
    }

    @Test
    void postWithoutContentLengthOverLimitReturns413DuringBodyRead() throws ServletException, IOException {
        MockHttpServletRequest requestWithoutHeader = new MockHttpServletRequest("POST", "/v1/chat/completions");
        requestWithoutHeader.setContent(new byte[(int) ApiBodyLimitFilter.MAX_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain readingChain = (servletRequest, servletResponse) -> servletRequest.getInputStream().readAllBytes();

        filter.doFilter(requestWithoutHeader, response, readingChain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
            .contains("\"message\":\"Request body too large.\"")
            .contains("\"code\":\"body_too_large\"");
    }
}

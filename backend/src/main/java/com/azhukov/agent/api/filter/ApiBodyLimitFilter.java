package com.azhukov.agent.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiBodyLimitFilter extends OncePerRequestFilter {

    static final long MAX_REQUEST_BYTES = 10_000_000L;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean requestBodyMethod = hasRequestBody(request);
        if (requestBodyMethod) {
            String contentLength = request.getHeader("Content-Length");
            if (contentLength != null) {
                Long parsed = parseContentLength(contentLength);
                if (parsed == null) {
                    writeOpenAiError(response, HttpStatus.BAD_REQUEST,
                        "Invalid Content-Length header.", "invalid_content_length");
                    return;
                }
                if (parsed > MAX_REQUEST_BYTES) {
                    writeOpenAiError(response, HttpStatus.PAYLOAD_TOO_LARGE,
                        "Request body too large.", "body_too_large");
                    return;
                }
            }
        }

        try {
            filterChain.doFilter(
                requestBodyMethod ? new LimitedBodyRequest(request, MAX_REQUEST_BYTES) : request,
                response);
        } catch (BodyTooLargeException e) {
            if (!response.isCommitted()) {
                writeOpenAiError(response, HttpStatus.PAYLOAD_TOO_LARGE,
                    "Request body too large.", "body_too_large");
                return;
            }
            throw e;
        }
    }

    private static boolean hasRequestBody(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equalsIgnoreCase(method)
            || "PUT".equalsIgnoreCase(method)
            || "PATCH".equalsIgnoreCase(method);
    }

    private static Long parseContentLength(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void writeOpenAiError(HttpServletResponse response,
                                         HttpStatus status,
                                         String message,
                                         String code) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"message\":\"" + message
            + "\",\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"" + code + "\"}}");
    }

    static class BodyTooLargeException extends IOException {
        BodyTooLargeException(long maxBytes) {
            super("Request body exceeded " + maxBytes + " bytes");
        }
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {
        private final long maxBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new LimitedServletInputStream(super.getInputStream(), maxBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                Charset charset = getCharacterEncoding() != null
                    ? Charset.forName(getCharacterEncoding())
                    : StandardCharsets.UTF_8;
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class LimitedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead;

        private LimitedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(long count) throws BodyTooLargeException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new BodyTooLargeException(maxBytes);
            }
        }
    }
}

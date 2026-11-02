package com.azhukov.agent.client.mcp;

import com.azhukov.agent.core.security.UserContext;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Binds the SDK-generated SSE endpoint to its authenticated opener. */
final class OwnedMcpSseServlet extends HttpServlet {
    private volatile HttpServletSseServerTransportProvider delegate;
    private volatile boolean destroyed;
    private record Binding(String owner, Object generation) { }
    private final ConcurrentMap<String, Binding> owners = new ConcurrentHashMap<>();

    OwnedMcpSseServlet(HttpServletSseServerTransportProvider delegate) {
        this.delegate = delegate;
    }

    synchronized void replaceDelegate(HttpServletSseServerTransportProvider replacement) {
        if (destroyed) throw new IllegalStateException("MCP servlet is destroyed");
        owners.clear();
        delegate = replacement;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String owner = UserContext.getUserId();
        if (owner == null || owner.isBlank()) {
            response.sendError(401);
            return;
        }
        HttpServletSseServerTransportProvider requestDelegate = delegate;
        var wrapper = new HttpServletResponseWrapper(response) {
            private PrintWriter writer;
            @Override public PrintWriter getWriter() throws IOException {
                if (writer == null) {
                    PrintWriter target = super.getWriter();
                    writer = new PrintWriter(new Writer() {
                        private final StringBuilder buffer = new StringBuilder();
                        private boolean bound;
                        private String sessionId;
                        private final Binding binding = new Binding(owner, new Object());
                        private void verifyTarget() throws IOException {
                            if (target.checkError()) {
                                if (sessionId != null) owners.remove(sessionId, binding);
                                throw new IOException("MCP stream write failed");
                            }
                        }
                        @Override public void write(char[] chars, int offset, int length) throws IOException {
                            String text = new String(chars, offset, length);
                            if (!bound) {
                                buffer.append(text);
                                int end = buffer.indexOf("\n\n");
                                if (end < 0) {
                                    if (buffer.length() > 8192) throw new IOException("MCP endpoint event too large");
                                    return;
                                }
                                String event = buffer.substring(0, end);
                                String id = endpointSessionId(event);
                                synchronized (OwnedMcpSseServlet.this) {
                                    if (destroyed || requestDelegate != delegate || id == null || owners.putIfAbsent(id, binding) != null)
                                        throw new IOException("Invalid or stale MCP endpoint event");
                                }
                                sessionId = id;
                                try {
                                    request.getAsyncContext().addListener(new AsyncListener() {
                                        private void cleanup() { owners.remove(id, binding); }
                                        @Override public void onComplete(AsyncEvent event) { cleanup(); }
                                        @Override public void onTimeout(AsyncEvent event) { cleanup(); }
                                        @Override public void onError(AsyncEvent event) { cleanup(); }
                                        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                                    });
                                } catch (RuntimeException ex) {
                                    owners.remove(id, binding);
                                    throw new IOException("Cannot bind MCP async session", ex);
                                }
                                bound = true;
                                target.write(buffer.toString());
                                verifyTarget();
                                buffer.setLength(0);
                            } else {
                                target.write(text);
                                verifyTarget();
                            }
                        }
                        @Override public void flush() throws IOException { if (bound) { target.flush(); verifyTarget(); } }
                        @Override public void close() { target.close(); }
                    });
                }
                return writer;
            }
        };
        requestDelegate.service(request, wrapper);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String[] ids = request.getParameterValues("sessionId");
        String id = ids != null && ids.length == 1 ? ids[0] : null;
        String owner = UserContext.getUserId();
        Binding binding = id == null ? null : owners.get(id);
        if (owner == null || binding == null || !owner.equals(binding.owner())) {
            response.setStatus(404);
            return;
        }
        delegate.service(request, response);
    }

    private static String endpointSessionId(String event) {
        String data = null;
        boolean endpoint = false;
        for (String line : event.split("\\r?\\n")) {
            if (line.equals("event: endpoint") || line.equals("event:endpoint")) endpoint = true;
            if (line.startsWith("data:")) data = line.substring(5).trim();
        }
        if (!endpoint || data == null) return null;
        try {
            String query = URI.create(data).getRawQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && parts[0].equals("sessionId")) {
                    String id = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    return java.util.UUID.fromString(id).toString().equals(id) ? id : null;
                }
            }
        } catch (IllegalArgumentException ignored) { }
        return null;
    }

    @Override public synchronized void destroy() {
        destroyed = true;
        owners.clear();
        delegate.destroy();
        super.destroy();
    }
}

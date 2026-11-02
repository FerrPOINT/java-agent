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
    private final ConcurrentMap<String, String> owners = new ConcurrentHashMap<>();

    OwnedMcpSseServlet(HttpServletSseServerTransportProvider delegate) {
        this.delegate = delegate;
    }

    synchronized void replaceDelegate(HttpServletSseServerTransportProvider replacement) {
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
        var wrapper = new HttpServletResponseWrapper(response) {
            private PrintWriter writer;
            @Override public PrintWriter getWriter() throws IOException {
                if (writer == null) {
                    PrintWriter target = super.getWriter();
                    writer = new PrintWriter(new Writer() {
                        private final StringBuilder buffer = new StringBuilder();
                        private boolean bound;
                        private String sessionId;
                        private void verifyTarget() throws IOException {
                            if (target.checkError()) {
                                if (sessionId != null) owners.remove(sessionId, owner);
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
                                if (id == null || owners.putIfAbsent(id, owner) != null) throw new IOException("Invalid MCP endpoint event");
                                sessionId = id;
                                try {
                                    request.getAsyncContext().addListener(new AsyncListener() {
                                        private void cleanup() { owners.remove(id, owner); }
                                        @Override public void onComplete(AsyncEvent event) { cleanup(); }
                                        @Override public void onTimeout(AsyncEvent event) { cleanup(); }
                                        @Override public void onError(AsyncEvent event) { cleanup(); }
                                        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                                    });
                                } catch (RuntimeException ex) {
                                    owners.remove(id, owner);
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
        delegate.service(request, wrapper);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String[] ids = request.getParameterValues("sessionId");
        String id = ids != null && ids.length == 1 ? ids[0] : null;
        String owner = UserContext.getUserId();
        if (id == null || owner == null || !owner.equals(owners.get(id))) {
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

    @Override public void destroy() {
        owners.clear();
        delegate.destroy();
        super.destroy();
    }
}

package com.azhukov.agent.client.mcp;

import com.azhukov.agent.core.security.UserContext;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnedMcpSseServletTest {
    @AfterEach void clear() { UserContext.clear(); }

    @Test
    void bindingCleanupFromOldGenerationCannotEraseNewGeneration() throws Exception {
        var oldDelegate = mock(HttpServletSseServerTransportProvider.class);
        var newDelegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(oldDelegate);
        var firstReq = mock(HttpServletRequest.class);
        var secondReq = mock(HttpServletRequest.class);
        var firstResp = mock(HttpServletResponse.class);
        var secondResp = mock(HttpServletResponse.class);
        var firstAsync = mock(AsyncContext.class);
        var secondAsync = mock(AsyncContext.class);
        when(firstReq.getAsyncContext()).thenReturn(firstAsync);
        when(secondReq.getAsyncContext()).thenReturn(secondAsync);
        when(firstResp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(secondResp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        org.mockito.stubbing.Answer<Void> emit = inv -> {
            var writer = ((HttpServletResponse) inv.getArgument(1)).getWriter();
            writer.write("event: endpoint\ndata: /mcp/message?sessionId=123e4567-e89b-12d3-a456-426614174000\n\n");
            writer.flush();
            return null;
        };
        doAnswer(emit).when(oldDelegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        doAnswer(emit).when(newDelegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        UserContext.set("alice", "user");
        servlet.doGet(firstReq, firstResp);
        var listener = ArgumentCaptor.forClass(AsyncListener.class);
        verify(firstAsync).addListener(listener.capture());
        servlet.replaceDelegate(newDelegate);
        servlet.doGet(secondReq, secondResp);
        listener.getValue().onComplete(new AsyncEvent(firstAsync));
        var post = mock(HttpServletRequest.class);
        var result = mock(HttpServletResponse.class);
        when(post.getParameterValues("sessionId")).thenReturn(new String[]{"123e4567-e89b-12d3-a456-426614174000"});
        reset(newDelegate);
        servlet.doPost(post, result);
        verify(newDelegate).service(post, result);
    }

    @Test
    void pendingGetCannotReintroduceBindingAfterDelegateReplacement() throws Exception {
        var oldDelegate = mock(HttpServletSseServerTransportProvider.class);
        var newDelegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(oldDelegate);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var async = mock(AsyncContext.class);
        when(request.getAsyncContext()).thenReturn(async);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            assertThat(resume.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var writer = ((HttpServletResponse) inv.getArgument(1)).getWriter();
            writer.write("event: endpoint\ndata: /mcp/message?sessionId=123e4567-e89b-12d3-a456-426614174000\n\n");
            writer.flush();
            return null;
        }).when(oldDelegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        var thread = new Thread(() -> {
            UserContext.set("alice", "user");
            try { servlet.doGet(request, response); }
            catch (Exception e) { throw new RuntimeException(e); }
            finally { UserContext.clear(); }
        });
        thread.start();
        try {
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            servlet.replaceDelegate(newDelegate);
            resume.countDown();
            thread.join(5000);
            assertThat(thread.isAlive()).isFalse();
            var post = mock(HttpServletRequest.class);
            var result = mock(HttpServletResponse.class);
            when(post.getParameterValues("sessionId")).thenReturn(new String[]{"123e4567-e89b-12d3-a456-426614174000"});
            UserContext.set("alice", "user");
            servlet.doPost(post, result);
            verify(result).setStatus(404);
            verifyNoInteractions(newDelegate);
        } finally { resume.countDown(); thread.join(5000); }
    }

    @Test
    void cleanupRacingWithEndpointPublicationDoesNotRecreateBinding() throws Exception {
        var delegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(delegate);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var async = mock(AsyncContext.class);
        when(request.getAsyncContext()).thenReturn(async);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        doAnswer(inv -> { ((AsyncListener) inv.getArgument(0)).onComplete(new AsyncEvent(async)); return null; })
            .when(async).addListener(any(AsyncListener.class));
        doAnswer(inv -> {
            var writer = ((HttpServletResponse) inv.getArgument(1)).getWriter();
            writer.write("event: endpoint\ndata: /mcp/message?sessionId=123e4567-e89b-12d3-a456-426614174000\n\n");
            writer.flush();
            return null;
        }).when(delegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        UserContext.set("alice", "user");
        servlet.doGet(request, response);
        var post = mock(HttpServletRequest.class);
        var postResponse = mock(HttpServletResponse.class);
        when(post.getParameterValues("sessionId")).thenReturn(new String[]{"123e4567-e89b-12d3-a456-426614174000"});
        servlet.doPost(post, postResponse);
        verify(postResponse).setStatus(404);
    }

    @Test
    void duplicateSessionParameterCannotReachSdk() throws Exception {
        var delegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(delegate);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        when(request.getParameterValues("sessionId")).thenReturn(new String[]{"first", "second"});
        UserContext.set("alice", "user");
        servlet.doPost(request, response);
        verify(response).setStatus(404);
        verifyNoInteractions(delegate);
    }

    @Test
    void invalidEndpointEventCannotPublishOrCreateBinding() throws Exception {
        var delegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(delegate);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var output = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        doAnswer(inv -> {
            var writer = ((HttpServletResponse) inv.getArgument(1)).getWriter();
            writer.write("event: message\ndata: /mcp/message?sessionId=123e4567-e89b-12d3-a456-426614174000\n\n");
            writer.flush();
            assertThat(writer.checkError()).isTrue();
            return null;
        }).when(delegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        UserContext.set("alice", "user");
        servlet.doGet(request, response);
        assertThat(output.toString()).isEmpty();
        var post = mock(HttpServletRequest.class);
        var postResponse = mock(HttpServletResponse.class);
        when(post.getParameterValues("sessionId")).thenReturn(new String[]{"123e4567-e89b-12d3-a456-426614174000"});
        servlet.doPost(post, postResponse);
        verify(postResponse).setStatus(404);
    }

    @Test
    void failedEndpointWriteDoesNotLeaveUsableBinding() throws Exception {
        var delegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(delegate);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var async = mock(AsyncContext.class);
        var broken = new PrintWriter(new java.io.Writer() {
            @Override public void write(char[] chars, int off, int len) throws java.io.IOException { throw new java.io.IOException("closed"); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        when(request.getAsyncContext()).thenReturn(async);
        when(response.getWriter()).thenReturn(broken);
        doAnswer(inv -> {
            var writer = ((HttpServletResponse) inv.getArgument(1)).getWriter();
            writer.write("event: endpoint\ndata: /mcp/message?sessionId=123e4567-e89b-12d3-a456-426614174000\n\n");
            writer.flush();
            assertThat(writer.checkError()).isTrue();
            return null;
        }).when(delegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        UserContext.set("alice", "user");
        servlet.doGet(request, response);
        var post = mock(HttpServletRequest.class);
        var postResponse = mock(HttpServletResponse.class);
        when(post.getParameterValues("sessionId")).thenReturn(new String[]{"123e4567-e89b-12d3-a456-426614174000"});
        servlet.doPost(post, postResponse);
        verify(postResponse).setStatus(404);
    }

    @Test
    void asyncCompletionRemovesOnlyOwnedBinding() throws Exception {
        var delegate = mock(HttpServletSseServerTransportProvider.class);
        var servlet = new OwnedMcpSseServlet(delegate);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var async = mock(AsyncContext.class);
        var output = new StringWriter();
        when(request.getAsyncContext()).thenReturn(async);
        when(response.getWriter()).thenReturn(new PrintWriter(output));
        doAnswer(inv -> {
            var wrapped = (HttpServletResponse) inv.getArgument(1);
            var writer = wrapped.getWriter();
            writer.write("event: endpoint\n");
            writer.write("data: /mcp/message?sessionId=123e4567-e89b-12d3-a456-426614174000\n\n");
            writer.flush();
            return null;
        }).when(delegate).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
        UserContext.set("alice", "user");
        servlet.doGet(request, response);
        assertThat(output.toString()).contains("event: endpoint");
        var listener = ArgumentCaptor.forClass(AsyncListener.class);
        verify(async).addListener(listener.capture());
        listener.getValue().onComplete(new AsyncEvent(async));
        var post = mock(HttpServletRequest.class);
        var postResponse = mock(HttpServletResponse.class);
        when(post.getParameterValues("sessionId")).thenReturn(new String[]{"123e4567-e89b-12d3-a456-426614174000"});
        servlet.doPost(post, postResponse);
        verify(postResponse).setStatus(404);
        verify(delegate, times(1)).service(any(HttpServletRequest.class), any(HttpServletResponse.class));
    }
}

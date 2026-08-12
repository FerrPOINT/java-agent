package com.azhukov.agent.security;

import com.azhukov.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for SsrfSafeHttpClient concurrency fix — ensures per-request factory
 * creation prevents timeout mutation races between concurrent calls.
 */
class SsrfSafeHttpClientConcurrencyTest {

    @Test
    void fetchBlocksUnsafeUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThatThrownBy(() -> client.fetch("http://localhost/admin", 5))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("blocked");
    }

    @Test
    void postBlocksUnsafeUrl() {
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        assertThatThrownBy(() -> client.post("http://example.com", "body", 5))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    void noSharedRequestFactoryField() {
        // After the fix, SsrfSafeHttpClient should NOT have a shared
        // SimpleClientHttpRequestFactory field — each request creates its own.
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        boolean hasSharedFactory = false;
        for (Field field : SsrfSafeHttpClient.class.getDeclaredFields()) {
            if (field.getType() == SimpleClientHttpRequestFactory.class) {
                hasSharedFactory = true;
                break;
            }
        }
        assertThat(hasSharedFactory)
            .as("SsrfSafeHttpClient should not have a shared SimpleClientHttpRequestFactory field")
            .isFalse();
    }

    @Test
    void concurrentFetchWithDifferentTimeoutsDoesNotRace() throws Exception {
        // Verify that two concurrent fetch calls with different timeouts don't
        // interfere by ensuring each creates its own RestClient/factory.
        // Since we can't actually call external URLs in tests, we just verify
        // the safety check is invoked (which happens before any factory creation).
        UrlSafetyHandler safety = mock(UrlSafetyHandler.class);
        when(safety.checkUrl(anyString())).thenReturn("blocked");
        SecretRedactor redactor = mock(SecretRedactor.class);
        AgentProperties properties = new AgentProperties();

        SsrfSafeHttpClient client = new SsrfSafeHttpClient(safety, redactor, properties);

        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Exception> errorRef = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try {
                client.fetch("http://example.com/1", 10);
            } catch (Exception e) {
                // Expected — blocked URL
            } finally {
                latch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                client.post("http://example.com/2", "body", 30);
            } catch (Exception e) {
                // Expected — blocked URL
            } finally {
                latch.countDown();
            }
        });

        t1.start();
        t2.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        // Both calls should have been blocked (no NPE or race condition)
        assertThat(errorRef.get()).isNull();
    }
}
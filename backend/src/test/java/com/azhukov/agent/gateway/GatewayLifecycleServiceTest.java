package com.azhukov.agent.gateway;

import com.azhukov.agent.service.ProfileRuntimeRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

class GatewayLifecycleServiceTest {

    @Test
    void startsRunningAndAcceptsInbound() {
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null);
        assertThat(lifecycle.currentState()).isEqualTo(GatewayLifecycleService.State.STOPPED);
        lifecycle.start();
        assertThat(lifecycle.currentState()).isEqualTo(GatewayLifecycleService.State.RUNNING);
        assertThat(lifecycle.acceptsInbound()).isTrue();
        assertThat(lifecycle.beginInbound()).isTrue();
        assertThat(lifecycle.activeInboundCount()).isEqualTo(1);
        lifecycle.endInbound();
        assertThat(lifecycle.activeInboundCount()).isZero();
    }

    @Test
    void drainRejectsNewInboundImmediatelyAndWaitsBounded() throws Exception {
        CountDownLatch draining = new CountDownLatch(1);
        ProfileRuntimeRegistry runtimeRegistry = mock(ProfileRuntimeRegistry.class);
        doAnswer(invocation -> {
            draining.countDown();
            return null;
        }).when(runtimeRegistry).updateWorkerState("default", "draining");
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null, runtimeRegistry);
        lifecycle.start();
        assertThat(lifecycle.beginInbound()).isTrue(); // one in-flight

        CountDownLatch drained = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            lifecycle.drain(Duration.ofMillis(200));
            drained.countDown();
        });
        // While draining: new inbound must be rejected.
        assertThat(draining.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.beginInbound()).isFalse();
        lifecycle.endInbound(); // in-flight completes

        assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.activeInboundCount()).isZero();
        assertThat(lifecycle.currentState()).isEqualTo(GatewayLifecycleService.State.STOPPED);
    }

    @Test
    void zeroDrainBoundReportsStillActiveInbound() {
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null);
        lifecycle.start();
        assertThat(lifecycle.beginInbound()).isTrue();
        GatewayLifecycleService.Status status = lifecycle.drain(Duration.ZERO);
        assertThat(status.state()).isEqualTo(GatewayLifecycleService.State.STOPPED);
        assertThat(status.activeInbound()).isEqualTo(1);
        assertThat(lifecycle.beginInbound()).isFalse();
    }

    @Test
    void stopAbandonsWithoutWaiting() {
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null);
        lifecycle.start();
        assertThat(lifecycle.beginInbound()).isTrue();
        GatewayLifecycleService.Status status = lifecycle.stop();
        assertThat(status.state()).isEqualTo(GatewayLifecycleService.State.STOPPED);
        assertThat(status.activeInbound()).isEqualTo(1); // abandoned, reported
        assertThat(lifecycle.acceptsInbound()).isFalse();
    }

    @Test
    void restartDrainsThenRuns() {
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null);
        lifecycle.start();
        GatewayLifecycleService.Status status = lifecycle.restart(Duration.ofMillis(100));
        assertThat(status.state()).isEqualTo(GatewayLifecycleService.State.RUNNING);
        assertThat(lifecycle.acceptsInbound()).isTrue();
    }

    @Test
    void startIsIdempotent() {
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null);
        lifecycle.start();
        GatewayLifecycleService.Status again = lifecycle.start();
        assertThat(again.state()).isEqualTo(GatewayLifecycleService.State.RUNNING);
    }

    @Test
    void lifecycleTransitionsArePersistedIntoDefaultProfileRuntimeState() {
        ProfileRuntimeRegistry runtimeRegistry = mock(ProfileRuntimeRegistry.class);
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null, runtimeRegistry);

        lifecycle.start();
        lifecycle.drain(Duration.ZERO);
        lifecycle.fail("adapter down");

        verify(runtimeRegistry).updateWorkerState("default", "running");
        verify(runtimeRegistry).updateWorkerState("default", "stopped");
        verify(runtimeRegistry).updateWorkerState("default", "failed");
    }

    @Test
    void failRecordsError() {
        GatewayLifecycleService lifecycle = new GatewayLifecycleService(null);
        lifecycle.start();
        GatewayLifecycleService.Status status = lifecycle.fail("poller crashed");
        assertThat(status.state()).isEqualTo(GatewayLifecycleService.State.FAILED);
        assertThat(status.lastError()).isEqualTo("poller crashed");
        assertThat(lifecycle.beginInbound()).isFalse();
    }
}

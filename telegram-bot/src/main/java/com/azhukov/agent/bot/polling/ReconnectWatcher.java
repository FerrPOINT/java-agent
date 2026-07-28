package com.azhukov.agent.bot.polling;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ReconnectWatcher {

    private static final Logger log = LoggerFactory.getLogger(ReconnectWatcher.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telegram-reconnect-worker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile double currentDelay = 5000;

    public void start(Runnable pollLoop) {
        running.set(true);
        currentDelay = 5000;
        workerExecutor.submit(pollLoop);
    }

    public void scheduleReconnect(Runnable pollLoop) {
        if (!running.get()) return;
        double delay = currentDelay;
        double maxDelay = 60000;
        double multiplier = 1.5;
        currentDelay = Math.min(currentDelay * multiplier, maxDelay);

        log.info("Scheduling reconnect in {}ms (backoff)", (long) delay);
        scheduler.schedule(() -> {
            if (running.get()) {
                workerExecutor.submit(pollLoop);
            }
        }, (long) delay, TimeUnit.MILLISECONDS);
    }

    public void resetBackoff() {
        currentDelay = 5000;
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        scheduler.shutdownNow();
        workerExecutor.shutdownNow();
    }
}
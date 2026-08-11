package com.azhukov.agent.bot.monitor;

import lombok.extern.slf4j.Slf4j;

import java.lang.management.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory Monitor — periodic process memory usage logging.
 *
 * <p>Ported from the original project's {@code gateway/memory_monitor.py}. Emits a single
 * structured {@code [MEMORY] ...} line every N minutes (default 5) so
 * maintainers investigating a suspected leak can grep agent.log for a
 * time series of RSS + GC stats.
 *
 * <p>Design notes (parity with the Python port):
 * <ul>
 * <li>Grep-friendly single-line format beginning {@code [MEMORY]}.</li>
 * <li>Final snapshot logged on shutdown so "last RSS before exit" is always in the log.</li>
 * <li>Baseline snapshot logged immediately on start.</li>
 * <li>Daemon thread — never blocks process exit.</li>
 * </ul>
 */
@Slf4j
public class MemoryMonitor {

 private static final long BYTES_TO_MB = 1024 * 1024;
 private static final long DEFAULT_INTERVAL_SECONDS = 300;

 private final ScheduledExecutorService scheduler;
 private final long intervalSeconds;
 private final AtomicBoolean running = new AtomicBoolean(false);
 private final AtomicLong startTime = new AtomicLong(0);
 private ScheduledFuture<?> monitorTask;

 public MemoryMonitor() {
 this(DEFAULT_INTERVAL_SECONDS);
 }

 public MemoryMonitor(long intervalSeconds) {
 this.intervalSeconds = intervalSeconds;
 this.scheduler = Executors.newScheduledThreadPool(1, r -> {
 Thread t = new Thread(r, "java-memory-monitor");
 t.setDaemon(true);
 return t;
 });
 }

 /** Start periodic memory usage logging. Safe to call once. */
 public boolean start() {
 if (!running.compareAndSet(false, true)) {
 return false;
 }
 startTime.set(System.nanoTime());

 // Baseline snapshot
 logMemoryUsage("baseline");

 monitorTask = scheduler.scheduleAtFixedRate(() -> {
 try {
 logMemoryUsage("");
 } catch (Exception e) {
 log.debug("Memory monitor iteration failed: {}", e.getMessage());
 }
 }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

 log.info("[MEMORY] Periodic memory monitoring started (interval: {}s)", (int) intervalSeconds);
 return true;
 }

 /** Stop the monitor and log a final snapshot. Safe to call even if never started. */
 public void stop() {
 if (!running.compareAndSet(true, false)) {
 return;
 }
 try {
 logMemoryUsage("shutdown");
 } catch (Exception e) {
 // ignore
 }
 if (monitorTask != null) {
 monitorTask.cancel(false);
 }
 scheduler.shutdown();
 try {
 scheduler.awaitTermination(2, TimeUnit.SECONDS);
 } catch (InterruptedException e) {
 Thread.currentThread().interrupt();
 }
 log.info("[MEMORY] Periodic memory monitoring stopped");
 }

 /** Check if the monitor is running. */
 public boolean isRunning() {
 return running.get();
 }

 /** Log current memory usage in a grep-friendly [MEMORY] line. */
 public void logMemoryUsage(String prefix) {
 long rssMb = getRssMb();
 long uptime = startTime.get() > 0 ? TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime.get()) : 0;
 long[] gcCounts = getGcCounts();
 int threadCount = getThreadCount();

 String tag = prefix.isEmpty() ? "" : prefix + " ";
 if (rssMb < 0) {
 log.info("[MEMORY] {}rss=unavailable gc={} threads={} uptime={}s",
 tag, Arrays.toString(gcCounts), threadCount, uptime);
 } else {
 log.info("[MEMORY] {}rss={}MB gc={} threads={} uptime={}s",
 tag, rssMb, Arrays.toString(gcCounts), threadCount, uptime);
 }
 }

 /** Get current process RSS in MB. Returns -1 if unavailable. */
 public long getRssMb() {
 try {
 MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
 // JVM heap usage as a proxy for RSS — not exactly RSS but useful for leak detection
 long usedHeap = memoryBean.getHeapMemoryUsage().getUsed();
 long usedNonHeap = memoryBean.getNonHeapMemoryUsage().getUsed();
 return (usedHeap + usedNonHeap) / BYTES_TO_MB;
 } catch (Exception e) {
 return -1;
 }
 }

 /** Get GC collection counts per generation. */
 public long[] getGcCounts() {
 try {
 java.util.List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
 return gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).toArray();
 } catch (Exception e) {
 return new long[0];
 }
 }

 /** Get active thread count. */
 public int getThreadCount() {
 try {
 ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
 return threadBean.getThreadCount();
 } catch (Exception e) {
 return 0;
 }
 }
}
package com.trading.telemetry;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

import com.lmax.disruptor.RingBuffer;

/**
 * Fluent entry point for launching a telemetry dashboard.
 *
 * <p>
 * Registration (counters, gauges, disruptors) and display configuration
 * (thresholds, stat visibility) are intentionally separate concerns:
 *
 * <pre>{@code
 * // 1. Build and start
 * TelemetryDashboard dashboard = TelemetryDashboard.create()
 *         .port(8080)
 *         .jvmMetrics()
 *         .start();
 *
 * // 2. Register metrics
 * LongAdder orders = dashboard.counter("orders_created");
 * dashboard.disruptor(orderRing, "orders");
 *
 * // 3. Configure chart display
 * dashboard.chart("orders_ring_backpressure").warnAt(50).critAt(80).showAvg().and();
 * }</pre>
 */
public class TelemetryDashboard {

    private final TelemetryRegistry registry;
    private int port = 8080;
    private String title = "Disruptor Telemetry";
    private int pollIntervalSec = 1;
    private int snapshotHistory = 0; // Default to no history for zero memory overhead
    private TelemetryServer server;

    private TelemetryDashboard() {
        this.registry = new TelemetryRegistry();
    }

    TelemetryDashboard(TelemetryRegistry registry) {
        this.registry = registry;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static TelemetryDashboard create() {
        return new TelemetryDashboard();
    }

    /** HTTP/WebSocket port (default: 8080). */
    public TelemetryDashboard port(int port) {
        this.port = port;
        return this;
    }

    /** Dashboard title shown in the browser tab. */
    public TelemetryDashboard title(String title) {
        this.title = title;
        return this;
    }

    /** Polling interval in seconds (default: 1). */
    public TelemetryDashboard pollInterval(int seconds) {
        this.pollIntervalSec = seconds;
        return this;
    }

    /**
     * Seconds of history to buffer for replay on browser connect (default: 28800 =
     * 8 h).
     */
    public TelemetryDashboard snapshotHistory(int seconds) {
        this.snapshotHistory = seconds;
        return this;
    }

    /**
     * Bind standard JVM metrics (heap, GC, threads) with sensible chart defaults.
     */
    public TelemetryDashboard jvmMetrics() {
        JvmMetricsBinder.bind(registry);
        return this;
    }

    /**
     * Bind a Disruptor RingBuffer — auto-creates backpressure and cursor charts.
     */
    public TelemetryDashboard disruptor(RingBuffer<?> ringBuffer, String name) {
        DisruptorMetricsBinder.bind(registry, ringBuffer, name);
        return this;
    }

    /**
     * Start the HTTP/WebSocket server. Returns {@code this} for post-start
     * configuration.
     */
    public TelemetryDashboard start() {
        try {
            server = new TelemetryServer(registry, port, pollIntervalSec, title, snapshotHistory);
            int boundPort = server.start();
            if (boundPort != -1) {
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
                System.out.println("📊 Telemetry Dashboard → http://localhost:" + boundPort);
            }
        } catch (Exception e) {
            System.err.println("[TelemetryDashboard] Failed to start: " + e.getMessage() + ". Telemetry disabled.");
        }
        return this;
    }

    public void stop() {
        if (server != null)
            server.stop();
    }

    // ── Metric registration ───────────────────────────────────────────────────

    /** Register a sampled gauge. The supplier is called every poll tick. */
    public TelemetryDashboard gauge(String name, DoubleSupplier supplier) {
        registry.gauge(name, supplier);
        return this;
    }

    /** Register a counter backed by a custom {@link LongSupplier}. */
    public TelemetryDashboard counter(String name, LongSupplier supplier) {
        registry.counter(name, supplier);
        return this;
    }

    /**
     * Create and return a managed {@link LongAdder} counter. Call
     * {@code counter.increment()} on your hot path.
     */
    public LongAdder counter(String name) {
        return registry.counter(name);
    }

    // ── Chart display configuration ───────────────────────────────────────────

    /**
     * Open a display-configuration builder for one or more metric names.
     * Chain threshold and stat-visibility options, then call
     * {@link MetricConfig#and()}
     * to commit and return here.
     *
     * <pre>{@code
     * dashboard.chart("orders_ring_backpressure").warnAt(50).critAt(80).showAvg().and()
     *         .chart("orders_created").showMin().showMax().and();
     * }</pre>
     */
    public MetricConfig chart(String... metricNames) {
        return new MetricConfig(this, registry, metricNames);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    TelemetryRegistry registry() {
        return registry;
    }
}

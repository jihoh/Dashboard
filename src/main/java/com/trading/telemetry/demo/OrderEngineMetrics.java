package com.trading.telemetry.demo;

import com.trading.telemetry.TelemetryDashboard;

/**
 * Central dashboard configuration for the Order Engine demo.
 *
 * <p>Defines all metric name constants so raw strings never appear in application
 * code, and applies all chart display settings (thresholds, stat visibility) in
 * one auditable place.
 *
 * <p>Usage:
 * <pre>{@code
 * dashboard.disruptor(orderRing, OrderEngineMetrics.ORDERS);
 * OrderEngineMetrics.configure(dashboard);
 * }</pre>
 */
public class OrderEngineMetrics {

    private OrderEngineMetrics() {}

    // ── Disruptor prefixes (passed to disruptor()) ────────────────────────────
    public static final String ORDERS      = "orders";
    public static final String MARKET_DATA = "market_data";

    // ── Counters ──────────────────────────────────────────────────────────────
    public static final String ORDERS_CREATED   = "orders_created";
    public static final String ORDERS_CANCELLED = "orders_cancelled";
    public static final String ORDERS_FILLED    = "orders_filled";
    public static final String MD_RECEIVED      = "market_data_received";

    // ── Gauges ────────────────────────────────────────────────────────────────
    public static final String ACTIVE_CONN_US_EAST = "active_connections/us_east";
    public static final String ACTIVE_CONN_EU_WEST = "active_connections/eu_west";

    // ── Chart display configuration ───────────────────────────────────────────

    /**
     * Apply all chart display settings to the dashboard.
     * Call this once after all metrics have been registered.
     */
    public static void configure(TelemetryDashboard dashboard) {
        dashboard
            // Active connections (shared thresholds across both regions)
            .chart(ACTIVE_CONN_US_EAST, ACTIVE_CONN_EU_WEST)
                .warnAt(45).critAt(50)
                .and()
            // Order counters
            .chart(ORDERS_CREATED, ORDERS_FILLED)
                .showMin().showMax()
                .and()
            .chart(ORDERS_CANCELLED)
                .hideStats()
                .and();
    }
}

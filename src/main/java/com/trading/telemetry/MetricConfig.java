package com.trading.telemetry;

import java.util.EnumSet;

/**
 * Fluent builder for configuring how a metric is displayed on the dashboard.
 *
 * <p>Obtain via {@link TelemetryDashboard#chart(String...)}. Chain threshold
 * and stat-visibility options, then call {@link #and()} to commit all settings
 * at once and return to the dashboard builder.
 *
 * <pre>{@code
 * dashboard.chart("orders_ring_backpressure")
 *          .warnAt(50).critAt(80)
 *          .showAvg()
 *          .and()
 *          .chart("active_connections/us_east", "active_connections/eu_west")
 *          .warnAt(45).critAt(50)
 *          .and();
 * }</pre>
 */
public class MetricConfig {

    private final TelemetryDashboard dashboard;
    private final TelemetryRegistry  registry;
    private final String[]           metricNames;

    private double       warnThreshold = Double.NaN;
    private double       critThreshold = Double.NaN;
    private EnumSet<Stat> stats        = null;  // null = "never configured" → show all (default)
    private String       unit          = null;

    MetricConfig(TelemetryDashboard dashboard, TelemetryRegistry registry, String... metricNames) {
        this.dashboard   = dashboard;
        this.registry    = registry;
        this.metricNames = metricNames;
    }

    // ── Thresholds ────────────────────────────────────────────────────────────

    /** Yellow dashed line at {@code value}. */
    public MetricConfig warnAt(double value) {
        this.warnThreshold = value;
        return this;
    }

    /** Red dashed line at {@code value}. */
    public MetricConfig critAt(double value) {
        this.critThreshold = value;
        return this;
    }

    // ── Units ─────────────────────────────────────────────────────────────────

    /** Suffix to display next to the value (e.g., "%", "MB"). */
    public MetricConfig unit(String unit) {
        this.unit = unit;
        return this;
    }

    // ── Stats visibility ──────────────────────────────────────────────────────

    public MetricConfig showMin() { return addStat(Stat.MIN); }
    public MetricConfig showMax() { return addStat(Stat.MAX); }
    public MetricConfig showAvg() { return addStat(Stat.AVG); }

    /** Show all three stats (MIN, MAX, AVG). */
    public MetricConfig showAllStats() {
        stats = EnumSet.allOf(Stat.class);
        return this;
    }

    /** Hide all stats from the panel header. */
    public MetricConfig hideStats() {
        stats = EnumSet.noneOf(Stat.class);
        return this;
    }

    // ── Terminal ──────────────────────────────────────────────────────────────

    /**
     * Commit all configured options to the registry in a single pass and
     * return to the dashboard builder for further chaining.
     */
    public TelemetryDashboard and() {
        boolean hasThreshold = !Double.isNaN(warnThreshold) || !Double.isNaN(critThreshold);
        for (String name : metricNames) {
            if (hasThreshold) registry.threshold(name, warnThreshold, critThreshold);
            if (stats != null) registry.stats(name, stats.toArray(new Stat[0]));
            if (unit != null) registry.unit(name, unit);
        }
        return dashboard;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private MetricConfig addStat(Stat stat) {
        if (stats == null) stats = EnumSet.noneOf(Stat.class);
        stats.add(stat);
        return this;
    }
}

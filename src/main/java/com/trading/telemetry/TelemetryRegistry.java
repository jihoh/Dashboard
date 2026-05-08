package com.trading.telemetry;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * Thread-safe registry for telemetry metrics.
 * Register counters and gauges here — each one becomes a chart on the
 * dashboard.
 */
public class TelemetryRegistry {

    public static class Metric<T> {
        public final String group;
        public final String name;
        public final T supplier;

        public Metric(String group, String name, T supplier) {
            this.group = group;
            this.name = name;
            this.supplier = supplier;
        }
    }

    private final List<Metric<DoubleSupplier>> doubleGauges = new CopyOnWriteArrayList<>();
    private final List<Metric<LongSupplier>> longCounters = new CopyOnWriteArrayList<>();

    private final ConcurrentHashMap<String, LongAdder> managedCounters = new ConcurrentHashMap<>();

    // ── Registration ──────────────────────────────────────────────────────────

    public void registerGauge(String group, String name, DoubleSupplier supplier) {
        upsert(doubleGauges, group, name, supplier);
    }

    public void registerCounter(String group, String name, LongSupplier supplier) {
        upsert(longCounters, group, name, supplier);
    }

    private <T> void upsert(List<Metric<T>> list, String group, String name, T supplier) {
        for (int i = 0, n = list.size(); i < n; i++) {
            Metric<T> m = list.get(i);
            if (m.name.equals(name) && m.group.equals(group)) {
                list.set(i, new Metric<>(group, name, supplier));
                return;
            }
        }
        list.add(new Metric<>(group, name, supplier));
    }

    // ── Convenience: default group "custom" ───────────────────────────────────

    /** Register a sampled gauge. The supplier is polled every tick. */
    public void gauge(String name, DoubleSupplier supplier) {
        registerGauge("custom", name, supplier);
    }

    /** Register a counter backed by a LongSupplier. */
    public void counter(String name, LongSupplier supplier) {
        registerCounter("custom", name, supplier);
    }

    /**
     * Create and return a managed {@link LongAdder} counter.
     * Call {@code counter.increment()} on your hot path.
     * Idempotent — returns the same instance on repeated calls with the same name.
     */
    public LongAdder counter(String name) {
        return managedCounters.computeIfAbsent(name, k -> {
            LongAdder adder = new LongAdder();
            registerCounter("custom", k, adder::sum);
            return adder;
        });
    }

    // ── Accessors (used by TelemetryServer) ───────────────────────────────────

    public List<Metric<DoubleSupplier>> getDoubleGauges() {
        return doubleGauges;
    }

    public List<Metric<LongSupplier>> getLongCounters() {
        return longCounters;
    }

    // ── Thresholds ────────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, double[]> thresholds = new ConcurrentHashMap<>();

    /**
     * Set warn and crit thresholds for a metric. Use {@link Double#NaN} to leave a
     * level unset.
     */
    public void threshold(String name, double warn, double crit) {
        thresholds.put(name, new double[] { warn, crit });
    }

    public ConcurrentHashMap<String, double[]> getThresholds() {
        return thresholds;
    }

    // ── Stats display config ──────────────────────────────────────────────────

    private final ConcurrentHashMap<String, Set<Stat>> statsConfig = new ConcurrentHashMap<>();

    /**
     * Configure which stats to display for a metric.
     * No args = show no stats. Omitting this call entirely = show all (default).
     */
    public void stats(String name, Stat... stats) {
        statsConfig.put(name, stats.length == 0
                ? EnumSet.noneOf(Stat.class)
                : EnumSet.copyOf(List.of(stats)));
    }

    public ConcurrentHashMap<String, Set<Stat>> getStatsConfig() {
        return statsConfig;
    }

    // ── Units ─────────────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, String> units = new ConcurrentHashMap<>();

    /** Set the display unit for a metric (e.g., "%", "MB/s"). */
    public void unit(String name, String unit) {
        units.put(name, unit);
    }

    public ConcurrentHashMap<String, String> getUnits() {
        return units;
    }
}

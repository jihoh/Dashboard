# Disruptor Telemetry Dashboard

Zero-allocation, real-time telemetry for Java applications. Drop in a few lines, open a browser, get Grafana-style live charts.

## Quick Start

```java
// 1. Launch the dashboard
TelemetryDashboard dashboard = TelemetryDashboard.create()
        .port(8080)
        .title("Order Engine")
        .jvmMetrics()   // heap, GC, threads — auto-registered with sensible defaults
        .start();           // → http://localhost:8080

// 2. Register metrics (each one = a new chart)
LongAdder ordersCreated = dashboard.counter("orders_created");
dashboard.gauge("queue_depth", () -> myQueue.size());

// 3. On your hot path, just increment
ordersCreated.increment();
```

Open `http://localhost:8080`. That's it.

---

## Adding Metrics

### Counters

```java
// Managed counter — returns a LongAdder, call increment() on your hot path
LongAdder filled = dashboard.counter("orders_filled");
filled.increment();

// External supplier — wrap any existing value source
dashboard.counter("events_processed", myAdder::sum);
```

### Gauges

```java
// Any DoubleSupplier — sampled every poll tick
dashboard.gauge("cpu_percent",    () -> osMxBean.getCpuLoad() * 100);
dashboard.gauge("queue_depth",    () -> myQueue.size());
dashboard.gauge("cache_hit_rate", () -> hits.get() / (double) total.get() * 100);
```

**Tip:** Use a `/` in the name to merge related series into a single chart:
```java
dashboard.gauge("active_connections/us_east", ...);
dashboard.gauge("active_connections/eu_west", ...);
// → both appear as series on one "active_connections" chart
```

### Disruptor Ring Buffers

```java
// One line → auto-creates a backpressure-% chart and a cursor chart
// Default chart settings (thresholds, stats) are applied automatically
dashboard.disruptor(orderRingBuffer, "orders");
dashboard.disruptor(mdRingBuffer,    "market_data");
```

This produces four metrics: `orders_ring_backpressure`, `orders_cursor`,
`market_data_ring_backpressure`, `market_data_cursor`.

### JVM Metrics

```java
dashboard.jvmMetrics();
// Registers: heapUsed, heapMax, threads, youngGcCount, oldGcCount
// Default chart settings applied automatically — no extra config needed.
// JVM uptime is shown in the topbar automatically.
```

---

## Chart Display Configuration

Use `dashboard.chart(...)` to configure thresholds and stat visibility per chart.
Settings are committed lazily in a single pass when you call `.and()`.

```java
dashboard
    .chart("orders_ring_backpressure")
        .warnAt(50).critAt(80)   // yellow / red dashed threshold lines
        .showAvg()
        .and()
    .chart("active_connections/us_east", "active_connections/eu_west")
        .warnAt(45).critAt(50)   // same thresholds applied to both
        .and()
    .chart("orders_created")
        .showMin().showMax()     // hide avg
        .and()
    .chart("orders_cancelled")
        .hideStats()             // no stats panel
        .and();
```

| Method | Effect |
|---|---|
| `.warnAt(n)` | Yellow dashed line at `n` |
| `.critAt(n)` | Red dashed line at `n` |
| `.showMin()` / `.showMax()` / `.showAvg()` | Show individual stats |
| `.showAllStats()` | Show MIN, MAX, AVG |
| `.hideStats()` | Hide all stats |
| `.and()` | Commit and return to dashboard |

### Recommended Pattern: a Metrics Config Class

For any non-trivial app, centralise all metric names and chart settings in one class:

```java
public class MyAppMetrics {

    // Constants — no raw strings anywhere in application code
    public static final String ORDERS      = "orders";
    public static final String ORDERS_CREATED   = "orders_created";
    public static final String ORDERS_CANCELLED = "orders_cancelled";

    // All chart display config in one auditable place
    public static void configure(TelemetryDashboard dashboard) {
        dashboard
            .chart(ORDERS_CREATED).showMin().showMax().and()
            .chart(ORDERS_CANCELLED).hideStats().and();
    }
}
```

Then in your app:

```java
dashboard.disruptor(orderRing, MyAppMetrics.ORDERS);
MyAppMetrics.configure(dashboard);
```

---

## Full Configuration Options

```java
TelemetryDashboard dashboard = TelemetryDashboard.create()
        .port(9090)               // HTTP/WebSocket port (default: 8080)
        .title("My Service")      // browser tab title
        .pollInterval(1)          // seconds between metric snapshots (default: 1)
        .snapshotHistory(3600)    // seconds of history buffered for replay (default: 28800 = 8 h)
        .jvmMetrics()
        .start();
```

---

## Dashboard Features

| Feature | Description |
|---|---|
| **Auto-discovery** | New metrics appear as charts automatically |
| **Threshold lines** | Configurable warn (yellow) and crit (red) dashed lines |
| **Min / Max / Avg** | Per-chart stat visibility — configurable per metric |
| **DataZoom slider** | Click & drag the bottom slider to pan through history |
| **Mouse wheel zoom** | Scroll inside any chart to zoom the time axis |
| **Pause / Resume** | Freeze updates to inspect a snapshot (buffering continues in background) |
| **Maximize** | Full-screen any panel |
| **Time window** | Switch between 30 s, 1 m, 5 m, 15 m, 30 m views |
| **History replay** | New browser tabs receive buffered history immediately on connect |
| **msg/s counter** | Real-time WebSocket throughput in the topbar |

---

## Architecture

```
Your App                         TelemetryDashboard
   │                                     │
   │  counter.increment()                │
   │  gauge supplier called              │
   │────────────────────────────────────►│
   │                                     │  poll every N seconds
   │                                     │──────────────►  WebSocket broadcast
   │                                     │                 (zero-alloc JSON)
   │                               Browser (ECharts)
   │                                     │
   │                              Live charts with
   │                              rAF-batched rendering
```

- **Backend**: Javalin WebSocket server, zero-allocation `StringBuilder` JSON serialisation
- **Frontend**: Apache ECharts, `requestAnimationFrame`-batched updates
- **Transport**: WebSocket at `/ws/telemetry`
- **History**: ring buffer (`snapshotHistory` seconds deep), replayed to new clients on connect

### Hot-Path Safety

This library is designed for low-latency systems. The **only thing your trading thread touches** is `LongAdder.increment()` (~5 ns). All collection, serialisation, and broadcasting runs on a separate daemon thread.

```
Trading Thread (hot path)          Publisher Thread (cold, 1 Hz)
─────────────────────────          ─────────────────────────────
ordersCreated.increment()  ←5ns   reads LongAdder.sum()
                                   polls DoubleSupplier gauges
                                   builds JSON (reused StringBuilder)
                                   broadcasts to WebSocket clients
```

| Concern | Status |
|---|---|
| **Locks on hot path** | None. `LongAdder` uses cell-striping, no CAS on the fast path |
| **Allocation on hot path** | Zero. `increment()` mutates a pre-allocated cell |
| **Shared mutable state** | Only `LongAdder` cells — read by publisher via `sum()`, written by your thread via `increment()`. No contention |
| **GC pressure** | ~1 String per poll tick (1/sec). `StringBuilder` is reused. Negligible |
| **Thread affinity** | Publisher is a daemon thread — won't pin your cores |

---

## Running the Demo

```bash
mvn compile exec:java -Dexec.mainClass="com.trading.telemetry.demo.TelemetryDemoApp"
```

Then open http://localhost:8080.

The demo simulates an order engine with two Disruptors (one with an intentionally slow consumer to generate visible backpressure), order/fill/cancel counters, active-connection gauges, and GC pressure — all wired up through `OrderEngineMetrics`.

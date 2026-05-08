package com.trading.telemetry;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * WebSocket server that polls registered metrics and broadcasts JSON to connected clients.
 */
public class TelemetryServer {
    private final TelemetryRegistry registry;
    private final int port;
    private final Javalin app;
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private Thread publisherThread;
    private final int pollIntervalSec;
    private final String title;
    private final char[] dblBuf = new char[8]; // single-writer: only used from publisherThread

    // Snapshot ring buffer — replays history to new clients
    private final String[] snapshots;
    private int snapHead = 0;
    private int snapSize = 0;

    public TelemetryServer(TelemetryRegistry registry, int port, int pollIntervalSec, String title, int snapshotCapacity) {
        this.registry = registry;
        this.port = port;
        this.pollIntervalSec = pollIntervalSec;
        this.title = title;
        this.snapshots = new String[snapshotCapacity];
        this.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.staticFiles.add("/public");
        });

        this.app.ws("/ws/telemetry", ws -> {
            ws.onConnect(ctx -> {
                replaySnapshots(ctx);
                clients.add(ctx);
            });
            ws.onClose(ctx -> clients.remove(ctx));
            ws.onError(ctx -> clients.remove(ctx));
        });
    }

    public int start() {
        boolean started = false;
        int currentPort = port;
        for (int i = 0; i < 10; i++) {
            try {
                app.start(currentPort);
                started = true;
                break;
            } catch (Exception e) {
                System.err.println("[TelemetryServer] Port " + currentPort + " in use, trying next...");
                currentPort++;
            }
        }
        
        if (!started) {
            System.err.println("[TelemetryServer] Failed to bind after 10 attempts. Telemetry disabled.");
            running = false;
            return -1;
        }

        publisherThread = new Thread(this::publishLoop, "TelemetryPublisher");
        publisherThread.setDaemon(true);
        publisherThread.start();
        return currentPort;
    }

    public void stop() {
        running = false;
        if (publisherThread != null) publisherThread.interrupt();
        app.stop();
    }

    private void publishLoop() {
        final StringBuilder sb = new StringBuilder(4096);

        while (running) {
            try {
                Thread.sleep(pollIntervalSec * 1000L);

                sb.setLength(0);
                buildJson(sb);
                final String json = sb.toString();

                // Always store snapshot for replay, even with no live clients
                storeSnapshot(json);

                if (clients.isEmpty()) continue;

                for (WsContext ctx : clients) {
                    try {
                        if (ctx.session.isOpen()) {
                            ctx.send(json);
                        } else {
                            clients.remove(ctx);
                        }
                    } catch (Exception e) {
                        clients.remove(ctx);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[TelemetryServer] publish error: " + e.getMessage());
            }
        }
    }

    private void buildJson(StringBuilder sb) {
        sb.append("{\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"title\":\"").append(title).append("\"");
        sb.append(",\"bufferCapacity\":").append(snapshots.length);
        sb.append(",\"uptimeSec\":").append(ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);
        appendThresholds(sb);
        appendStatsConfig(sb);
        appendUnits(sb);
        appendGroup(sb, "jvm");
        appendGroup(sb, "custom");
        sb.append("}");
    }

    private void appendThresholds(StringBuilder sb) {
        var thresholds = registry.getThresholds();
        if (thresholds.isEmpty()) return;
        sb.append(",\"thresholds\":{");
        boolean first = true;
        for (var entry : thresholds.entrySet()) {
            if (!first) sb.append(",");
            double[] wc = entry.getValue();
            sb.append("\"").append(entry.getKey()).append("\":[");
            appendDouble(sb, wc[0]);
            sb.append(",");
            appendDouble(sb, wc[1]);
            sb.append("]");
            first = false;
        }
        sb.append("}");
    }

    private void appendUnits(StringBuilder sb) {
        var units = registry.getUnits();
        if (units.isEmpty()) return;
        sb.append(",\"units\":{");
        boolean first = true;
        for (var entry : units.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
    }

    private void appendStatsConfig(StringBuilder sb) {
        var statsConfig = registry.getStatsConfig();
        if (statsConfig.isEmpty()) return;
        sb.append(",\"statsConfig\":{");
        boolean first = true;
        for (var entry : statsConfig.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":[");
            boolean firstStat = true;
            for (Stat s : entry.getValue()) {
                if (!firstStat) sb.append(",");
                sb.append("\"").append(s.name().toLowerCase()).append("\"");
                firstStat = false;
            }
            sb.append("]");
            first = false;
        }
        sb.append("}");
    }

    private void appendGroup(StringBuilder sb, String group) {
        sb.append(",\"").append(group).append("\":{");
        boolean first = true;

        final List<TelemetryRegistry.Metric<LongSupplier>> counters = registry.getLongCounters();
        for (int i = 0, n = counters.size(); i < n; i++) {
            final TelemetryRegistry.Metric<LongSupplier> m = counters.get(i);
            if (m.group.equals(group)) {
                if (!first) sb.append(",");
                sb.append("\"").append(m.name).append("\":").append(m.supplier.getAsLong());
                first = false;
            }
        }

        final List<TelemetryRegistry.Metric<DoubleSupplier>> gauges = registry.getDoubleGauges();
        for (int i = 0, n = gauges.size(); i < n; i++) {
            final TelemetryRegistry.Metric<DoubleSupplier> m = gauges.get(i);
            if (m.group.equals(group)) {
                if (!first) sb.append(",");
                double val = m.supplier.getAsDouble();
                if (Double.isNaN(val) || Double.isInfinite(val)) val = 0.0;
                sb.append("\"").append(m.name).append("\":");
                appendDouble(sb, val);
                first = false;
            }
        }

        sb.append("}");
    }

    /** Appends a double without allocating via Double.toString(). */
    private void appendDouble(StringBuilder sb, double val) {
        if (Double.isNaN(val))      { sb.append("null"); return; }
        if (Double.isInfinite(val)) { sb.append("null"); return; }
        if (val == 0.0)             { sb.append('0'); return; }

        long longVal = (long) val;
        if (val == longVal) { sb.append(longVal); return; }

        if (val < 0) { sb.append('-'); val = -val; }

        long intPart = (long) val;
        sb.append(intPart);

        double frac    = val - intPart;
        long   fracInt = Math.round(frac * 10_000);  // long: avoids int overflow at 4 dp
        if (fracInt > 0) {
            int digits = 4;
            while (digits > 0 && fracInt % 10 == 0) { fracInt /= 10; digits--; }
            if (digits > 0) {
                sb.append('.');
                for (int i = 0; i < digits; i++) dblBuf[i] = '0';
                for (int i = digits - 1; i >= 0 && fracInt > 0; i--) {
                    dblBuf[i] = (char) ('0' + (fracInt % 10));
                    fracInt /= 10;
                }
                sb.append(dblBuf, 0, digits);
            }
        }
    }

    private void storeSnapshot(String json) {
        int cap = snapshots.length;
        int idx = (snapHead + snapSize) % cap;
        snapshots[idx] = json;
        if (snapSize < cap) {
            snapSize++;
        } else {
            snapHead = (snapHead + 1) % cap;
        }
    }

    private void replaySnapshots(WsContext ctx) {
        int cap = snapshots.length;
        for (int i = 0; i < snapSize; i++) {
            try {
                ctx.send(snapshots[(snapHead + i) % cap]);
            } catch (Exception e) {
                break; // client disconnected during replay
            }
        }
    }
}

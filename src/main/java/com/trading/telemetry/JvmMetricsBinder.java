package com.trading.telemetry;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

public class JvmMetricsBinder {

    // ── Metric name constants ─────────────────────────────────────────────────
    public static final String HEAP_USED = "heapUsed";
    public static final String HEAP_MAX = "heapMax";
    public static final String THREADS = "threads";
    public static final String CPU = "cpu";
    public static final String YOUNG_GC = "youngGcCount";
    public static final String OLD_GC = "oldGcCount";

    public static void bind(TelemetryRegistry registry) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        registry.registerCounter("jvm", HEAP_USED, () -> memoryBean.getHeapMemoryUsage().getUsed());
        registry.registerCounter("jvm", HEAP_MAX, () -> memoryBean.getHeapMemoryUsage().getMax());
        registry.registerCounter("jvm", THREADS, threadBean::getThreadCount);
        
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            registry.registerGauge("jvm", CPU, () -> sunOsBean.getProcessCpuLoad() * 100.0);
        } else {
            registry.registerGauge("jvm", CPU, () -> osBean.getSystemLoadAverage());
        }

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = gcBean.getName().toLowerCase();
            if (name.contains("young") || name.contains("scavenge") || name.contains("parnew") || name.contains("copy")) {
                registry.registerCounter("jvm", YOUNG_GC, gcBean::getCollectionCount);
            } else if (name.contains("old") || name.contains("marksweep") || name.contains("concurrentmark")) {
                registry.registerCounter("jvm", OLD_GC, gcBean::getCollectionCount);
            } else {
                registry.registerCounter("jvm", name.replaceAll("[^a-zA-Z0-9]", "_") + "GcCount", gcBean::getCollectionCount);
            }
        }

        // Default display config — applied once at bind time.
        // Consumers can override these later via dashboard.chart(JvmMetricsBinder.HEAP_USED)...
        registry.stats(HEAP_USED, Stat.MIN, Stat.MAX); // spikes and floors matter, not the avg
        registry.stats(HEAP_MAX, Stat.MIN, Stat.MAX);
        registry.stats(THREADS, Stat.AVG); // steady-state baseline is the signal
        registry.stats(CPU, Stat.MAX, Stat.AVG); // spikes are important
        registry.unit(CPU, "%");
        registry.stats(YOUNG_GC); // monotonic counter — stats add no value
        registry.stats(OLD_GC);
    }
}

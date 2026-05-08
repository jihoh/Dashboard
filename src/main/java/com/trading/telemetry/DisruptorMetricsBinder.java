package com.trading.telemetry;

import com.lmax.disruptor.RingBuffer;

public class DisruptorMetricsBinder {

    public static void bind(TelemetryRegistry registry, RingBuffer<?> ringBuffer, String namePrefix) {
        int capacity = ringBuffer.getBufferSize();
        
        String bpName = namePrefix + "_ring_backpressure";
        String cursorName = namePrefix + "_cursor";

        registry.registerGauge("custom", bpName, () ->
                (double)(capacity - ringBuffer.remainingCapacity()) / capacity * 100.0);

        registry.registerCounter("custom", cursorName, ringBuffer::getCursor);
        
        // ── Default chart configuration ───────────────────────────────────────
        registry.threshold(bpName, 50, 80);
        registry.stats(bpName, Stat.AVG);
        
        // Cursor numbers are monotonically astronomical, stats don't make sense
        registry.stats(cursorName);
    }
}

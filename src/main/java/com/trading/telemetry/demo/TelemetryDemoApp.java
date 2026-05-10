package com.trading.telemetry.demo;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import com.trading.telemetry.TelemetryDashboard;

import static com.trading.telemetry.demo.OrderEngineMetrics.*;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Demonstrates how easy it is to add real-time telemetry to any Java
 * application.
 * <p>
 * The entire dashboard setup is 4 lines of code. Adding a new chart is 1 line.
 */
public class TelemetryDemoApp {

    public static class OrderEvent {
        public long price;
        public long quantity;
    }

    public static void main(String[] args) throws InterruptedException {

        // ┌──────────────────────────────────────────────────────────────┐
        // │ STEP 1: Launch the dashboard (1 line) │
        // └──────────────────────────────────────────────────────────────┘
        TelemetryDashboard dashboard = TelemetryDashboard.create()
                .port(8088)
                .title("Order Engine") // Optional custom name
                .snapshotHistory(1800) // retain 30 mins for browser refreshes
                .pollInterval(1) // seconds between metric snapshots
                .jvmMetrics() // auto-bind heap, GC, threads
                .start(); // → http://localhost:8080

        // ┌──────────────────────────────────────────────────────────────┐
        // │ STEP 2: Create counters (1 line each) │
        // │ Each one automatically gets its own chart on the dashboard. │
        // └──────────────────────────────────────────────────────────────┘
        LongAdder ordersCreated = dashboard.counter(ORDERS_CREATED);
        LongAdder ordersCancelled = dashboard.counter(ORDERS_CANCELLED);
        LongAdder ordersFilled = dashboard.counter(ORDERS_FILLED);
        LongAdder marketDataRecvd = dashboard.counter(MD_RECEIVED);

        // Track live gauges — sampled every poll tick:
        // Tip: Using a slash auto-merges them into a single "active_connections" chart!
        dashboard.gauge(ACTIVE_CONN_US_EAST,
                () -> 42.0 + Math.sin(System.currentTimeMillis() / 1000.0) * 10);
        dashboard.gauge(ACTIVE_CONN_EU_WEST,
                () -> 20.0 + Math.cos(System.currentTimeMillis() / 1500.0) * 5);

        // ┌──────────────────────────────────────────────────────────────┐
        // │ STEP 3 (optional): Hook up Disruptor ring buffers │
        // └──────────────────────────────────────────────────────────────┘
        Disruptor<OrderEvent> orderDisruptor = new Disruptor<>(
                OrderEvent::new, 1024,
                Executors.defaultThreadFactory(),
                ProducerType.SINGLE,
                new BlockingWaitStrategy());

        Disruptor<OrderEvent> mdDisruptor = new Disruptor<>(
                OrderEvent::new, 1024,
                Executors.defaultThreadFactory(),
                ProducerType.SINGLE,
                new BlockingWaitStrategy());

        // Slow consumer → backpressure visible on dashboard
        orderDisruptor.handleEventsWith((event, seq, eob) -> {
            if (Math.random() < 0.05)
                Thread.sleep((long) (Math.random() * 5));
        });
        mdDisruptor.handleEventsWith((event, seq, eob) -> {
            /* fast */ });

        RingBuffer<OrderEvent> orderRing = orderDisruptor.start();
        RingBuffer<OrderEvent> mdRing = mdDisruptor.start();

        // One line per ring buffer → auto-creates backpressure + cursor charts
        dashboard.disruptor(orderRing, ORDERS);
        dashboard.disruptor(mdRing, MARKET_DATA);

        // All chart display settings live in one place
        OrderEngineMetrics.configure(dashboard);

        // ┌──────────────────────────────────────────────────────────────┐
        // │ Application logic: just increment counters on your hot path │
        // └──────────────────────────────────────────────────────────────┘

        // Simulate GC pressure
        Thread gcThread = new Thread(() -> {
            while (true) {
                byte[] garbage = new byte[1024 * 512];
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        gcThread.setDaemon(true);
        gcThread.start();

        // Order publisher
        Thread orderPub = new Thread(() -> {
            while (true) {
                long seq = orderRing.next();
                try {
                    OrderEvent e = orderRing.get(seq);
                    e.price = 100;
                    e.quantity = 10;
                } finally {
                    orderRing.publish(seq);
                }

                ordersCreated.increment();

                // Simulate some cancellations and fills
                if (Math.random() < 0.1)
                    ordersCancelled.increment();
                if (Math.random() < 0.6)
                    ordersFilled.increment();

                try {
                    Thread.sleep(0, 500_000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        // Market data publisher
        Thread mdPub = new Thread(() -> {
            while (true) {
                long seq = mdRing.next();
                try {
                    OrderEvent e = mdRing.get(seq);
                    e.price = 50;
                } finally {
                    mdRing.publish(seq);
                }

                marketDataRecvd.increment();

                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        orderPub.start();
        mdPub.start();

        orderPub.join();
    }
}

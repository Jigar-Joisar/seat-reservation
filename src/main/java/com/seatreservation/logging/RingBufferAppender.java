package com.seatreservation.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.encoder.Encoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the most recent application log events (as JSON lines) in memory so they can be served by
 * GET /ops/logs. Only the request access log and this application's own loggers are captured; framework
 * and driver logs (which may print connection details) never enter the buffer.
 */
public class RingBufferAppender extends AppenderBase<ILoggingEvent> {
    private static volatile RingBufferAppender instance;

    private final ArrayDeque<String> buffer = new ArrayDeque<>();
    private Encoder<ILoggingEvent> encoder;
    private int capacity = 2000;

    public void setEncoder(Encoder<ILoggingEvent> encoder) { this.encoder = encoder; }

    public void setCapacity(int capacity) { this.capacity = Math.max(10, capacity); }

    @Override
    public void start() {
        if (encoder == null) { addError("no encoder configured for " + name); return; }
        instance = this;
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String logger = event.getLoggerName();
        if (!"access".equals(logger) && !logger.startsWith("com.seatreservation")) return;
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8).trim();
        synchronized (buffer) {
            if (buffer.size() >= capacity) buffer.removeFirst();
            buffer.addLast(line);
        }
    }

    public static List<String> snapshot() {
        RingBufferAppender a = instance;
        if (a == null) return List.of();
        synchronized (a.buffer) { return new ArrayList<>(a.buffer); }
    }

    public static int capacityOrZero() {
        RingBufferAppender a = instance;
        return a == null ? 0 : a.capacity;
    }
}

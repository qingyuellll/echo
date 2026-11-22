package com.echo.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class PrometheusMetricsConfig {

    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, AtomicInteger> seckillStockMap = new ConcurrentHashMap<>();

    public PrometheusMetricsConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordSeckillStock(String ticketId, int stock) {
        seckillStockMap.computeIfAbsent(ticketId, k -> new AtomicInteger(0)).set(stock);

        Gauge.builder("seckill_stock_remaining",
                        () -> seckillStockMap.getOrDefault(ticketId, new AtomicInteger(0)).get())
                .tag("ticket_id", ticketId)
                .description("单票档秒杀库存剩余量")
                .register(meterRegistry);
    }

    public void recordOrder(String type, String status) {
        Counter.builder("orders_total")
                .tag("type", type)
                .tag("status", status)
                .register(meterRegistry)
                .increment();
    }

    public void recordLoginAttempt(String result, String role) {
        Counter.builder("login_attempts_total")
                .tag("result", result)
                .tag("role", role)
                .register(meterRegistry)
                .increment();
    }

    public void recordApiRequest(String method, String endpoint, int status) {
        Counter.builder("api_requests_total")
                .tag("method", method)
                .tag("endpoint", endpoint)
                .tag("status", String.valueOf(status))
                .register(meterRegistry)
                .increment();
    }

    public Timer.Sample startApiTimer() {
        return Timer.start(meterRegistry);
    }

    public void stopApiTimer(Timer.Sample sample, String method, String endpoint) {
        sample.stop(Timer.builder("api_request_duration_seconds")
                .tag("method", method)
                .tag("endpoint", endpoint)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }

    public void recordVerification(boolean success) {
        Counter.builder("verification_operations_total")
                .tag("result", success ? "success" : "failed")
                .register(meterRegistry)
                .increment();
    }
}

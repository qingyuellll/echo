package com.echo.utils;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单机线程安全的令牌桶限流器
 *
 * @author tulip
 */
public class TokenBucketLimiter {

    private final long capacity;
    private final double rate;
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillTime;
    private final ReentrantLock lock;

    public TokenBucketLimiter(long capacity, double rate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        this.capacity = capacity;
        this.rate = rate;
        this.availableTokens = new AtomicLong(capacity);
        this.lastRefillTime = new AtomicLong(System.nanoTime());
        this.lock = new ReentrantLock();
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(long tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("tokens must be positive");
        }
        if (tokens > capacity) {
            return false;
        }

        lock.lock();
        try {
            refill();

            long current = availableTokens.get();
            if (current >= tokens) {
                availableTokens.set(current - tokens);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillTime.get();
        long elapsedNanos = now - last;

        if (elapsedNanos <= 0) {
            return;
        }

        long newTokens = (long) (elapsedNanos * rate / 1_000_000_000.0);
        if (newTokens <= 0) {
            return;
        }

        long current = availableTokens.get();
        long replenished = Math.min(capacity, current + newTokens);
        availableTokens.set(replenished);
        lastRefillTime.set(now);
    }

    public long getAvailableTokens() {
        lock.lock();
        try {
            refill();
            return availableTokens.get();
        } finally {
            lock.unlock();
        }
    }

    public long getCapacity() {
        return capacity;
    }

    public double getRate() {
        return rate;
    }
}

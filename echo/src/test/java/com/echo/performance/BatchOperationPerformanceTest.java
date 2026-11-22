package com.echo.performance;

import com.echo.dto.BatchOperationDTO;
import com.echo.service.IAdminService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest(webEnvironment = WebEnvironment.NONE)
@ActiveProfiles("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Batch Operation Performance Test")
public class BatchOperationPerformanceTest {

    @Autowired
    private IAdminService adminService;

    private static final int CONCURRENT_USERS = 100;

    private static final int OPERATIONS_PER_USER = 5;

    private static final int BATCH_SIZE = 10;

    private static final long AVG_RESPONSE_TIME_THRESHOLD_MS = 2000;

    private static final long MAX_RESPONSE_TIME_THRESHOLD_MS = 10000;

    private static final double SUCCESS_RATE_THRESHOLD = 95.0;

    private final AtomicInteger successCount = new AtomicInteger(0);

    private final AtomicInteger failCount = new AtomicInteger(0);

    private final List<Long> responseTimes = new ArrayList<>();

    @Test
    @Order(1)
    @DisplayName("1. Test concurrent batch operations with 100 users")
    void testConcurrentBatchOnline() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("Performance Test: 100 Concurrent Users");
        System.out.println("=".repeat(80));

        resetStatistics();

        ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_USERS);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_USERS);

        for (int userId = 1; userId <= CONCURRENT_USERS; userId++) {
            final int currentUserId = userId;
            executorService.submit(() -> {
                try {
                    startLatch.await();

                    for (int op = 0; op < OPERATIONS_PER_USER; op++) {
                        long startTime = System.currentTimeMillis();
                        try {
                            BatchOperationDTO dto = createTestBatchOperation(currentUserId);
                            
                            var result = adminService.batchOperationOrders(dto);
                            
                            if (result != null && result.getSuccess()) {
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                        } finally {
                            long duration = System.currentTimeMillis() - startTime;
                            synchronized (responseTimes) {
                                responseTimes.add(duration);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executorService.shutdown();

        printPerformanceResults();
        validatePerformanceThresholds();
    }

    private void resetStatistics() {
        successCount.set(0);
        failCount.set(0);
        responseTimes.clear();
    }

    private BatchOperationDTO createTestBatchOperation(int userId) {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= BATCH_SIZE; i++) {
            ids.add((long)(userId * 1000 + i));
        }

        return new BatchOperationDTO(ids, BatchOperationDTO.Operation.OFFLINE, null, "Performance test user-" + userId);
    }

    private void printPerformanceResults() {
        System.out.println("\n" + "-".repeat(80));
        System.out.println("PERFORMANCE RESULTS");
        System.out.println("-".repeat(80));

        int totalOperations = successCount.get() + failCount.get();
        double successRate = totalOperations > 0 ? 
            (successCount.get() * 100.0 / totalOperations) : 0;

        System.out.printf("Total Operations: %d%n", totalOperations);
        System.out.printf("Successful: %d (%.2f%%)%n", successCount.get(), successRate);
        System.out.printf("Failed: %d (%.2f%%)%n", failCount.get(), 
            totalOperations > 0 ? (failCount.get() * 100.0 / totalOperations) : 0);

        if (!responseTimes.isEmpty()) {
            long avgTime = (long) responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

            long maxTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);

            long minTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0);

            System.out.printf("%nResponse Times:%n");
            System.out.printf("  Average: %d ms%n", avgTime);
            System.out.printf("  Min: %d ms%n", minTime);
            System.out.printf("  Max: %d ms%n", maxTime);
        }

        System.out.println("-".repeat(80));
    }

    private void validatePerformanceThresholds() {
        int totalOperations = successCount.get() + failCount.get();
        double successRate = totalOperations > 0 ? 
            (successCount.get() * 100.0 / totalOperations) : 0;

        Assertions.assertTrue(successRate >= SUCCESS_RATE_THRESHOLD,
            String.format("Success rate %.2f%% is below threshold %.2f%%",
                successRate, SUCCESS_RATE_THRESHOLD));

        if (!responseTimes.isEmpty()) {
            long avgTime = (long) responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);

            long maxTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0);

            Assertions.assertTrue(avgTime <= AVG_RESPONSE_TIME_THRESHOLD_MS,
                String.format("Average response time %d ms exceeds threshold %d ms",
                    avgTime, AVG_RESPONSE_TIME_THRESHOLD_MS));

            Assertions.assertTrue(maxTime <= MAX_RESPONSE_TIME_THRESHOLD_MS,
                String.format("Max response time %d ms exceeds threshold %d ms",
                    maxTime, MAX_RESPONSE_TIME_THRESHOLD_MS));
        }

        System.out.println("\n[✅ PASS] All performance thresholds met!");
    }
}

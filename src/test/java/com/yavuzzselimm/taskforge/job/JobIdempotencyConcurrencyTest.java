package com.yavuzzselimm.taskforge.job;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JobIdempotencyConcurrencyTest {

    private static final int THREADS = 10;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
    }

    @Test
    void concurrentRequestsWithSameKey_createExactlyOneJob() throws Exception {
        CreateJobRequest request = new CreateJobRequest(
                "SEND_ORDER_EMAIL",
                JsonNodeFactory.instance.objectNode().put("orderId", "ORD-1"),
                null, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();

        try {
            for (int i = 0; i < THREADS; i++) {
                results.add(pool.submit(() -> {
                    startSignal.await();   // tüm thread'ler hazır olana kadar bekle
                    return jobService.create(request, "race-key").job().getId();
                }));
            }

            startSignal.countDown();   // hepsini aynı anda başlat

            Set<UUID> jobIds = new HashSet<>();
            for (Future<UUID> result : results) {
                jobIds.add(result.get(10, TimeUnit.SECONDS));
            }

            assertThat(jobIds).hasSize(1);
            assertThat(jobRepository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
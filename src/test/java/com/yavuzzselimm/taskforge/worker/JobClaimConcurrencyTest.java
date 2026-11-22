package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.job.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
class JobClaimConcurrencyTest {

    private static final int WORKERS = 8;
    private static final int JOBS = 200;

    @Autowired
    private JobClaimRepository claimRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
    }

    @Test
    void concurrentWorkers_claimEveryJobExactlyOnce() throws Exception {
        jdbc.sql("""
                        INSERT INTO jobs (job_type, payload)
                        SELECT 'SEND_ORDER_EMAIL', '{}'::jsonb FROM generate_series(1, :count)
                        """)
                .param("count", JOBS)
                .update();

        Queue<UUID> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < WORKERS; i++) {
                String workerId = "test-worker-" + i;
                futures.add(pool.submit(() -> {
                    startSignal.await();
                    Optional<ClaimedJob> job;
                    while ((job = claimRepository.claimNext(workerId, 30)).isPresent()) {
                        claimed.add(job.get().id());
                    }
                    return null;
                }));
            }

            startSignal.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed).hasSize(JOBS).doesNotHaveDuplicates();

        Integer notClaimedOnce = jdbc.sql("""
                        SELECT count(*) FROM jobs
                        WHERE status <> 'RUNNING' OR attempt_count <> 1
                        """)
                .query(Integer.class)
                .single();
        assertThat(notClaimedOnce).isZero();
    }

    @Test
    void claimNext_prefersHigherPriorityAndSkipsFutureJobs() {
        UUID low = insertJob(0, "now()");
        UUID high = insertJob(10, "now()");
        UUID medium = insertJob(5, "now()");
        insertJob(100, "now() + interval '1 hour'");

        List<UUID> order = new ArrayList<>();
        Optional<ClaimedJob> job;
        while ((job = claimRepository.claimNext("test-worker", 30)).isPresent()) {
            order.add(job.get().id());
        }

        assertThat(order).containsExactly(high, medium, low);
    }

    private UUID insertJob(int priority, String availableAtSql) {
        return jdbc.sql("""
                        INSERT INTO jobs (job_type, payload, priority, available_at)
                        VALUES ('SEND_ORDER_EMAIL', '{}'::jsonb, :priority, %s)
                        RETURNING id
                        """.formatted(availableAtSql))
                .param("priority", priority)
                .query(UUID.class)
                .single();
    }
}

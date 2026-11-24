package com.yavuzzselimm.taskforge.job;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.worker.JobClaimRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
class JobCancelConcurrencyTest {

    private static final int JOBS = 200;
    private static final int CLAIMERS = 4;
    private static final int CANCELLERS = 4;

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobClaimRepository claimRepository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
    }

    @Test
    void cancelAndClaimRacing_neverBothWin() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < JOBS; i++) {
            ids.add(jobService.create(new CreateJobRequest(
                    "SEND_ORDER_EMAIL",
                    JsonNodeFactory.instance.objectNode().put("orderId", "ORD-" + i),
                    null, null, null), null).job().getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(CLAIMERS + CANCELLERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int i = 0; i < CLAIMERS; i++) {
                String workerId = "worker-" + i;
                tasks.add(pool.submit(() -> {
                    start.await();
                    while (claimRepository.claimNext(workerId, 30).isPresent()) {
                        // kuyruk boşalana kadar al
                    }
                    return null;
                }));
            }
            for (int i = 0; i < CANCELLERS; i++) {
                List<UUID> order = new ArrayList<>(ids);
                Collections.shuffle(order);
                tasks.add(pool.submit(() -> {
                    start.await();
                    for (UUID id : order) {
                        try {
                            jobService.cancel(id);
                        } catch (JobNotCancellableException ignored) {
                            // worker önce davrandı
                        }
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> broken = jdbc.sql("""
                        SELECT j.id::text
                        FROM jobs j
                        LEFT JOIN job_attempts a ON a.job_id = j.id
                        GROUP BY j.id, j.status
                        HAVING NOT (
                            (j.status = 'CANCELLED' AND count(a.id) = 0)
                         OR (j.status = 'RUNNING'   AND count(a.id) = 1))
                        """)
                .query(String.class)
                .list();
        assertThat(broken).isEmpty();

        Map<String, Integer> byStatus = new HashMap<>();
        jdbc.sql("SELECT status, count(*) AS n FROM jobs GROUP BY status")
                .query(rs -> { byStatus.put(rs.getString("status"), rs.getInt("n")); });
        assertThat(byStatus.getOrDefault("CANCELLED", 0) + byStatus.getOrDefault("RUNNING", 0))
                .isEqualTo(JOBS);
    }
}

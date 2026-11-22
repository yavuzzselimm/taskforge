package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.handler.JobContext;
import com.yavuzzselimm.taskforge.handler.JobHandler;
import com.yavuzzselimm.taskforge.job.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(properties = {
        "taskforge.worker.enabled=true",
        "taskforge.worker.poll-interval-ms=100",
        "taskforge.worker.poll-jitter-ms=50",
        "taskforge.worker.threads=4",
        "taskforge.retry.base-delay-ms=50",
        "taskforge.retry.max-delay-ms=200",
        "taskforge.handlers.flaky.enabled=true",
        "taskforge.recovery.interval-ms=200"
})
@Import({TestcontainersConfiguration.class, JobWorkerIntegrationTest.TestHandlersConfig.class})
class JobWorkerIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private CountingHandler countingHandler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
        countingHandler.reset();
    }

    @Test
    void readyJob_isPickedUpAndCompletedByWorker() {
        UUID id = createJob("SEND_ORDER_EMAIL", null);

        Job job = awaitStatus(id, JobStatus.COMPLETED);

        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getLastError()).isNull();
    }

    @Test
    void failingJob_isRetriedUntilAttemptsRunOut_thenMarkedDead() {
        UUID id = createJob("ALWAYS_FAIL", null);

        Job job = awaitStatus(id, JobStatus.DEAD);

        assertThat(job.getAttemptCount()).isEqualTo(job.getMaxAttempts());
        assertThat(job.getLastError()).contains("simulated failure");
    }

    @Test
    void transientFailures_areRetriedUntilTheJobSucceeds() {
        UUID id = createJob("FLAKY", JsonNodeFactory.instance.objectNode().put("failTimes", 2), 5);

        Job job = awaitStatus(id, JobStatus.COMPLETED);

        assertThat(job.getAttemptCount()).isEqualTo(3);
        assertThat(job.getLastError()).isNull();
    }

    @Test
    void permanentFailure_isNotRetried() {
        UUID id = createJob("FLAKY", JsonNodeFactory.instance.objectNode().put("permanent", true), 5);

        Job job = awaitStatus(id, JobStatus.DEAD);

        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getLastError()).contains("PermanentJobException");
    }

    @Test
    void jobHeldByCrashedWorker_isRecoveredAndCompleted() {
        // çökmüş bir worker'ın elinde kalmış gibi: RUNNING, lease'i dolmuş
        UUID id = jdbc.sql("""
                        INSERT INTO jobs (job_type, payload, status, locked_by, lease_until, attempt_count)
                        VALUES ('SEND_ORDER_EMAIL', '{"orderId": "ORD-1"}'::jsonb, 'RUNNING',
                                'crashed-worker', now() - interval '1 second', 1)
                        RETURNING id
                        """)
                .query(UUID.class)
                .single();

        Job job = awaitStatus(id, JobStatus.COMPLETED);

        assertThat(job.getAttemptCount()).isEqualTo(2);
    }

    @Test
    void jobScheduledInTheFuture_isNotPickedUpYet() throws InterruptedException {
        UUID id = createJob("SEND_ORDER_EMAIL", Instant.now().plus(Duration.ofHours(1)));

        Thread.sleep(1000);   // worker'a birkaç tur fırsat ver

        Job job = jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.READY);
        assertThat(job.getAttemptCount()).isZero();
    }

    @Test
    void manyJobs_areCompletedExactlyOnceUsingMultipleThreads() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(createJob("COUNTING", null));
        }

        for (UUID id : ids) {
            Job job = awaitStatus(id, JobStatus.COMPLETED);
            assertThat(job.getAttemptCount()).isEqualTo(1);
        }

        assertThat(countingHandler.executions()).hasSize(40);
        assertThat(countingHandler.executions().values()).allMatch(count -> count.get() == 1);
        // gerçekten paralel çalıştı mı, ve thread sayısını aştı mı?
        assertThat(countingHandler.maxConcurrent()).isBetween(2, 4);
    }

    @Test
    void completedAndFailedJobs_areCountedByJobType() throws InterruptedException {
        double completedBefore = counterValue("jobs.completed", "SEND_ORDER_EMAIL");
        double failedBefore = counterValue("jobs.failed", "ALWAYS_FAIL");
        double retriesBefore = counterValue("job.retries", "ALWAYS_FAIL");

        awaitStatus(createJob("SEND_ORDER_EMAIL", null), JobStatus.COMPLETED);
        awaitStatus(createJob("ALWAYS_FAIL", null), JobStatus.DEAD);

        // sayaç, DB güncellemesinden hemen sonra artıyor; kısa bir an beklemek gerekebilir
        Instant deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)
                && (counterValue("jobs.completed", "SEND_ORDER_EMAIL") < completedBefore + 1
                || counterValue("jobs.failed", "ALWAYS_FAIL") < failedBefore + 1)) {
            Thread.sleep(20);
        }

        assertThat(counterValue("jobs.completed", "SEND_ORDER_EMAIL")).isEqualTo(completedBefore + 1);
        assertThat(counterValue("jobs.failed", "ALWAYS_FAIL")).isEqualTo(failedBefore + 1);
        // varsayılan 5 deneme: 4 tekrar, sonra DEAD
        assertThat(counterValue("job.retries", "ALWAYS_FAIL")).isEqualTo(retriesBefore + 4);
    }

    private double counterValue(String name, String jobType) {
        Counter counter = meterRegistry.find(name).tag("job_type", jobType).counter();
        return counter == null ? 0 : counter.count();
    }

    private UUID createJob(String type, JsonNode payload, Integer maxAttempts) {
        CreateJobRequest request = new CreateJobRequest(type, payload, null, null, maxAttempts);
        return jobService.create(request, null).job().getId();
    }

    private UUID createJob(String type, Instant scheduledAt) {
        CreateJobRequest request = new CreateJobRequest(
                type,
                JsonNodeFactory.instance.objectNode().put("orderId", "ORD-1"),
                null, scheduledAt, null);
        return jobService.create(request, null).job().getId();
    }

    /** İş beklenen duruma gelene kadar en fazla 5 saniye bekler. */
    private Job awaitStatus(UUID id, JobStatus expected) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            Job job = jobRepository.findById(id).orElseThrow();
            if (job.getStatus() == expected) {
                return job;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting");
            }
        }
        Job job = jobRepository.findById(id).orElseThrow();
        return fail("Job %s expected %s but was %s", id, expected, job.getStatus());
    }

    @TestConfiguration
    static class TestHandlersConfig {

        @Bean
        JobHandler alwaysFailHandler() {
            return new JobHandler() {
                @Override
                public String jobType() {
                    return "ALWAYS_FAIL";
                }

                @Override
                public void handle(JobContext context) {
                    throw new IllegalStateException("simulated failure");
                }
            };
        }

        @Bean
        CountingHandler countingHandler() {
            return new CountingHandler();
        }
    }

    /** Her işin kaç kez çalıştığını ve aynı anda en fazla kaç iş çalıştığını sayar. */
    static class CountingHandler implements JobHandler {

        private final Map<UUID, AtomicInteger> executions = new ConcurrentHashMap<>();
        private final AtomicInteger current = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public String jobType() {
            return "COUNTING";
        }

        @Override
        public void handle(JobContext context) throws InterruptedException {
            int now = current.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                executions.computeIfAbsent(context.jobId(), id -> new AtomicInteger()).incrementAndGet();
                Thread.sleep(100);
            } finally {
                current.decrementAndGet();
            }
        }

        Map<UUID, AtomicInteger> executions() {
            return executions;
        }

        int maxConcurrent() {
            return maxConcurrent.get();
        }

        void reset() {
            executions.clear();
            maxConcurrent.set(0);
        }
    }
}

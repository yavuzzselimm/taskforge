package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.handler.JobContext;
import com.yavuzzselimm.taskforge.handler.JobHandler;
import com.yavuzzselimm.taskforge.handler.JobHandlerRegistry;
import com.yavuzzselimm.taskforge.job.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aynı veritabanını paylaşan birden fazla worker instance'ı. Spring'in kendi worker'ı
 * kapalı; worker'ları test kendisi oluşturup başlatıyor.
 */
@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import({TestcontainersConfiguration.class, MultiWorkerIntegrationTest.Config.class})
class MultiWorkerIntegrationTest {

    private static final int JOBS = 60;

    @Autowired
    private JobClaimRepository claimRepository;

    @Autowired
    private JobHandlerRegistry handlerRegistry;

    @Autowired
    private RetryPolicy retryPolicy;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RecordingHandler recordingHandler;

    private final List<JobWorker> workers = new ArrayList<>();

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
    }

    @AfterEach
    void stopWorkers() {
        workers.forEach(JobWorker::stop);
    }

    @Test
    void threeWorkers_shareTheQueueAndRunEveryJobExactlyOnce() throws InterruptedException {
        jdbc.sql("""
                        INSERT INTO jobs (job_type, payload)
                        SELECT 'RECORDING', '{}'::jsonb FROM generate_series(1, :count)
                        """)
                .param("count", JOBS)
                .update();

        for (String id : List.of("worker-a", "worker-b", "worker-c")) {
            JobWorker worker = new JobWorker(claimRepository, handlerRegistry, retryPolicy, meterRegistry,
                    new WorkerProperties(id, 2, 50, 20, 30, 10_000, 5));
            workers.add(worker);
            worker.start();
        }

        awaitAllCompleted();

        assertThat(recordingHandler.runsPerJob()).hasSize(JOBS);
        assertThat(recordingHandler.runsPerJob().values()).allMatch(runs -> runs.get() == 1);
        assertThat(recordingHandler.jobsPerWorker().keySet())
                .containsExactlyInAnyOrder("worker-a", "worker-b", "worker-c");
    }

    private void awaitAllCompleted() throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (countCompleted() == JOBS) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(countCompleted()).as("completed jobs").isEqualTo(JOBS);
    }

    private int countCompleted() {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE status = 'COMPLETED'")
                .query(Integer.class)
                .single();
    }

    @TestConfiguration
    static class Config {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    /** Hangi işin kaç kez, hangi worker tarafından çalıştırıldığını kaydeder. */
    static class RecordingHandler implements JobHandler {

        private final Map<UUID, AtomicInteger> runsPerJob = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> jobsPerWorker = new ConcurrentHashMap<>();

        @Override
        public String jobType() {
            return "RECORDING";
        }

        @Override
        public void handle(JobContext context) throws InterruptedException {
            // executor thread'lerinin adı "<workerId>-exec-N" şeklinde
            String threadName = Thread.currentThread().getName();
            String workerId = threadName.substring(0, threadName.indexOf("-exec-"));

            runsPerJob.computeIfAbsent(context.jobId(), id -> new AtomicInteger()).incrementAndGet();
            jobsPerWorker.computeIfAbsent(workerId, id -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(50);
        }

        Map<UUID, AtomicInteger> runsPerJob() {
            return runsPerJob;
        }

        Map<String, AtomicInteger> jobsPerWorker() {
            return jobsPerWorker;
        }
    }
}

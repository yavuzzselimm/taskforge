package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.handler.JobContext;
import com.yavuzzselimm.taskforge.handler.JobHandler;
import com.yavuzzselimm.taskforge.job.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(properties = {
        "taskforge.worker.enabled=true",
        "taskforge.worker.poll-interval-ms=100"
})
@Import({TestcontainersConfiguration.class, JobWorkerIntegrationTest.FailingHandlerConfig.class})
class JobWorkerIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
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
    void failingJob_isMarkedDeadWithError() {
        UUID id = createJob("ALWAYS_FAIL", null);

        Job job = awaitStatus(id, JobStatus.DEAD);

        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getLastError()).contains("simulated failure");
    }

    @Test
    void jobScheduledInTheFuture_isNotPickedUpYet() throws InterruptedException {
        UUID id = createJob("SEND_ORDER_EMAIL", Instant.now().plus(Duration.ofHours(1)));

        Thread.sleep(1000);   // worker'a birkaç tur fırsat ver

        Job job = jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.READY);
        assertThat(job.getAttemptCount()).isZero();
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

    /** Yalnızca testlerde var olan, her zaman hata veren bir handler. */
    @TestConfiguration
    static class FailingHandlerConfig {

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
    }
}
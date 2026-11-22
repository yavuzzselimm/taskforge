package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.job.Job;
import com.yavuzzselimm.taskforge.job.JobRepository;
import com.yavuzzselimm.taskforge.job.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
class JobClaimRepositoryTest {

    @Autowired
    private JobClaimRepository claimRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        jdbc.sql("INSERT INTO jobs (job_type, payload) VALUES ('SEND_ORDER_EMAIL', '{}'::jsonb)").update();
    }

    @Test
    void markRetry_releasesTheJobAndPostponesIt() {
        ClaimedJob claimed = claimRepository.claimNext("worker-1", 30).orElseThrow();

        boolean updated = claimRepository.markRetry(
                claimed.id(), "worker-1", claimed.attempt(), "boom", Duration.ofHours(1));

        assertThat(updated).isTrue();
        Job job = jobRepository.findById(claimed.id()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getLastError()).isEqualTo("boom");
        assertThat(job.getAvailableAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));

        // zamanı gelmeden tekrar alınmamalı
        assertThat(claimRepository.claimNext("worker-2", 30)).isEmpty();
    }

    @Test
    void markRetry_byAnotherWorker_isRejected() {
        ClaimedJob claimed = claimRepository.claimNext("worker-1", 30).orElseThrow();

        boolean updated = claimRepository.markRetry(
                claimed.id(), "worker-2", claimed.attempt(), "boom", Duration.ofSeconds(1));

        assertThat(updated).isFalse();
        assertThat(jobRepository.findById(claimed.id()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.RUNNING);
    }
}

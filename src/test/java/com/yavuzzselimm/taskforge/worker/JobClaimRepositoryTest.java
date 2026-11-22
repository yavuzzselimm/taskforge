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
import java.util.List;
import java.util.UUID;

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

    @Test
    void recoverExpiredLeases_givesJobWithAttemptsLeftAnotherTry() {
        UUID id = insertRunning("crashed-worker", "now() - interval '1 second'", 1, 3);

        List<RecoveredJob> recovered = claimRepository.recoverExpiredLeases(100);

        assertThat(recovered).extracting(RecoveredJob::id).containsExactly(id);
        assertThat(recovered.getFirst().previousOwner()).isEqualTo("crashed-worker");
        Job job = jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RETRY_WAIT);
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getLastError()).contains("crashed-worker");
    }

    @Test
    void recoverExpiredLeases_marksJobDeadWhenAttemptsAreUsedUp() {
        UUID id = insertRunning("crashed-worker", "now() - interval '1 second'", 3, 3);

        claimRepository.recoverExpiredLeases(100);

        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.DEAD);
    }

    @Test
    void recoverExpiredLeases_leavesActiveLeasesAlone() {
        UUID id = insertRunning("busy-worker", "now() + interval '30 seconds'", 1, 3);

        assertThat(claimRepository.recoverExpiredLeases(100)).isEmpty();
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void lateWorker_cannotWriteResultAfterItsJobWasRecovered() {
        ClaimedJob slow = claimRepository.claimNext("slow-worker", 30).orElseThrow();
        // slow-worker takılıp kaldı ve lease'i doldu
        jdbc.sql("UPDATE jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
                .param("id", slow.id())
                .update();
        claimRepository.recoverExpiredLeases(100);

        ClaimedJob fresh = claimRepository.claimNext("fresh-worker", 30).orElseThrow();
        assertThat(fresh.id()).isEqualTo(slow.id());
        assertThat(fresh.attempt()).isEqualTo(2);

        assertThat(claimRepository.markCompleted(slow.id(), "slow-worker", slow.attempt())).isFalse();
        assertThat(claimRepository.markCompleted(fresh.id(), "fresh-worker", fresh.attempt())).isTrue();

        // slow-worker'ın geç gelen sonucu deneme kaydını da değiştirmemeli
        assertThat(attempts(slow.id()))
                .containsExactly("1 slow-worker LEASE_EXPIRED", "2 fresh-worker SUCCESS");
    }

    @Test
    void attemptHistory_recordsEveryTryWithItsWorkerAndOutcome() {
        ClaimedJob first = claimRepository.claimNext("worker-1", 30).orElseThrow();
        assertThat(attempts(first.id())).containsExactly("1 worker-1 null");

        claimRepository.markRetry(first.id(), "worker-1", 1, "timeout", Duration.ZERO);
        ClaimedJob second = claimRepository.claimNext("worker-2", 30).orElseThrow();
        claimRepository.markCompleted(second.id(), "worker-2", 2);

        assertThat(attempts(first.id()))
                .containsExactly("1 worker-1 TRANSIENT_FAILURE", "2 worker-2 SUCCESS");
        Integer unfinished = jdbc.sql("SELECT count(*) FROM job_attempts WHERE finished_at IS NULL")
                .query(Integer.class)
                .single();
        assertThat(unfinished).isZero();
    }

    private List<String> attempts(UUID jobId) {
        return jdbc.sql("""
                        SELECT attempt_no || ' ' || worker_id || ' ' || coalesce(outcome, 'null')
                        FROM job_attempts
                        WHERE job_id = :jobId
                        ORDER BY attempt_no
                        """)
                .param("jobId", jobId)
                .query(String.class)
                .list();
    }

    @Test
    void renewLeases_extendsOnlyJobsOwnedByTheWorker() {
        ClaimedJob claimed = claimRepository.claimNext("worker-1", 1).orElseThrow();

        assertThat(claimRepository.renewLeases("worker-2", List.of(claimed.id()), 30)).isEmpty();
        assertThat(claimRepository.renewLeases("worker-1", List.of(claimed.id()), 30))
                .containsExactly(claimed.id());

        Job job = jobRepository.findById(claimed.id()).orElseThrow();
        assertThat(job.getLeaseUntil()).isAfter(Instant.now().plusSeconds(29));
    }

    private UUID insertRunning(String lockedBy, String leaseUntilSql, int attempt, int maxAttempts) {
        return jdbc.sql("""
                        INSERT INTO jobs (job_type, payload, status, locked_by, lease_until,
                                          attempt_count, max_attempts)
                        VALUES ('SEND_ORDER_EMAIL', '{}'::jsonb, 'RUNNING', :lockedBy, %s,
                                :attempt, :maxAttempts)
                        RETURNING id
                        """.formatted(leaseUntilSql))
                .param("lockedBy", lockedBy)
                .param("attempt", attempt)
                .param("maxAttempts", maxAttempts)
                .query(UUID.class)
                .single();
    }
}

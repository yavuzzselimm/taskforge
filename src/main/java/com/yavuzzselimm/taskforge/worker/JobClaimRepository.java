package com.yavuzzselimm.taskforge.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


@Repository
public class JobClaimRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    public JobClaimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Sıradaki uygun işi sahiplenir ve aynı ifadede job_attempts'a yeni bir deneme satırı açar.
     */
    public Optional<ClaimedJob> claimNext(String workerId, long leaseSeconds) {
        return jdbc.sql("""
                        WITH claimed AS (
                            UPDATE jobs
                            SET status        = 'RUNNING',
                                locked_by     = :workerId,
                                lease_until   = now() + (:leaseSeconds * interval '1 second'),
                                heartbeat_at  = now(),
                                attempt_count = attempt_count + 1,
                                updated_at    = now()
                            WHERE id = (
                                SELECT id
                                FROM jobs
                                WHERE status IN ('READY', 'RETRY_WAIT')
                                  AND available_at <= now()
                                ORDER BY priority DESC, available_at
                                LIMIT 1
                                FOR UPDATE SKIP LOCKED
                            )
                            RETURNING id, job_type, payload, attempt_count, max_attempts
                        ), started AS (
                            INSERT INTO job_attempts (job_id, attempt_no, worker_id)
                            SELECT id, attempt_count, :workerId FROM claimed
                        )
                        SELECT id, job_type, payload::text AS payload, attempt_count, max_attempts
                        FROM claimed
                        """)
                .param("workerId", workerId)
                .param("leaseSeconds", leaseSeconds)
                .query((rs, rowNum) -> new ClaimedJob(
                        rs.getObject("id", UUID.class),
                        rs.getString("job_type"),
                        rs.getString("payload"),
                        rs.getInt("attempt_count"),
                        rs.getInt("max_attempts")))
                .optional();
    }

    public boolean markCompleted(UUID jobId, String workerId, int attempt) {
        return finishAttempt(jobId, workerId, attempt, "COMPLETED", AttemptOutcome.SUCCESS, null, Duration.ZERO);
    }

    public boolean markDead(UUID jobId, String workerId, int attempt, String error, AttemptOutcome outcome) {
        return finishAttempt(jobId, workerId, attempt, "DEAD", outcome, error, Duration.ZERO);
    }

    public boolean markRetry(UUID jobId, String workerId, int attempt, String error, Duration delay) {
        return finishAttempt(jobId, workerId, attempt, "RETRY_WAIT",
                AttemptOutcome.TRANSIENT_FAILURE, error, delay);
    }

    /**
     * İşin sonucunu ve deneme kaydını tek ifadede yazar. Yalnızca iş hâlâ bu worker'ın
     * bu denemesine aitse yazılır (geç kalan worker koruması).
     */
    private boolean finishAttempt(UUID jobId, String workerId, int attempt, String newStatus,
                                  AttemptOutcome outcome, String error, Duration retryDelay) {
        int finished = jdbc.sql("""
                        WITH finished AS (
                            UPDATE jobs
                            SET status       = :newStatus,
                                available_at = CASE WHEN :newStatus = 'RETRY_WAIT'
                                                    THEN now() + (:delayMs * interval '1 millisecond')
                                                    ELSE available_at END,
                                locked_by    = NULL,
                                lease_until  = NULL,
                                heartbeat_at = NULL,
                                last_error   = :error,
                                updated_at   = now()
                            WHERE id = :id
                              AND status = 'RUNNING'
                              AND locked_by = :workerId
                              AND attempt_count = :attempt
                            RETURNING id, attempt_count
                        ), closed AS (
                            UPDATE job_attempts a
                            SET finished_at   = now(),
                                outcome       = :outcome,
                                error_summary = :error
                            FROM finished f
                            WHERE a.job_id = f.id
                              AND a.attempt_no = f.attempt_count
                        )
                        SELECT count(*) FROM finished
                        """)
                .param("newStatus", newStatus)
                .param("delayMs", retryDelay.toMillis())
                .param("error", truncate(error))
                .param("id", jobId)
                .param("workerId", workerId)
                .param("attempt", attempt)
                .param("outcome", outcome.name())
                .query(Integer.class)
                .single();
        return finished == 1;
    }

    /**
     * Worker'ın hâlâ sahibi olduğu işlerin lease'ini uzatır. Dönen liste, uzatılabilen
     * işlerdir; listede olmayan bir iş artık bu worker'a ait değildir.
     */
    public List<UUID> renewLeases(String workerId, Collection<UUID> jobIds, long leaseSeconds) {
        if (jobIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        UPDATE jobs
                        SET lease_until  = now() + (:leaseSeconds * interval '1 second'),
                            heartbeat_at = now()
                        WHERE id IN (:ids)
                          AND status = 'RUNNING'
                          AND locked_by = :workerId
                        RETURNING id
                        """)
                .param("ids", jobIds)
                .param("workerId", workerId)
                .param("leaseSeconds", leaseSeconds)
                .query(UUID.class)
                .list();
    }

    /**
     * Lease'i dolmuş RUNNING işleri geri alır: deneme hakkı varsa RETRY_WAIT, yoksa DEAD.
     * Birden fazla process aynı anda çalıştırsa da güvenli; SKIP LOCKED sayesinde
     * aynı satırı iki kez işlemezler.
     */
    public List<RecoveredJob> recoverExpiredLeases(int limit) {
        return jdbc.sql("""
                        WITH recovered AS (
                            UPDATE jobs
                            SET status       = CASE WHEN attempt_count >= max_attempts
                                                    THEN 'DEAD' ELSE 'RETRY_WAIT' END,
                                available_at = now(),
                                last_error   = 'Lease expired while held by ' || locked_by,
                                locked_by    = NULL,
                                lease_until  = NULL,
                                heartbeat_at = NULL,
                                updated_at   = now()
                            FROM (
                                SELECT id, locked_by AS previous_owner
                                FROM jobs
                                WHERE status = 'RUNNING'
                                  AND lease_until < now()
                                ORDER BY lease_until
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED
                            ) expired
                            WHERE jobs.id = expired.id
                            RETURNING jobs.id, jobs.job_type, jobs.status, jobs.attempt_count,
                                      expired.previous_owner
                        ), closed AS (
                            UPDATE job_attempts a
                            SET finished_at   = now(),
                                outcome       = 'LEASE_EXPIRED',
                                error_summary = 'Lease expired while held by ' || r.previous_owner
                            FROM recovered r
                            WHERE a.job_id = r.id
                              AND a.attempt_no = r.attempt_count
                              AND a.finished_at IS NULL
                        )
                        SELECT id, job_type, previous_owner, status FROM recovered
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new RecoveredJob(
                        rs.getObject("id", UUID.class),
                        rs.getString("job_type"),
                        rs.getString("previous_owner"),
                        rs.getString("status")))
                .list();
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_LENGTH);
    }
}
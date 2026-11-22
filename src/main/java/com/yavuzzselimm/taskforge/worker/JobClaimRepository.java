package com.yavuzzselimm.taskforge.worker;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;


@Repository
public class JobClaimRepository {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;

    public JobClaimRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ClaimedJob> claimNext(String workerId, long leaseSeconds) {
        return jdbc.sql("""
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
                        RETURNING id, job_type, payload::text AS payload, attempt_count, max_attempts
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
        int updated = jdbc.sql("""
                        UPDATE jobs
                        SET status       = 'COMPLETED',
                            locked_by    = NULL,
                            lease_until  = NULL,
                            heartbeat_at = NULL,
                            last_error   = NULL,
                            updated_at   = now()
                        WHERE id = :id
                          AND status = 'RUNNING'
                          AND locked_by = :workerId
                          AND attempt_count = :attempt
                        """)
                .param("id", jobId)
                .param("workerId", workerId)
                .param("attempt", attempt)
                .update();
        return updated == 1;
    }

    public boolean markDead(UUID jobId, String workerId, int attempt, String error) {
        int updated = jdbc.sql("""
                        UPDATE jobs
                        SET status       = 'DEAD',
                            locked_by    = NULL,
                            lease_until  = NULL,
                            heartbeat_at = NULL,
                            last_error   = :error,
                            updated_at   = now()
                        WHERE id = :id
                          AND status = 'RUNNING'
                          AND locked_by = :workerId
                          AND attempt_count = :attempt
                        """)
                .param("id", jobId)
                .param("workerId", workerId)
                .param("attempt", attempt)
                .param("error", truncate(error))
                .update();
        return updated == 1;
    }

    public boolean markRetry(UUID jobId, String workerId, int attempt, String error, Duration delay) {
        int updated = jdbc.sql("""
                        UPDATE jobs
                        SET status       = 'RETRY_WAIT',
                            available_at = now() + (:delayMs * interval '1 millisecond'),
                            locked_by    = NULL,
                            lease_until  = NULL,
                            heartbeat_at = NULL,
                            last_error   = :error,
                            updated_at   = now()
                        WHERE id = :id
                          AND status = 'RUNNING'
                          AND locked_by = :workerId
                          AND attempt_count = :attempt
                        """)
                .param("id", jobId)
                .param("workerId", workerId)
                .param("attempt", attempt)
                .param("error", truncate(error))
                .param("delayMs", delay.toMillis())
                .update();
        return updated == 1;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_LENGTH);
    }
}
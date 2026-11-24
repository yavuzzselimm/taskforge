package com.yavuzzselimm.taskforge.job;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JobAttemptRepository {

    private final JdbcClient jdbc;

    public JobAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<JobAttemptResponse> findByJobId(UUID jobId) {
        return jdbc.sql("""
                        SELECT attempt_no, worker_id, started_at, finished_at, outcome, error_summary
                        FROM job_attempts
                        WHERE job_id = :jobId
                        ORDER BY attempt_no
                        """)
                .param("jobId", jobId)
                .query((rs, n) -> new JobAttemptResponse(
                        rs.getInt("attempt_no"),
                        rs.getString("worker_id"),
                        toInstant(rs.getTimestamp("started_at")),
                        toInstant(rs.getTimestamp("finished_at")),
                        rs.getString("outcome"),
                        rs.getString("error_summary")))
                .list();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}

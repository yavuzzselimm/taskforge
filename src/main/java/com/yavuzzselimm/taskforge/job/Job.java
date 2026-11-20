package com.yavuzzselimm.taskforge.job;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private JobStatus status;

    @Column(nullable = false)
    private short priority;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_error")
    private String lastError;

    /** JPA için gerekli; kodda kullanmayın. */
    protected Job() {
    }

    /** Yeni bir işi her zaman bu metotla oluşturun. */
    public static Job create(String jobType,
                             String payload,
                             short priority,
                             Instant scheduledAt,
                             int maxAttempts,
                             String idempotencyKey) {
        Job job = new Job();
        job.jobType = jobType;
        job.payload = payload;
        job.priority = priority;
        job.scheduledAt = scheduledAt;
        job.maxAttempts = maxAttempts;
        job.idempotencyKey = idempotencyKey;
        job.status = JobStatus.READY;
        job.attemptCount = 0;
        job.availableAt = (scheduledAt != null) ? scheduledAt : Instant.now();
        return job;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getJobType() { return jobType; }
    public String getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public short getPriority() { return priority; }
    public Instant getScheduledAt() { return scheduledAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public Instant getAvailableAt() { return availableAt; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getHeartbeatAt() { return heartbeatAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getLastError() { return lastError; }
}
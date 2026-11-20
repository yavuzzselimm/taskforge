package com.yavuzzselimm.taskforge.job;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String type,
        @JsonRawValue String payload,
        JobStatus status,
        short priority,
        int attemptCount,
        int maxAttempts,
        Instant scheduledAt,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt,
        String lastError
) {
    static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getJobType(),
                job.getPayload(),
                job.getStatus(),
                job.getPriority(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getScheduledAt(),
                job.getAvailableAt(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getLastError()
        );
    }
}
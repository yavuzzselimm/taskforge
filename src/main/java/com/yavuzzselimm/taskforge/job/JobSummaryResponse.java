package com.yavuzzselimm.taskforge.job;

import java.time.Instant;
import java.util.UUID;

// Liste ucunda payload dönmüyoruz; tam hali GET /api/v1/jobs/{id}'de.
public record JobSummaryResponse(
        UUID id,
        String type,
        JobStatus status,
        short priority,
        int attemptCount,
        int maxAttempts,
        Instant availableAt,
        Instant createdAt,
        Instant updatedAt
) {
    static JobSummaryResponse from(Job job) {
        return new JobSummaryResponse(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                job.getPriority(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getAvailableAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}

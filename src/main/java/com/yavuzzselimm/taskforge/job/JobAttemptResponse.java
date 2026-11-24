package com.yavuzzselimm.taskforge.job;

import java.time.Instant;

public record JobAttemptResponse(
        int attemptNo,
        String workerId,
        Instant startedAt,
        Instant finishedAt,
        String outcome,
        String errorSummary
) {
}

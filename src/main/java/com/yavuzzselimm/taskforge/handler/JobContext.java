package com.yavuzzselimm.taskforge.handler;

import java.util.UUID;

/** Handler'a verilen bilgiler. Handler, Job entity'sini doğrudan görmez. */
public record JobContext(
        UUID jobId,
        String jobType,
        String payload,
        int attempt
) {
}
package com.yavuzzselimm.taskforge.worker;

import java.util.UUID;

/** Worker'ın sahiplendiği işi çalıştırmak için gereken bilgiler. */
public record ClaimedJob(
        UUID id,
        String jobType,
        String payload,
        int attempt
) {
}
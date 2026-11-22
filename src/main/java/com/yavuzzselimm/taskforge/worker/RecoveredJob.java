package com.yavuzzselimm.taskforge.worker;

import java.util.UUID;

public record RecoveredJob(
        UUID id,
        String jobType,
        String previousOwner,
        String newStatus
) {
}

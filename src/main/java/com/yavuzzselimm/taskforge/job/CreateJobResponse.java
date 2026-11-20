package com.yavuzzselimm.taskforge.job;

import java.util.UUID;

public record CreateJobResponse(UUID jobId, JobStatus status) {
}
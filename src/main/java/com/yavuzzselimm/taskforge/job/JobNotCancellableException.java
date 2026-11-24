package com.yavuzzselimm.taskforge.job;

import java.util.UUID;

public class JobNotCancellableException extends RuntimeException {

    private final JobStatus status;

    public JobNotCancellableException(UUID id, JobStatus status) {
        super("Job " + id + " is " + status + "; only READY or RETRY_WAIT jobs can be cancelled");
        this.status = status;
    }

    public JobStatus getStatus() {
        return status;
    }
}

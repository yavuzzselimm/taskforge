package com.yavuzzselimm.taskforge.worker;

/** job_attempts.outcome değerleri. */
public enum AttemptOutcome {
    SUCCESS,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    LEASE_EXPIRED
}

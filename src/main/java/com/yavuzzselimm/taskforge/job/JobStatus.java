package com.yavuzzselimm.taskforge.job;

public enum JobStatus {
    READY,
    RUNNING,
    RETRY_WAIT,
    COMPLETED,
    DEAD,
    CANCELLED;

    // Bu durumdan sonra iş bir daha çalışmaz.
    public boolean isTerminal() {
        return this == COMPLETED || this == DEAD || this == CANCELLED;
    }
}

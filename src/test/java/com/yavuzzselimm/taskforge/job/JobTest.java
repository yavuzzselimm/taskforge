package com.yavuzzselimm.taskforge.job;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JobTest {

    @Test
    void create_startsInReadyStateWithNoAttempts() {
        Job job = Job.create("SEND_ORDER_EMAIL", "{}", (short) 3, null, 5, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.READY);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getMaxAttempts()).isEqualTo(5);
        assertThat(job.getPriority()).isEqualTo((short) 3);
        assertThat(job.getLockedBy()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
    }

    @Test
    void create_withoutScheduledAt_isAvailableImmediately() {
        Instant before = Instant.now();

        Job job = Job.create("SEND_ORDER_EMAIL", "{}", (short) 0, null, 5, null);

        assertThat(job.getAvailableAt()).isBetween(before, Instant.now());
    }

    @Test
    void create_withScheduledAt_becomesAvailableAtThatTime() {
        Instant later = Instant.now().plus(1, ChronoUnit.HOURS);

        Job job = Job.create("SEND_ORDER_EMAIL", "{}", (short) 0, later, 5, null);

        assertThat(job.getScheduledAt()).isEqualTo(later);
        assertThat(job.getAvailableAt()).isEqualTo(later);
    }

    @Test
    void onlyCompletedDeadAndCancelledAreTerminal() {
        assertThat(JobStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(JobStatus.DEAD.isTerminal()).isTrue();
        assertThat(JobStatus.CANCELLED.isTerminal()).isTrue();

        assertThat(JobStatus.READY.isTerminal()).isFalse();
        assertThat(JobStatus.RUNNING.isTerminal()).isFalse();
        assertThat(JobStatus.RETRY_WAIT.isTerminal()).isFalse();
    }
}
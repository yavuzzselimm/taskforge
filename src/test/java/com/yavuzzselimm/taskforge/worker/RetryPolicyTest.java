package com.yavuzzselimm.taskforge.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void delayDoublesWithEachAttempt() {
        RetryPolicy policy = new RetryPolicy(100, 10_000, bound -> 0);

        assertThat(policy.delayAfter(1).toMillis()).isEqualTo(100);
        assertThat(policy.delayAfter(2).toMillis()).isEqualTo(200);
        assertThat(policy.delayAfter(3).toMillis()).isEqualTo(400);
    }

    @Test
    void delayIsCappedAtMaxDelay() {
        RetryPolicy policy = new RetryPolicy(100, 1_000, bound -> 0);

        assertThat(policy.delayAfter(5).toMillis()).isEqualTo(1_000);
        assertThat(policy.delayAfter(1_000).toMillis()).isEqualTo(1_000);
    }

    @Test
    void jitterAddsAtMostTwentyPercent() {
        RetryPolicy maxJitter = new RetryPolicy(100, 10_000, bound -> bound);
        assertThat(maxJitter.delayAfter(1).toMillis()).isEqualTo(120);

        RetryPolicy random = new RetryPolicy(1_000, 10_000);
        for (int i = 0; i < 1_000; i++) {
            assertThat(random.delayAfter(1).toMillis()).isBetween(1_000L, 1_200L);
        }
    }
}

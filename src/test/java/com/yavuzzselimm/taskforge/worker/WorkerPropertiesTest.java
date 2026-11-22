package com.yavuzzselimm.taskforge.worker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPropertiesTest {

    @Test
    void heartbeatMustBeShorterThanLease() {
        assertThatThrownBy(() -> new WorkerProperties(null, 4, 1000, 200, 10, 10_000, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval-ms");
    }
}

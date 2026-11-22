package com.yavuzzselimm.taskforge.handler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobHandlerRegistryTest {

    @Test
    void findsHandlerByJobType() {
        JobHandler handler = fakeHandler("A");
        JobHandlerRegistry registry = new JobHandlerRegistry(List.of(handler, fakeHandler("B")));

        assertThat(registry.find("A")).containsSame(handler);
        assertThat(registry.supports("B")).isTrue();
        assertThat(registry.supports("C")).isFalse();
        assertThat(registry.supportedTypes()).containsExactly("A", "B");
    }

    @Test
    void duplicateJobTypeFailsAtStartup() {
        assertThatThrownBy(() -> new JobHandlerRegistry(List.of(fakeHandler("A"), fakeHandler("A"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate handler for job type A");
    }

    private static JobHandler fakeHandler(String type) {
        return new JobHandler() {
            @Override
            public String jobType() {
                return type;
            }

            @Override
            public void handle(JobContext context) {
            }
        };
    }
}
package com.yavuzzselimm.taskforge.metrics;

import com.yavuzzselimm.taskforge.TestcontainersConfiguration;
import com.yavuzzselimm.taskforge.job.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "taskforge.worker.enabled=false")
@Import(TestcontainersConfiguration.class)
class QueueDepthMetricsTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jobRepository.deleteAll();
    }

    @Test
    void queueDepth_countsOnlyWaitingJobsByStatus() {
        insertJobs("READY", 3);
        insertJobs("RETRY_WAIT", 1);
        insertJobs("COMPLETED", 2);
        insertJobs("DEAD", 1);

        assertThat(queueDepth("READY")).isEqualTo(3);
        assertThat(queueDepth("RETRY_WAIT")).isEqualTo(1);
    }

    private double queueDepth(String status) {
        Gauge gauge = meterRegistry.get("queue.depth")
                .tag("status", status)
                .tag("job_type", "SEND_ORDER_EMAIL")
                .gauge();
        return gauge.value();
    }

    private void insertJobs(String status, int count) {
        jdbc.sql("""
                        INSERT INTO jobs (job_type, payload, status)
                        SELECT 'SEND_ORDER_EMAIL', '{}'::jsonb, :status FROM generate_series(1, :count)
                        """)
                .param("status", status)
                .param("count", count)
                .update();
    }
}

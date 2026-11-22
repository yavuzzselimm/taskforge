package com.yavuzzselimm.taskforge.metrics;

import com.yavuzzselimm.taskforge.handler.JobHandlerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kuyrukta bekleyen iş sayısı. Değer her okunduğunda DB'den sayılır; sorgu
 * idx_jobs_claimable partial index'ini kullandığı için ucuz.
 */
@Component
public class QueueDepthMetrics implements MeterBinder {

    private static final List<String> WAITING_STATUSES = List.of("READY", "RETRY_WAIT");

    private final JdbcClient jdbc;
    private final JobHandlerRegistry handlerRegistry;

    public QueueDepthMetrics(JdbcClient jdbc, JobHandlerRegistry handlerRegistry) {
        this.jdbc = jdbc;
        this.handlerRegistry = handlerRegistry;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String jobType : handlerRegistry.supportedTypes()) {
            for (String status : WAITING_STATUSES) {
                Gauge.builder("queue.depth", () -> count(status, jobType))
                        .description("Jobs waiting to be claimed")
                        .tag("status", status)
                        .tag("job_type", jobType)
                        .register(registry);
            }
        }
    }

    private long count(String status, String jobType) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE status = :status AND job_type = :jobType")
                .param("status", status)
                .param("jobType", jobType)
                .query(Long.class)
                .single();
    }
}

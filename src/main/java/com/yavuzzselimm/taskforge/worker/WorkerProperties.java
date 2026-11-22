package com.yavuzzselimm.taskforge.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** application.yml'deki taskforge.worker.* ayarları. */
@ConfigurationProperties(prefix = "taskforge.worker")
public record WorkerProperties(
        String id,
        @DefaultValue("4") int threads,
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue("200") long pollJitterMs,
        @DefaultValue("30") long leaseSeconds,
        @DefaultValue("10000") long heartbeatIntervalMs,
        @DefaultValue("20") long shutdownTimeoutSeconds
) {

    public WorkerProperties {
        if (threads < 1) {
            throw new IllegalArgumentException("taskforge.worker.threads must be at least 1");
        }
        // heartbeat en az bir kez atılamadan lease dolarsa sağlıklı işler de geri alınır
        if (heartbeatIntervalMs >= leaseSeconds * 1000) {
            throw new IllegalArgumentException(
                    "taskforge.worker.heartbeat-interval-ms must be shorter than lease-seconds");
        }
    }
}

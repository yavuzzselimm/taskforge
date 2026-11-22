package com.yavuzzselimm.taskforge.worker;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sahibi ölmüş (lease'i dolmuş) işleri belirli aralıklarla geri kazanır.
 * Worker'ın açık olduğu her process'te çalışır; leader election gerekmiyor.
 */
@Component
@ConditionalOnProperty(prefix = "taskforge.worker", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class LeaseRecovery implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaseRecovery.class);

    private final JobClaimRepository claimRepository;
    private final MeterRegistry meterRegistry;
    private final long intervalMs;
    private final int batchSize;

    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    public LeaseRecovery(JobClaimRepository claimRepository,
                         MeterRegistry meterRegistry,
                         @Value("${taskforge.recovery.interval-ms:5000}") long intervalMs,
                         @Value("${taskforge.recovery.batch-size:100}") int batchSize) {
        this.claimRepository = claimRepository;
        this.meterRegistry = meterRegistry;
        this.intervalMs = intervalMs;
        this.batchSize = batchSize;
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("lease-recovery").factory());
        scheduler.scheduleWithFixedDelay(this::recoverSafely, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void recoverSafely() {
        // Buradan exception kaçarsa ScheduledExecutorService sonraki çalıştırmaları
        // sessizce iptal eder, recovery bir daha hiç çalışmaz.
        try {
            recover();
        } catch (RuntimeException e) {
            log.error("Lease recovery failed, will retry in {} ms", intervalMs, e);
        }
    }

    void recover() {
        List<RecoveredJob> recovered;
        do {
            recovered = claimRepository.recoverExpiredLeases(batchSize);
            for (RecoveredJob job : recovered) {
                Counter.builder("expired.leases")
                        .tag("job_type", job.jobType())
                        .register(meterRegistry)
                        .increment();
                log.warn("Recovered job {} from {} (lease expired), now {}",
                        job.id(), job.previousOwner(), job.newStatus());
            }
        } while (recovered.size() == batchSize);
    }
}

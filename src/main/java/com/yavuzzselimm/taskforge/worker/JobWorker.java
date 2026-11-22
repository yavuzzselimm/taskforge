package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.handler.JobContext;
import com.yavuzzselimm.taskforge.handler.JobHandler;
import com.yavuzzselimm.taskforge.handler.JobHandlerRegistry;
import com.yavuzzselimm.taskforge.handler.PermanentJobException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Tek bir poller thread kuyruktan iş alır, işleri sabit boyutlu bir thread pool çalıştırır.
 * Poller yalnızca boşta thread varken claim yapar.
 */
@Component
@ConditionalOnProperty(prefix = "taskforge.worker", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JobWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobClaimRepository claimRepository;
    private final JobHandlerRegistry handlerRegistry;
    private final RetryPolicy retryPolicy;
    private final MeterRegistry meterRegistry;
    private final long leaseSeconds;
    private final long pollIntervalMs;
    private final long pollJitterMs;
    private final int threads;
    private final long shutdownTimeoutSeconds;
    private final String workerId;

    private volatile boolean running;
    private ExecutorService executor;
    private Semaphore freeSlots;
    private Thread poller;

    public JobWorker(JobClaimRepository claimRepository,
                     JobHandlerRegistry handlerRegistry,
                     RetryPolicy retryPolicy,
                     MeterRegistry meterRegistry,
                     @Value("${taskforge.worker.lease-seconds:30}") long leaseSeconds,
                     @Value("${taskforge.worker.poll-interval-ms:1000}") long pollIntervalMs,
                     @Value("${taskforge.worker.poll-jitter-ms:200}") long pollJitterMs,
                     @Value("${taskforge.worker.threads:4}") int threads,
                     @Value("${taskforge.worker.shutdown-timeout-seconds:20}") long shutdownTimeoutSeconds,
                     @Value("${taskforge.worker.id:}") String configuredWorkerId) {
        if (threads < 1) {
            throw new IllegalArgumentException("taskforge.worker.threads must be at least 1");
        }
        this.claimRepository = claimRepository;
        this.handlerRegistry = handlerRegistry;
        this.retryPolicy = retryPolicy;
        this.meterRegistry = meterRegistry;
        this.leaseSeconds = leaseSeconds;
        this.pollIntervalMs = pollIntervalMs;
        this.pollJitterMs = pollJitterMs;
        this.threads = threads;
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        this.workerId = configuredWorkerId.isBlank() ? generateWorkerId() : configuredWorkerId;
    }

    @Override
    public void start() {
        executor = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name(workerId + "-exec-", 1).factory());
        freeSlots = new Semaphore(threads);
        running = true;
        poller = Thread.ofPlatform().name(workerId + "-poller").start(this::pollLoop);
        log.info("Worker {} started ({} threads, lease {}s)", workerId, threads, leaseSeconds);
    }

    @Override
    public void stop() {
        running = false;
        poller.interrupt();
        try {
            poller.join(TimeUnit.SECONDS.toMillis(5));

            executor.shutdown();
            if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("Running jobs did not finish in {}s, interrupting them", shutdownTimeoutSeconds);
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker {} stopped", workerId);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        while (running) {
            try {
                freeSlots.acquire();
                Optional<ClaimedJob> claimed = claimSafely();
                if (claimed.isEmpty()) {
                    freeSlots.release();
                    Thread.sleep(nextPollDelayMs());
                    continue;
                }
                submit(claimed.get());
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // Aynı anda açılan worker'lar DB'ye hep aynı anda gitmesin diye bekleme süresine rastgele ek
    private long nextPollDelayMs() {
        if (pollJitterMs <= 0) {
            return pollIntervalMs;
        }
        return pollIntervalMs + ThreadLocalRandom.current().nextLong(pollJitterMs + 1);
    }

    private Optional<ClaimedJob> claimSafely() {
        try {
            return claimRepository.claimNext(workerId, leaseSeconds);
        } catch (RuntimeException e) {
            // DB geçici olarak erişilemiyorsa poller ölmesin, bir sonraki turda tekrar dener
            log.error("Failed to claim next job", e);
            return Optional.empty();
        }
    }

    private void submit(ClaimedJob job) {
        try {
            executor.execute(() -> {
                try {
                    execute(job);
                } catch (RuntimeException e) {
                    log.error("Unexpected error while processing job {}", job.id(), e);
                } finally {
                    freeSlots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            // Kapanış sırasında olabilir; iş RUNNING kalır, lease dolunca recovery alır
            freeSlots.release();
            log.warn("Job {} claimed but could not be submitted (worker shutting down)", job.id());
        }
    }

    void execute(ClaimedJob job) {
        Optional<JobHandler> handler = handlerRegistry.find(job.jobType());
        if (handler.isEmpty()) {
            markDead(job, "No handler registered for job type " + job.jobType());
            return;
        }

        try {
            handler.get().handle(new JobContext(job.id(), job.jobType(), job.payload(), job.attempt()));
        } catch (InterruptedException e) {
            // Uygulama kapanıyor. İşi olduğu gibi bırakıyoruz; lease süresi dolunca
            // recovery (Aşama 4) onu yeniden kuyruğa alacak.
            Thread.currentThread().interrupt();
            log.warn("Worker interrupted while running job {}", job.id());
            return;
        } catch (PermanentJobException e) {
            markDead(job, describe(e));
            return;
        } catch (Exception e) {
            retryOrGiveUp(job, describe(e));
            return;
        }

        if (claimRepository.markCompleted(job.id(), workerId, job.attempt())) {
            counter("jobs.completed", job).increment();
            log.info("Job {} completed (type {}, attempt {})", job.id(), job.jobType(), job.attempt());
        } else {
            log.warn("Job {} was no longer owned by this worker; result discarded", job.id());
        }
    }

    private void retryOrGiveUp(ClaimedJob job, String error) {
        if (job.attempt() >= job.maxAttempts()) {
            markDead(job, error);
            return;
        }

        Duration delay = retryPolicy.delayAfter(job.attempt());
        if (claimRepository.markRetry(job.id(), workerId, job.attempt(), error, delay)) {
            counter("job.retries", job).increment();
            log.warn("Job {} failed (attempt {}/{}), retrying in {} ms: {}",
                    job.id(), job.attempt(), job.maxAttempts(), delay.toMillis(), error);
        } else {
            log.warn("Job {} failed but was no longer owned by this worker", job.id());
        }
    }

    private static String describe(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private void markDead(ClaimedJob job, String error) {
        if (claimRepository.markDead(job.id(), workerId, job.attempt(), error)) {
            counter("jobs.failed", job).increment();
            log.warn("Job {} failed and marked DEAD: {}", job.id(), error);
        } else {
            log.warn("Job {} failed but was no longer owned by this worker", job.id());
        }
    }

    // Label olarak yalnızca job_type: job ID label yapılırsa her iş yeni bir zaman serisi açar
    private Counter counter(String name, ClaimedJob job) {
        return Counter.builder(name)
                .tag("job_type", job.jobType())
                .register(meterRegistry);
    }

    public String workerId() {
        return workerId;
    }

    private static String generateWorkerId() {
        return "worker-" + ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}

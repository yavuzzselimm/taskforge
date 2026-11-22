package com.yavuzzselimm.taskforge.worker;

import com.yavuzzselimm.taskforge.handler.JobContext;
import com.yavuzzselimm.taskforge.handler.JobHandler;
import com.yavuzzselimm.taskforge.handler.JobHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Basit, tek thread'li worker: belirli aralıklarla kuyruğa bakar, uygun iş
 * oldukça alıp çalıştırır. Aşama 3'te thread pool'a dönüşecek.
 */
@Component
@ConditionalOnProperty(prefix = "taskforge.worker", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JobClaimRepository claimRepository;
    private final JobHandlerRegistry handlerRegistry;
    private final long leaseSeconds;
    private final String workerId;

    public JobWorker(JobClaimRepository claimRepository,
                     JobHandlerRegistry handlerRegistry,
                     @Value("${taskforge.worker.lease-seconds:30}") long leaseSeconds,
                     @Value("${taskforge.worker.id:}") String configuredWorkerId) {
        this.claimRepository = claimRepository;
        this.handlerRegistry = handlerRegistry;
        this.leaseSeconds = leaseSeconds;
        this.workerId = configuredWorkerId.isBlank() ? generateWorkerId() : configuredWorkerId;
        log.info("Worker {} started (lease {}s)", workerId, leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${taskforge.worker.poll-interval-ms:1000}")
    public void poll() {
        Optional<ClaimedJob> next;
        while ((next = claimRepository.claimNext(workerId, leaseSeconds)).isPresent()) {
            execute(next.get());
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
        } catch (Exception e) {
            markDead(job, e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        if (claimRepository.markCompleted(job.id(), workerId, job.attempt())) {
            log.info("Job {} completed (type {}, attempt {})", job.id(), job.jobType(), job.attempt());
        } else {
            log.warn("Job {} was no longer owned by this worker; result discarded", job.id());
        }
    }

    private void markDead(ClaimedJob job, String error) {
        if (claimRepository.markDead(job.id(), workerId, job.attempt(), error)) {
            log.warn("Job {} failed and marked DEAD: {}", job.id(), error);
        } else {
            log.warn("Job {} failed but was no longer owned by this worker", job.id());
        }
    }

    public String workerId() {
        return workerId;
    }

    private static String generateWorkerId() {
        return "worker-" + ProcessHandle.current().pid() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
package com.yavuzzselimm.taskforge.job;

import com.yavuzzselimm.taskforge.handler.JobHandlerRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class JobService {

    static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    static final short DEFAULT_PRIORITY = 0;
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final int MAX_PAGE_SIZE = 100;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<JobStatus> CANCELLABLE = EnumSet.of(JobStatus.READY, JobStatus.RETRY_WAIT);

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final JobHandlerRegistry handlerRegistry;

    public JobService(JobRepository jobRepository,
                      JobAttemptRepository attemptRepository,
                      JobHandlerRegistry handlerRegistry) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.handlerRegistry = handlerRegistry;
    }

    /**
     * Yeni bir iş oluşturur. Aynı Idempotency-Key ile daha önce iş oluşturulmuşsa
     * yenisini oluşturmaz, mevcut işi döndürür.
     */
    public CreateJobResult create(CreateJobRequest request, String idempotencyKey) {
        validateJobType(request.type());
        validateIdempotencyKey(idempotencyKey);
        String payloadJson = toPayloadJson(request);

        if (idempotencyKey != null) {
            Optional<Job> existing = jobRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return new CreateJobResult(existing.get(), true);
            }
        }

        Job job = Job.create(
                request.type(),
                payloadJson,
                request.priority() != null ? request.priority() : DEFAULT_PRIORITY,
                request.scheduledAt(),
                request.maxAttempts() != null ? request.maxAttempts() : DEFAULT_MAX_ATTEMPTS,
                idempotencyKey
        );

        try {
            return new CreateJobResult(jobRepository.save(job), false);
        } catch (DataIntegrityViolationException e) {
            // Aynı anahtarla iki istek aynı anda geldiyse, ikisi de yukarıdaki kontrolden
            // geçebilir. Veritabanındaki UNIQUE kısıtı ikincisini reddeder; o zaman
            // ilk isteğin oluşturduğu işi döndürürüz.
            if (idempotencyKey == null) {
                throw e;
            }
            return jobRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> new CreateJobResult(existing, true))
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public Job get(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Job> list(Set<JobStatus> statuses, String type, int page, int size) {
        if (page < 0) {
            throw new InvalidJobRequestException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidJobRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Set<JobStatus> filter = (statuses == null || statuses.isEmpty())
                ? EnumSet.allOf(JobStatus.class)
                : statuses;
        return jobRepository.search(filter, type, PageRequest.of(page, size, NEWEST_FIRST));
    }

    @Transactional
    public Job cancel(UUID id) {
        int updated = jobRepository.cancelIfWaiting(
                id, CANCELLABLE, JobStatus.CANCELLED, Instant.now());

        Job job = get(id);
        if (updated == 0 && job.getStatus() != JobStatus.CANCELLED) {
            throw new JobNotCancellableException(id, job.getStatus());
        }
        return job;
    }

    public List<JobAttemptResponse> attempts(UUID id) {
        // Hiç denenmemiş iş boş liste döner; olmayan iş 404 olmalı.
        if (!jobRepository.existsById(id)) {
            throw new JobNotFoundException(id);
        }
        return attemptRepository.findByJobId(id);
    }

    private void validateJobType(String jobType) {
        if (!handlerRegistry.supports(jobType)) {
            throw new InvalidJobRequestException(
                    "Unsupported job type: " + jobType
                            + ". Supported types: " + handlerRegistry.supportedTypes());
        }
    }

    private void validateIdempotencyKey(String key) {
        if (key == null) {
            return;
        }
        if (key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidJobRequestException(
                    "Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
    }

    private String toPayloadJson(CreateJobRequest request) {
        if (request.payload() == null || !request.payload().isObject()) {
            throw new InvalidJobRequestException("payload must be a JSON object");
        }
        String json = request.payload().toString();
        if (json.length() > MAX_PAYLOAD_CHARS) {
            throw new InvalidJobRequestException(
                    "payload must not exceed " + MAX_PAYLOAD_CHARS + " characters");
        }
        return json;
    }

    public record CreateJobResult(Job job, boolean replayed) {
    }
}
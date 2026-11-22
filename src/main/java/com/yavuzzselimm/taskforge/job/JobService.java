package com.yavuzzselimm.taskforge.job;

import com.yavuzzselimm.taskforge.handler.JobHandlerRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;
    static final short DEFAULT_PRIORITY = 0;
    static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final JobRepository jobRepository;
    private final JobHandlerRegistry handlerRegistry;

    public JobService(JobRepository jobRepository, JobHandlerRegistry handlerRegistry) {
        this.jobRepository = jobRepository;
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
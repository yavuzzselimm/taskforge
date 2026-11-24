package com.yavuzzselimm.taskforge.job;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<CreateJobResponse> create(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        JobService.CreateJobResult result = jobService.create(request, idempotencyKey);
        Job job = result.job();

        return ResponseEntity
                .accepted()
                .location(URI.create("/api/v1/jobs/" + job.getId()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(new CreateJobResponse(job.getId(), job.getStatus()));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobService.get(id));
    }

    @GetMapping("/{id}/attempts")
    public List<JobAttemptResponse> attempts(@PathVariable UUID id) {
        return jobService.attempts(id);
    }
}
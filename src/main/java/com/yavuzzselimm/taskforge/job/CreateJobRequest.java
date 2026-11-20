package com.yavuzzselimm.taskforge.job;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

public record CreateJobRequest(

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                message = "must be UPPER_SNAKE_CASE, e.g. SEND_ORDER_EMAIL")
        String type,

        @NotNull
        JsonNode payload,

        @Min(0) @Max(10)
        Short priority,

        Instant scheduledAt,

        @Min(1) @Max(20)
        Integer maxAttempts
) {
}
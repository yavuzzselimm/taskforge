package com.yavuzzselimm.taskforge.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sahte rapor üretimi: saniyeler, hatta dakikalar sürebilen bir iş. Lease süresinden
 * uzun çalışabildiği için heartbeat olmadan recovery tarafından yanlışlıkla geri alınırdı.
 * Payload: {"reportType": "MONTHLY_SALES", "durationSeconds": 45}
 */
@Component
public class GenerateReportHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerateReportHandler.class);
    private static final int MAX_DURATION_SECONDS = 600;

    private final JsonMapper jsonMapper;

    public GenerateReportHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String jobType() {
        return "GENERATE_REPORT";
    }

    @Override
    public void handle(JobContext context) throws Exception {
        JsonNode payload = jsonMapper.readTree(context.payload());
        String reportType = payload.path("reportType").asString("");
        int durationSeconds = payload.path("durationSeconds").asInt(30);

        // kaç kez denersek deneyelim düzelmeyecek hatalar
        if (reportType.isBlank()) {
            throw new PermanentJobException("reportType is required");
        }
        if (durationSeconds < 1 || durationSeconds > MAX_DURATION_SECONDS) {
            throw new PermanentJobException(
                    "durationSeconds must be between 1 and " + MAX_DURATION_SECONDS);
        }

        for (int second = 1; second <= durationSeconds; second++) {
            Thread.sleep(1000);
            if (second % 10 == 0) {
                log.info("Report {} for job {}: {}/{}s", reportType, context.jobId(), second, durationSeconds);
            }
        }
        log.info("Report {} generated (job {}, attempt {})", reportType, context.jobId(), context.attempt());
    }
}

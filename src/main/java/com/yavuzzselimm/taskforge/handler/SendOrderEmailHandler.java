package com.yavuzzselimm.taskforge.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sahte e-posta handler'ı: gerçekten e-posta göndermez, log'a yazar ve
 * kısa bir gecikme simüle eder. Amaç SMTP değil, iş akışının çalışmasıdır.
 */
@Component
public class SendOrderEmailHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SendOrderEmailHandler.class);
    private static final long SIMULATED_SEND_MILLIS = 200;

    private final JsonMapper jsonMapper;

    public SendOrderEmailHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String jobType() {
        return "SEND_ORDER_EMAIL";
    }

    @Override
    public void handle(JobContext context) throws Exception {
        JsonNode payload = jsonMapper.readTree(context.payload());
        String orderId = payload.path("orderId").asString("unknown");

        Thread.sleep(SIMULATED_SEND_MILLIS);

        log.info("Order confirmation email sent for order {} (job {}, attempt {})",
                orderId, context.jobId(), context.attempt());
    }
}
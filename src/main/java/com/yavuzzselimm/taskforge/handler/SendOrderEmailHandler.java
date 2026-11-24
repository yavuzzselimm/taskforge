package com.yavuzzselimm.taskforge.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Sipariş onay e-postası. İş birden fazla kez çalışabilir (worker sonucu yazamadan çökerse
 * recovery onu tekrar kuyruğa alır), bu yüzden sağlayıcıya her denemede aynı anahtarı veriyoruz.
 */
@Component
public class SendOrderEmailHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(SendOrderEmailHandler.class);

    private final JsonMapper jsonMapper;
    private final FakeEmailProvider emailProvider;

    public SendOrderEmailHandler(JsonMapper jsonMapper, FakeEmailProvider emailProvider) {
        this.jsonMapper = jsonMapper;
        this.emailProvider = emailProvider;
    }

    @Override
    public String jobType() {
        return "SEND_ORDER_EMAIL";
    }

    @Override
    public void handle(JobContext context) throws Exception {
        JsonNode payload = jsonMapper.readTree(context.payload());
        String orderId = payload.path("orderId").asString("unknown");

        boolean sent = emailProvider.send(idempotencyKey(context.jobId()), orderId);
        if (!sent) {
            log.info("Job {} attempt {}: email already sent earlier, nothing to do",
                    context.jobId(), context.attempt());
        }
    }

    // Deneme numarası anahtara girmez; amaç tüm denemelerde aynı anahtarı kullanmak.
    public static String idempotencyKey(UUID jobId) {
        return "order-email-" + jobId;
    }
}

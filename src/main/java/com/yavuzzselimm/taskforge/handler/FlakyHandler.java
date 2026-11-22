package com.yavuzzselimm.taskforge.handler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Hata enjeksiyonu için test/demo handler'ı. Davranışı payload belirler:
 * <pre>
 * {"failTimes": 2}       ilk 2 denemede geçici hata, sonra başarılı
 * {"failureRate": 0.3}   her denemede %30 ihtimalle geçici hata
 * {"permanent": true}    kalıcı hata (tekrar denenmez)
 * {"sleepMs": 5000}      önce 5 saniye bekler (uzun iş / lease senaryoları)
 * </pre>
 * Varsayılan olarak kapalı; taskforge.handlers.flaky.enabled=true ile açılır.
 */
@Component
@ConditionalOnProperty(prefix = "taskforge.handlers.flaky", name = "enabled", havingValue = "true")
public class FlakyHandler implements JobHandler {

    private final JsonMapper jsonMapper;

    public FlakyHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String jobType() {
        return "FLAKY";
    }

    @Override
    public void handle(JobContext context) throws Exception {
        JsonNode payload = jsonMapper.readTree(context.payload());

        long sleepMs = payload.path("sleepMs").asLong(0);
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        if (payload.path("permanent").asBoolean(false)) {
            throw new PermanentJobException("simulated permanent failure");
        }

        if (context.attempt() <= payload.path("failTimes").asInt(0)) {
            throw new IllegalStateException("simulated transient failure on attempt " + context.attempt());
        }

        double failureRate = payload.path("failureRate").asDouble(0);
        if (failureRate > 0 && ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new IllegalStateException("simulated random failure on attempt " + context.attempt());
        }
    }
}

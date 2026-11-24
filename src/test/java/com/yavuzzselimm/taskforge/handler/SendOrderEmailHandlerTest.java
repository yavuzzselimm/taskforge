package com.yavuzzselimm.taskforge.handler;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SendOrderEmailHandlerTest {

    private static final String PAYLOAD = "{\"orderId\":\"ORD-8421\"}";

    private final FakeEmailProvider provider = new FakeEmailProvider();
    private final SendOrderEmailHandler handler = new SendOrderEmailHandler(JsonMapper.builder().build(), provider);

    @Test
    void runningTheSameJobAgain_sendsTheEmailOnlyOnce() throws Exception {
        UUID jobId = UUID.randomUUID();

        handler.handle(new JobContext(jobId, "SEND_ORDER_EMAIL", PAYLOAD, 1));
        handler.handle(new JobContext(jobId, "SEND_ORDER_EMAIL", PAYLOAD, 2));

        String key = SendOrderEmailHandler.idempotencyKey(jobId);
        assertThat(provider.requestCount(key)).isEqualTo(2);
        assertThat(provider.deliveryCount(key)).isEqualTo(1);
    }

    @Test
    void differentJobs_eachSendTheirOwnEmail() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        handler.handle(new JobContext(first, "SEND_ORDER_EMAIL", PAYLOAD, 1));
        handler.handle(new JobContext(second, "SEND_ORDER_EMAIL", PAYLOAD, 1));

        assertThat(provider.deliveryCount(SendOrderEmailHandler.idempotencyKey(first))).isEqualTo(1);
        assertThat(provider.deliveryCount(SendOrderEmailHandler.idempotencyKey(second))).isEqualTo(1);
    }
}

package com.yavuzzselimm.taskforge.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dış e-posta servisinin yerine geçer. Gerçek sağlayıcılar gibi idempotency key kabul eder:
 * aynı anahtarla gelen ikinci istek yeniden gönderilmez.
 *
 * Gerçek sağlayıcı bu anahtarları kendi tarafında, tüm istemciler için tutar. Burada bellek içinde
 * tutuluyor, yani yalnızca aynı process içindeki tekrarları yakalar.
 */
@Component
public class FakeEmailProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeEmailProvider.class);
    private static final long SIMULATED_SEND_MILLIS = 200;

    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();

    /** E-posta gerçekten gönderildiyse true, bu anahtar daha önce görüldüyse false döner. */
    public boolean send(String idempotencyKey, String orderId) throws InterruptedException {
        AtomicInteger count = requests.computeIfAbsent(idempotencyKey, k -> new AtomicInteger());
        if (count.incrementAndGet() > 1) {
            log.info("Duplicate request {} ignored, email for order {} was already sent", idempotencyKey, orderId);
            return false;
        }
        Thread.sleep(SIMULATED_SEND_MILLIS);
        deliveries.computeIfAbsent(idempotencyKey, k -> new AtomicInteger()).incrementAndGet();
        log.info("Email for order {} sent ({})", orderId, idempotencyKey);
        return true;
    }

    public int requestCount(String idempotencyKey) {
        return countOf(requests, idempotencyKey);
    }

    public int deliveryCount(String idempotencyKey) {
        return countOf(deliveries, idempotencyKey);
    }

    private static int countOf(Map<String, AtomicInteger> map, String key) {
        AtomicInteger count = map.get(key);
        return count != null ? count.get() : 0;
    }
}

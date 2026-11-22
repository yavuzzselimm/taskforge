package com.yavuzzselimm.taskforge.worker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * delay = min(maxDelay, baseDelay * 2^(attempt-1)) + jitter
 * Jitter, hesaplanan sürenin en fazla %20'si kadar rastgele bir ek.
 */
@Component
public class RetryPolicy {

    private static final double JITTER_RATIO = 0.2;

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final LongUnaryOperator random;

    @Autowired
    public RetryPolicy(@Value("${taskforge.retry.base-delay-ms:1000}") long baseDelayMs,
                       @Value("${taskforge.retry.max-delay-ms:300000}") long maxDelayMs) {
        this(baseDelayMs, maxDelayMs, bound -> ThreadLocalRandom.current().nextLong(bound + 1));
    }

    // testlerde rastgeleliği sabitlemek için
    RetryPolicy(long baseDelayMs, long maxDelayMs, LongUnaryOperator random) {
        if (baseDelayMs < 1 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("need 1 <= base-delay-ms <= max-delay-ms");
        }
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.random = random;
    }

    /** attempt: az önce başarısız olan denemenin numarası (1'den başlar). */
    public Duration delayAfter(int attempt) {
        int exponent = Math.min(Math.max(attempt, 1) - 1, 62);
        long factor = 1L << exponent;
        // çarpmadan önce karşılaştırıyoruz, yoksa büyük attempt'lerde long taşar
        long delay = factor > maxDelayMs / baseDelayMs ? maxDelayMs : baseDelayMs * factor;

        long jitter = random.applyAsLong((long) (delay * JITTER_RATIO));
        return Duration.ofMillis(delay + jitter);
    }
}

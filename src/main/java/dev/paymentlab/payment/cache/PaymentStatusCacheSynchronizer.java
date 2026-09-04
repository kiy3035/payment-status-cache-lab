package dev.paymentlab.payment.cache;

import dev.paymentlab.payment.PaymentStatusMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(
        prefix = "payment.status-cache",
        name = "enabled",
        havingValue = "true")
public class PaymentStatusCacheSynchronizer {

    private final PaymentStatusCacheAdapter cacheAdapter;
    private final PaymentStatusMetrics metrics;

    public PaymentStatusCacheSynchronizer(
            PaymentStatusCacheAdapter cacheAdapter,
            PaymentStatusMetrics metrics) {
        this.cacheAdapter = cacheAdapter;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void synchronize(PaymentStatusChangedEvent event) {
        PaymentStatusCacheWriteResult result = cacheAdapter.write(event.value());
        metrics.recordCacheWrite(result);
    }
}

package dev.paymentlab.payment;

import dev.paymentlab.payment.cache.PaymentStatusCacheResult;
import dev.paymentlab.payment.cache.PaymentStatusCacheWriteResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class PaymentStatusMetrics {

    private final MeterRegistry meterRegistry;

    public PaymentStatusMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordApiReadTime(Timer.Sample sample) {
        sample.stop(meterRegistry.timer("payment.status.api.read"));
    }

    public void recordDatabaseReadTime(Timer.Sample sample) {
        sample.stop(meterRegistry.timer("payment.status.db.read.duration"));
    }

    public void recordCacheAccess(PaymentStatusCacheResult result) {
        meterRegistry.counter(
                "payment.status.cache.access",
                "result",
                result.metricValue()).increment();
    }

    public void recordDatabaseRead(String reason) {
        meterRegistry.counter("payment.status.db.read", "reason", reason).increment();
    }

    public void recordCacheWrite(PaymentStatusCacheWriteResult result) {
        meterRegistry.counter(
                "payment.status.cache.write",
                "result",
                result.metricValue()).increment();
    }

    public void recordTransition(PaymentStatus from, PaymentStatus to, String result) {
        meterRegistry.counter(
                "payment.status.transition",
                "from",
                from.name(),
                "to",
                to.name(),
                "result",
                result).increment();
    }
}

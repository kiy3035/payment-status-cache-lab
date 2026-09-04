package dev.paymentlab.payment;

import java.time.Instant;

import dev.paymentlab.config.PaymentStatusCacheProperties;
import dev.paymentlab.payment.api.PaymentStatusResponse;
import dev.paymentlab.payment.cache.PaymentStatusCacheAdapter;
import dev.paymentlab.payment.cache.PaymentStatusCacheLookup;
import dev.paymentlab.payment.cache.PaymentStatusCacheResult;
import dev.paymentlab.payment.cache.PaymentStatusCacheValue;
import dev.paymentlab.payment.cache.PaymentStatusCacheWriteResult;
import dev.paymentlab.payment.cache.PaymentStatusChangedEvent;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PaymentStatusService {

    private final PaymentRepository paymentRepository;
    private final PaymentStatusCacheAdapter cacheAdapter;
    private final PaymentStatusCacheProperties cacheProperties;
    private final PaymentStatusMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentStatusService(
            PaymentRepository paymentRepository,
            PaymentStatusCacheAdapter cacheAdapter,
            PaymentStatusCacheProperties cacheProperties,
            PaymentStatusMetrics metrics,
            ApplicationEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.cacheAdapter = cacheAdapter;
        this.cacheProperties = cacheProperties;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
    }

    public PaymentStatusQueryResult getStatus(long paymentId) {
        Timer.Sample apiReadTimer = metrics.startTimer();
        try {
            if (!cacheProperties.enabled()) {
                return databaseResult(paymentId, PaymentStatusCacheResult.DISABLED);
            }

            PaymentStatusCacheLookup lookup = cacheAdapter.read(paymentId);
            if (lookup.result() == PaymentStatusCacheResult.HIT) {
                metrics.recordCacheAccess(PaymentStatusCacheResult.HIT);
                return new PaymentStatusQueryResult(
                        lookup.value().toResponse(),
                        PaymentStatusCacheResult.HIT);
            }
            return databaseResult(paymentId, lookup.result());
        } finally {
            metrics.recordApiReadTime(apiReadTimer);
        }
    }

    @Transactional
    public PaymentStatusResponse changeStatus(long paymentId, PaymentStatus targetStatus) {
        Payment payment = findPayment(paymentId);
        PaymentStatus currentStatus = payment.getStatus();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int completionStatus) {
                metrics.recordTransition(
                        currentStatus,
                        targetStatus,
                        completionStatus == STATUS_COMMITTED ? "success" : "failure");
            }
        });
        payment.transitionTo(targetStatus, Instant.now());
        paymentRepository.flush();
        PaymentStatusResponse response = PaymentStatusResponse.from(payment);
        eventPublisher.publishEvent(new PaymentStatusChangedEvent(
                PaymentStatusCacheValue.from(response)));
        return response;
    }

    private PaymentStatusQueryResult databaseResult(
            long paymentId,
            PaymentStatusCacheResult cacheResult) {
        metrics.recordCacheAccess(cacheResult);
        metrics.recordDatabaseRead(cacheResult.databaseReadReason());

        Timer.Sample databaseReadTimer = metrics.startTimer();
        PaymentStatusResponse response;
        try {
            response = PaymentStatusResponse.from(findPayment(paymentId));
        } finally {
            metrics.recordDatabaseReadTime(databaseReadTimer);
        }

        if (cacheProperties.enabled()) {
            PaymentStatusCacheWriteResult writeResult = cacheAdapter.write(
                    PaymentStatusCacheValue.from(response));
            metrics.recordCacheWrite(writeResult);
        }
        return new PaymentStatusQueryResult(response, cacheResult);
    }

    private Payment findPayment(long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }
}

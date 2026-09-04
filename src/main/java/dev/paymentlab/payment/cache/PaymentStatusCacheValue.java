package dev.paymentlab.payment.cache;

import java.time.Instant;

import dev.paymentlab.payment.PaymentStatus;
import dev.paymentlab.payment.api.PaymentStatusResponse;

public record PaymentStatusCacheValue(
        long paymentId,
        PaymentStatus status,
        long version,
        Instant updatedAt) {

    public static PaymentStatusCacheValue from(PaymentStatusResponse response) {
        return new PaymentStatusCacheValue(
                response.paymentId(),
                response.status(),
                response.version(),
                response.updatedAt());
    }

    public PaymentStatusResponse toResponse() {
        return new PaymentStatusResponse(paymentId, status, version, updatedAt);
    }

    public boolean isValidFor(long expectedPaymentId) {
        return paymentId == expectedPaymentId
                && status != null
                && version >= 0
                && updatedAt != null;
    }
}

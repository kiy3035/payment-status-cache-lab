package dev.paymentlab.payment.api;

import java.time.Instant;

import dev.paymentlab.payment.Payment;
import dev.paymentlab.payment.PaymentStatus;

public record PaymentStatusResponse(
        long paymentId,
        PaymentStatus status,
        long version,
        Instant updatedAt) {

    public static PaymentStatusResponse from(Payment payment) {
        return new PaymentStatusResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getVersion(),
                payment.getUpdatedAt());
    }
}

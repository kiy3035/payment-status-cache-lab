package dev.paymentlab.payment.api;

import dev.paymentlab.payment.PaymentStatus;
import jakarta.validation.constraints.NotNull;

public record ChangePaymentStatusRequest(
        @NotNull PaymentStatus targetStatus) {
}

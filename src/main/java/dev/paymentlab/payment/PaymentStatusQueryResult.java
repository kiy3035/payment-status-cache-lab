package dev.paymentlab.payment;

import dev.paymentlab.payment.api.PaymentStatusResponse;
import dev.paymentlab.payment.cache.PaymentStatusCacheResult;

public record PaymentStatusQueryResult(
        PaymentStatusResponse response,
        PaymentStatusCacheResult cacheResult) {
}

package dev.paymentlab.common;

import java.time.Instant;

public record ApiErrorResponse(
        String code,
        String message,
        Instant timestamp) {
}

package dev.paymentlab.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.status-cache")
public record PaymentStatusCacheProperties(
        boolean enabled,
        String keyPrefix,
        Duration ttl,
        Duration commandTimeout,
        Duration connectTimeout) {
}

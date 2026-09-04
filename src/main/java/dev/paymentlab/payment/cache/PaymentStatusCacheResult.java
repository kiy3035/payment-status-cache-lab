package dev.paymentlab.payment.cache;

public enum PaymentStatusCacheResult {
    DISABLED("DISABLED", "disabled", "cache_disabled"),
    HIT("HIT", "hit", null),
    MISS_FALLBACK("MISS_FALLBACK", "miss", "miss"),
    TIMEOUT_FALLBACK("TIMEOUT_FALLBACK", "timeout", "timeout"),
    ERROR_FALLBACK("ERROR_FALLBACK", "error", "redis_error");

    private final String headerValue;
    private final String metricValue;
    private final String databaseReadReason;

    PaymentStatusCacheResult(String headerValue, String metricValue, String databaseReadReason) {
        this.headerValue = headerValue;
        this.metricValue = metricValue;
        this.databaseReadReason = databaseReadReason;
    }

    public String headerValue() {
        return headerValue;
    }

    public String metricValue() {
        return metricValue;
    }

    public String databaseReadReason() {
        if (databaseReadReason == null) {
            throw new IllegalStateException("캐시 hit에는 DB 조회 사유가 없습니다.");
        }
        return databaseReadReason;
    }
}

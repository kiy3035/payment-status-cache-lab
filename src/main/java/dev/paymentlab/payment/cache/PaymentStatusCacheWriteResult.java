package dev.paymentlab.payment.cache;

public enum PaymentStatusCacheWriteResult {
    SUCCESS("success"),
    ERROR("error");

    private final String metricValue;

    PaymentStatusCacheWriteResult(String metricValue) {
        this.metricValue = metricValue;
    }

    public String metricValue() {
        return metricValue;
    }
}

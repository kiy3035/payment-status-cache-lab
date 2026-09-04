package dev.paymentlab.payment.cache;

public record PaymentStatusCacheLookup(
        PaymentStatusCacheResult result,
        PaymentStatusCacheValue value) {

    public static PaymentStatusCacheLookup hit(PaymentStatusCacheValue value) {
        return new PaymentStatusCacheLookup(PaymentStatusCacheResult.HIT, value);
    }

    public static PaymentStatusCacheLookup miss() {
        return new PaymentStatusCacheLookup(PaymentStatusCacheResult.MISS_FALLBACK, null);
    }

    public static PaymentStatusCacheLookup timeout() {
        return new PaymentStatusCacheLookup(PaymentStatusCacheResult.TIMEOUT_FALLBACK, null);
    }

    public static PaymentStatusCacheLookup error() {
        return new PaymentStatusCacheLookup(PaymentStatusCacheResult.ERROR_FALLBACK, null);
    }
}

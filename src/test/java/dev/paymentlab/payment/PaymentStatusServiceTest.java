package dev.paymentlab.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import dev.paymentlab.config.PaymentStatusCacheProperties;
import dev.paymentlab.payment.api.PaymentStatusResponse;
import dev.paymentlab.payment.cache.PaymentStatusCacheAdapter;
import dev.paymentlab.payment.cache.PaymentStatusCacheLookup;
import dev.paymentlab.payment.cache.PaymentStatusCacheResult;
import dev.paymentlab.payment.cache.PaymentStatusCacheValue;
import dev.paymentlab.payment.cache.PaymentStatusCacheWriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PaymentStatusServiceTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    PaymentStatusCacheAdapter cacheAdapter;

    @Mock
    ApplicationEventPublisher eventPublisher;

    SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void 캐시비활성화시Redis에접근하지않고Db를조회한다() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(1L)));

        PaymentStatusQueryResult result = service(false).getStatus(1L);

        assertThat(result.cacheResult()).isEqualTo(PaymentStatusCacheResult.DISABLED);
        assertThat(result.response().status()).isEqualTo(PaymentStatus.READY);
        verifyNoInteractions(cacheAdapter);
        assertThat(counter("payment.status.cache.access", "result", "disabled")).isEqualTo(1.0);
        assertThat(counter("payment.status.db.read", "reason", "cache_disabled")).isEqualTo(1.0);
    }

    @Test
    void 캐시Hit이면Db를조회하지않는다() {
        PaymentStatusCacheValue cached = cacheValue(2L, PaymentStatus.AUTH);
        when(cacheAdapter.read(2L)).thenReturn(PaymentStatusCacheLookup.hit(cached));

        PaymentStatusQueryResult result = service(true).getStatus(2L);

        assertThat(result.cacheResult()).isEqualTo(PaymentStatusCacheResult.HIT);
        assertThat(result.response()).isEqualTo(cached.toResponse());
        verifyNoInteractions(paymentRepository);
        verify(cacheAdapter, never()).write(any());
        assertThat(counter("payment.status.cache.access", "result", "hit")).isEqualTo(1.0);
    }

    @Test
    void RedisTimeout이면DbFallback후캐시저장을시도한다() {
        when(cacheAdapter.read(3L)).thenReturn(PaymentStatusCacheLookup.timeout());
        when(paymentRepository.findById(3L)).thenReturn(Optional.of(payment(3L)));
        when(cacheAdapter.write(any())).thenReturn(PaymentStatusCacheWriteResult.SUCCESS);

        PaymentStatusQueryResult result = service(true).getStatus(3L);

        assertThat(result.cacheResult()).isEqualTo(PaymentStatusCacheResult.TIMEOUT_FALLBACK);
        verify(paymentRepository).findById(3L);
        verify(cacheAdapter).write(any(PaymentStatusCacheValue.class));
        assertThat(counter("payment.status.cache.access", "result", "timeout")).isEqualTo(1.0);
        assertThat(counter("payment.status.db.read", "reason", "timeout")).isEqualTo(1.0);
    }

    @Test
    void Redis오류이면DbFallback후캐시저장을시도한다() {
        when(cacheAdapter.read(4L)).thenReturn(PaymentStatusCacheLookup.error());
        when(paymentRepository.findById(4L)).thenReturn(Optional.of(payment(4L)));
        when(cacheAdapter.write(any())).thenReturn(PaymentStatusCacheWriteResult.ERROR);

        PaymentStatusQueryResult result = service(true).getStatus(4L);

        assertThat(result.cacheResult()).isEqualTo(PaymentStatusCacheResult.ERROR_FALLBACK);
        verify(paymentRepository).findById(4L);
        verify(cacheAdapter).write(any(PaymentStatusCacheValue.class));
        assertThat(counter("payment.status.cache.access", "result", "error")).isEqualTo(1.0);
        assertThat(counter("payment.status.db.read", "reason", "redis_error")).isEqualTo(1.0);
        assertThat(counter("payment.status.cache.write", "result", "error")).isEqualTo(1.0);
    }

    private PaymentStatusService service(boolean cacheEnabled) {
        PaymentStatusCacheProperties properties = new PaymentStatusCacheProperties(
                cacheEnabled,
                "payment:status:",
                Duration.ofMinutes(5),
                Duration.ofMillis(100),
                Duration.ofMillis(100));
        return new PaymentStatusService(
                paymentRepository,
                cacheAdapter,
                properties,
                new PaymentStatusMetrics(meterRegistry),
                eventPublisher);
    }

    private Payment payment(long paymentId) {
        return new Payment(paymentId, PaymentStatus.READY, UPDATED_AT, UPDATED_AT);
    }

    private PaymentStatusCacheValue cacheValue(long paymentId, PaymentStatus status) {
        return PaymentStatusCacheValue.from(new PaymentStatusResponse(
                paymentId,
                status,
                0L,
                UPDATED_AT));
    }

    private double counter(String name, String tagName, String tagValue) {
        return meterRegistry.get(name).tag(tagName, tagValue).counter().count();
    }
}

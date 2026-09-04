package dev.paymentlab.payment.cache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import dev.paymentlab.payment.PaymentStatus;
import dev.paymentlab.payment.PaymentStatusMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentStatusCacheSynchronizerTest {

    @Mock
    PaymentStatusCacheAdapter cacheAdapter;

    @Mock
    PaymentStatusMetrics metrics;

    @InjectMocks
    PaymentStatusCacheSynchronizer synchronizer;

    @Test
    void commit후Redis쓰기실패가호출자예외로전파되지않는다() {
        PaymentStatusCacheValue value = new PaymentStatusCacheValue(
                1L,
                PaymentStatus.AUTH,
                1L,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(cacheAdapter.write(value)).thenReturn(PaymentStatusCacheWriteResult.ERROR);

        assertThatCode(() -> synchronizer.synchronize(new PaymentStatusChangedEvent(value)))
                .doesNotThrowAnyException();

        verify(metrics).recordCacheWrite(PaymentStatusCacheWriteResult.ERROR);
    }
}

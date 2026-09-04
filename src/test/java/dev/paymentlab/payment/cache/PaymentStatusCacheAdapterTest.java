package dev.paymentlab.payment.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paymentlab.config.PaymentStatusCacheProperties;
import dev.paymentlab.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PaymentStatusCacheAdapterTest {

    @Mock
    StringRedisTemplate redisTemplate;

    @Mock
    ValueOperations<String, String> valueOperations;

    PaymentStatusCacheAdapter cacheAdapter;

    @BeforeEach
    void setUp() {
        PaymentStatusCacheProperties properties = new PaymentStatusCacheProperties(
                true,
                "payment:status:",
                Duration.ofMinutes(5),
                Duration.ofMillis(100),
                Duration.ofMillis(100));
        cacheAdapter = new PaymentStatusCacheAdapter(
                redisTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void 값이없으면Miss로구분한다() {
        when(valueOperations.get("payment:status:1")).thenReturn(null);

        assertThat(cacheAdapter.read(1L).result())
                .isEqualTo(PaymentStatusCacheResult.MISS_FALLBACK);
    }

    @Test
    void Json값을명시적으로역직렬화해Hit로반환한다() throws Exception {
        PaymentStatusCacheValue value = value();
        when(valueOperations.get("payment:status:1"))
                .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(value));

        PaymentStatusCacheLookup lookup = cacheAdapter.read(1L);

        assertThat(lookup.result()).isEqualTo(PaymentStatusCacheResult.HIT);
        assertThat(lookup.value()).isEqualTo(value);
    }

    @Test
    void SpringQueryTimeout을Timeout으로구분한다() {
        when(valueOperations.get("payment:status:1"))
                .thenThrow(new QueryTimeoutException("Redis 명령 timeout"));

        assertThat(cacheAdapter.read(1L).result())
                .isEqualTo(PaymentStatusCacheResult.TIMEOUT_FALLBACK);
    }

    @Test
    void Redis연결실패를Error로구분한다() {
        when(valueOperations.get("payment:status:1"))
                .thenThrow(new RedisConnectionFailureException("Redis 연결 실패"));

        assertThat(cacheAdapter.read(1L).result())
                .isEqualTo(PaymentStatusCacheResult.ERROR_FALLBACK);
    }

    @Test
    void 잘못된Json을Error로구분한다() {
        when(valueOperations.get("payment:status:1")).thenReturn("not-json");

        assertThat(cacheAdapter.read(1L).result())
                .isEqualTo(PaymentStatusCacheResult.ERROR_FALLBACK);
    }

    @Test
    void JsonNull값은Error로구분한다() {
        when(valueOperations.get("payment:status:1")).thenReturn("null");

        assertThat(cacheAdapter.read(1L).result())
                .isEqualTo(PaymentStatusCacheResult.ERROR_FALLBACK);
    }

    @Test
    void 다른결제의Json값은Error로구분한다() throws Exception {
        when(valueOperations.get("payment:status:2"))
                .thenReturn(new ObjectMapper().findAndRegisterModules().writeValueAsString(value()));

        assertThat(cacheAdapter.read(2L).result())
                .isEqualTo(PaymentStatusCacheResult.ERROR_FALLBACK);
    }

    @Test
    void Redis쓰기실패를Error결과로변환한다() {
        doThrow(new RedisConnectionFailureException("Redis 연결 실패"))
                .when(valueOperations)
                .set(anyString(), anyString(), any(Duration.class));

        assertThat(cacheAdapter.write(value())).isEqualTo(PaymentStatusCacheWriteResult.ERROR);
    }

    private PaymentStatusCacheValue value() {
        return new PaymentStatusCacheValue(
                1L,
                PaymentStatus.READY,
                0L,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}

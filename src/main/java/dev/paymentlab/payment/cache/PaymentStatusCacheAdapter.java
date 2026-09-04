package dev.paymentlab.payment.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paymentlab.config.PaymentStatusCacheProperties;
import io.lettuce.core.RedisCommandTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentStatusCacheAdapter {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusCacheAdapter.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PaymentStatusCacheProperties properties;

    public PaymentStatusCacheAdapter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            PaymentStatusCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public PaymentStatusCacheLookup read(long paymentId) {
        try {
            String serializedValue = redisTemplate.opsForValue().get(key(paymentId));
            if (serializedValue == null) {
                return PaymentStatusCacheLookup.miss();
            }

            PaymentStatusCacheValue value = objectMapper.readValue(
                    serializedValue,
                    PaymentStatusCacheValue.class);
            if (value == null || !value.isValidFor(paymentId)) {
                log.warn("Redis 캐시 값이 요청과 일치하지 않아 DB fallback을 사용합니다.");
                return PaymentStatusCacheLookup.error();
            }
            return PaymentStatusCacheLookup.hit(value);
        } catch (QueryTimeoutException exception) {
            log.warn("Redis 캐시 읽기 timeout으로 DB fallback을 사용합니다.");
            return PaymentStatusCacheLookup.timeout();
        } catch (DataAccessException exception) {
            if (hasCommandTimeoutCause(exception)) {
                log.warn("Redis 캐시 읽기 timeout으로 DB fallback을 사용합니다.");
                return PaymentStatusCacheLookup.timeout();
            }
            log.warn(
                    "Redis 캐시 읽기에 실패해 DB fallback을 사용합니다. type={}",
                    exception.getClass().getSimpleName());
            return PaymentStatusCacheLookup.error();
        } catch (JsonProcessingException exception) {
            log.warn("Redis 캐시 값을 해석할 수 없어 DB fallback을 사용합니다.");
            return PaymentStatusCacheLookup.error();
        }
    }

    public PaymentStatusCacheWriteResult write(PaymentStatusCacheValue value) {
        try {
            String serializedValue = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(
                    key(value.paymentId()),
                    serializedValue,
                    properties.ttl());
            return PaymentStatusCacheWriteResult.SUCCESS;
        } catch (JsonProcessingException | DataAccessException exception) {
            log.warn(
                    "Redis 캐시 쓰기에 실패했습니다. type={}",
                    exception.getClass().getSimpleName());
            return PaymentStatusCacheWriteResult.ERROR;
        }
    }

    public String key(long paymentId) {
        return properties.keyPrefix() + paymentId;
    }

    private boolean hasCommandTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RedisCommandTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

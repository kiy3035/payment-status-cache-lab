package dev.paymentlab.payment.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paymentlab.config.PaymentStatusCacheProperties;
import dev.paymentlab.payment.Payment;
import dev.paymentlab.payment.PaymentRepository;
import dev.paymentlab.payment.PaymentStatus;
import dev.paymentlab.payment.PaymentStatusService;
import dev.paymentlab.payment.api.PaymentStatusResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentStatusCacheIntegrationTest {

    private static final String KEY_PREFIX = "stage3:payment:status:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(2);
    private static final String MYSQL_PASSWORD = "test-" + UUID.randomUUID();

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.6"))
            .withDatabaseName("payment_lab")
            .withUsername("payment_app")
            .withPassword(MYSQL_PASSWORD);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.4.5-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("payment.status-cache.enabled", () -> true);
        registry.add("payment.status-cache.key-prefix", () -> KEY_PREFIX);
        registry.add("payment.status-cache.ttl", () -> CACHE_TTL);
        registry.add("payment.status-cache.command-timeout", () -> "100ms");
        registry.add("payment.status-cache.connect-timeout", () -> "2s");
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoSpyBean
    PaymentRepository paymentRepository;

    @Autowired
    PaymentStatusCacheAdapter cacheAdapter;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @Autowired
    PaymentStatusCacheProperties properties;

    @Autowired
    PaymentStatusService paymentStatusService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearCacheAndRepositoryInvocations() {
        redisTemplate.delete(testKeys());
        clearInvocations(paymentRepository);
    }

    @AfterEach
    void clearCache() {
        redisTemplate.delete(testKeys());
    }

    @Test
    void cacheMiss후Db결과를TtlJson으로저장하고다음조회는Hit한다() throws Exception {
        double missBefore = counter("payment.status.cache.access", "result", "miss");
        double hitBefore = counter("payment.status.cache.access", "result", "hit");
        double databaseBefore = counter("payment.status.db.read", "reason", "miss");

        mockMvc.perform(get("/api/v1/payments/{paymentId}/status", 13L))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Result", "MISS_FALLBACK"))
                .andExpect(jsonPath("$.status").value("READY"));

        verify(paymentRepository).findById(13L);
        String cachedJson = redisTemplate.opsForValue().get(cacheAdapter.key(13L));
        JsonNode cachedValue = objectMapper.readTree(cachedJson);
        assertThat(cachedValue.path("paymentId").asLong()).isEqualTo(13L);
        assertThat(cachedValue.path("status").asText()).isEqualTo("READY");
        assertThat(cachedValue.path("version").asLong()).isZero();
        Long ttlSeconds = redisTemplate.getExpire(cacheAdapter.key(13L), TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(CACHE_TTL.toSeconds());
        assertThat(counter("payment.status.cache.access", "result", "miss"))
                .isEqualTo(missBefore + 1.0);
        assertThat(counter("payment.status.db.read", "reason", "miss"))
                .isEqualTo(databaseBefore + 1.0);

        clearInvocations(paymentRepository);
        mockMvc.perform(get("/api/v1/payments/{paymentId}/status", 13L))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Result", "HIT"))
                .andExpect(jsonPath("$.status").value("READY"));

        verify(paymentRepository, never()).findById(13L);
        verifyNoMoreInteractions(paymentRepository);
        assertThat(counter("payment.status.cache.access", "result", "hit"))
                .isEqualTo(hitBefore + 1.0);
    }

    @Test
    void DbCommit이후Redis를최신상태로갱신한다() throws Exception {
        double transitionBefore = counter(
                "payment.status.transition",
                "result",
                "success",
                "from",
                "READY",
                "to",
                "AUTH");

        mockMvc.perform(patch("/api/v1/payments/{paymentId}/status", 16L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"AUTH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTH"))
                .andExpect(jsonPath("$.version").value(1));

        PaymentStatusCacheValue cached = objectMapper.readValue(
                redisTemplate.opsForValue().get(cacheAdapter.key(16L)),
                PaymentStatusCacheValue.class);
        assertThat(cached.status()).isEqualTo(PaymentStatus.AUTH);
        assertThat(cached.version()).isEqualTo(1L);

        clearInvocations(paymentRepository);
        mockMvc.perform(get("/api/v1/payments/{paymentId}/status", 16L))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Result", "HIT"))
                .andExpect(jsonPath("$.status").value("AUTH"));
        verify(paymentRepository, never()).findById(16L);
        assertThat(counter(
                "payment.status.transition",
                "result",
                "success",
                "from",
                "READY",
                "to",
                "AUTH")).isEqualTo(transitionBefore + 1.0);
    }

    @Test
    void DbRollback이면Redis값을변경하지않는다() throws Exception {
        Payment original = paymentRepository.findById(19L).orElseThrow();
        PaymentStatusCacheValue originalValue = PaymentStatusCacheValue.from(
                PaymentStatusResponse.from(original));
        assertThat(cacheAdapter.write(originalValue)).isEqualTo(PaymentStatusCacheWriteResult.SUCCESS);
        String cachedBefore = redisTemplate.opsForValue().get(cacheAdapter.key(19L));
        double failedTransitionBefore = counter(
                "payment.status.transition",
                "result",
                "failure",
                "from",
                "READY",
                "to",
                "APPROVED");

        mockMvc.perform(patch("/api/v1/payments/{paymentId}/status", 19L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS_TRANSITION"));

        assertThat(redisTemplate.opsForValue().get(cacheAdapter.key(19L))).isEqualTo(cachedBefore);
        Payment unchanged = paymentRepository.findById(19L).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(unchanged.getVersion()).isZero();
        assertThat(counter(
                "payment.status.transition",
                "result",
                "failure",
                "from",
                "READY",
                "to",
                "APPROVED")).isEqualTo(failedTransitionBefore + 1.0);
    }

    @Test
    void 존재하지않는결제는NegativeCaching하지않는다() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{paymentId}/status", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(redisTemplate.hasKey(cacheAdapter.key(999_999L))).isFalse();
    }

    @Test
    void LettuceCommandTimeout과캐시설정을적용한다() {
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout())
                .isEqualTo(Duration.ofMillis(100));
        assertThat(properties.commandTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.keyPrefix()).isEqualTo(KEY_PREFIX);
        assertThat(properties.ttl()).isEqualTo(CACHE_TTL);
    }

    @Test
    void 외부TransactionCommit전에는캐시와성공지표를갱신하지않는다() throws Exception {
        paymentStatusService.getStatus(22L);
        String cachedBefore = redisTemplate.opsForValue().get(cacheAdapter.key(22L));
        double successBefore = counter(
                "payment.status.transition", "from", "READY", "to", "AUTH", "result", "success");

        transactionTemplate.executeWithoutResult(transaction -> {
            PaymentStatusResponse response = paymentStatusService.changeStatus(22L, PaymentStatus.AUTH);
            assertThat(response.version()).isEqualTo(1L);
            assertThat(redisTemplate.opsForValue().get(cacheAdapter.key(22L))).isEqualTo(cachedBefore);
            assertThat(counter(
                    "payment.status.transition", "from", "READY", "to", "AUTH", "result", "success"))
                    .isEqualTo(successBefore);
        });

        PaymentStatusCacheValue cachedAfter = objectMapper.readValue(
                redisTemplate.opsForValue().get(cacheAdapter.key(22L)), PaymentStatusCacheValue.class);
        assertThat(cachedAfter.status()).isEqualTo(PaymentStatus.AUTH);
        assertThat(cachedAfter.version()).isEqualTo(1L);
        assertThat(counter(
                "payment.status.transition", "from", "READY", "to", "AUTH", "result", "success"))
                .isEqualTo(successBefore + 1.0);
    }

    @Test
    void 유효한상태변경후외부TransactionRollback이면캐시와Db를보존한다() {
        paymentStatusService.getStatus(25L);
        String cachedBefore = redisTemplate.opsForValue().get(cacheAdapter.key(25L));
        double successBefore = counter(
                "payment.status.transition", "from", "READY", "to", "AUTH", "result", "success");
        double failureBefore = counter(
                "payment.status.transition", "from", "READY", "to", "AUTH", "result", "failure");

        transactionTemplate.executeWithoutResult(transaction -> {
            paymentStatusService.changeStatus(25L, PaymentStatus.AUTH);
            assertThat(redisTemplate.opsForValue().get(cacheAdapter.key(25L))).isEqualTo(cachedBefore);
            transaction.setRollbackOnly();
        });

        assertThat(redisTemplate.opsForValue().get(cacheAdapter.key(25L))).isEqualTo(cachedBefore);
        Payment unchanged = paymentRepository.findById(25L).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(unchanged.getVersion()).isZero();
        assertThat(counter(
                "payment.status.transition", "from", "READY", "to", "AUTH", "result", "success"))
                .isEqualTo(successBefore);
        assertThat(counter(
                "payment.status.transition", "from", "READY", "to", "AUTH", "result", "failure"))
                .isEqualTo(failureBefore + 1.0);
    }

    private List<String> testKeys() {
        return List.of(
                cacheAdapter.key(13L),
                cacheAdapter.key(16L),
                cacheAdapter.key(19L),
                cacheAdapter.key(22L),
                cacheAdapter.key(25L),
                cacheAdapter.key(999_999L));
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}

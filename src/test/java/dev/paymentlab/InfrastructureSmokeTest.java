package dev.paymentlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import dev.paymentlab.config.PaymentStatusCacheProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class InfrastructureSmokeTest {

    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4.6");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4.5-alpine");
    private static final String MYSQL_PASSWORD = "test-" + UUID.randomUUID();

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("payment_lab")
            .withUsername("payment_app")
            .withPassword(MYSQL_PASSWORD);

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.timeout", () -> "2s");
        registry.add("spring.data.redis.connect-timeout", () -> "2s");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    PaymentStatusCacheProperties cacheProperties;

    @Test
    void springContextStartsAndCachePropertiesBind() {
        assertThat(cacheProperties.enabled()).isFalse();
        assertThat(cacheProperties.ttl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(cacheProperties.commandTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(cacheProperties.connectTimeout()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void flywayCreatesAndSeedsOneHundredThousandDeterministicPayments() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long.class);
        Long minimumId = jdbcTemplate.queryForObject("SELECT MIN(id) FROM payments", Long.class);
        Long maximumId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM payments", Long.class);
        String firstStatus = jdbcTemplate.queryForObject("SELECT status FROM payments WHERE id = 1", String.class);

        assertThat(count).isEqualTo(100_000L);
        assertThat(minimumId).isEqualTo(1L);
        assertThat(maximumId).isEqualTo(100_000L);
        assertThat(firstStatus).isEqualTo("READY");
    }

    @Test
    void mysqlContainerRunsExpectedMajorMinorVersion() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);

        assertThat(version).startsWith("8.4.");
    }

    @Test
    void redisContainerSupportsRealRoundTrip() {
        String key = "stage1:smoke-test";
        redisTemplate.opsForValue().set(key, "connected", Duration.ofSeconds(10));

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("connected");
        assertThat(redisTemplate.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");

        redisTemplate.delete(key);
    }
}

package dev.paymentlab.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentApiIntegrationTest {

    private static final String MYSQL_PASSWORD = "test-" + UUID.randomUUID();

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4.6"))
            .withDatabaseName("payment_lab")
            .withUsername("payment_app")
            .withPassword(MYSQL_PASSWORD);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("payment.status-cache.enabled", () -> false);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PaymentRepository paymentRepository;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    void 결제상태를Db에서조회하고Disabled헤더를반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{paymentId}/status", 1L))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Result", "DISABLED"))
                .andExpect(jsonPath("$.paymentId").value(1))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void 유효한상태변경을Db에반영한다() throws Exception {
        mockMvc.perform(patch("/api/v1/payments/{paymentId}/status", 4L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"AUTH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(4))
                .andExpect(jsonPath("$.status").value("AUTH"))
                .andExpect(jsonPath("$.version").value(1));

        Payment changed = paymentRepository.findById(4L).orElseThrow();
        assertThat(changed.getStatus()).isEqualTo(PaymentStatus.AUTH);
        assertThat(changed.getVersion()).isEqualTo(1L);
    }

    @Test
    void 잘못된상태변경은409를반환하고Db를변경하지않는다() throws Exception {
        mockMvc.perform(patch("/api/v1/payments/{paymentId}/status", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"APPROVED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATUS_TRANSITION"));

        Payment unchanged = paymentRepository.findById(7L).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PaymentStatus.READY);
        assertThat(unchanged.getVersion()).isZero();
    }

    @Test
    void 존재하지않는결제는404를반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/payments/{paymentId}/status", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void 정의되지않은상태는400을반환한다() throws Exception {
        mockMvc.perform(patch("/api/v1/payments/{paymentId}/status", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CANCELLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void 실제Mysql에서낙관적잠금충돌을감지한다() {
        Payment first = transactionTemplate.execute(
                status -> paymentRepository.findById(10L).orElseThrow());
        Payment stale = transactionTemplate.execute(
                status -> paymentRepository.findById(10L).orElseThrow());

        transactionTemplate.executeWithoutResult(status -> {
            first.transitionTo(PaymentStatus.AUTH, Instant.now());
            paymentRepository.saveAndFlush(first);
        });

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            stale.transitionTo(PaymentStatus.AUTH, Instant.now());
            paymentRepository.saveAndFlush(stale);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}

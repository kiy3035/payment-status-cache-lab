package dev.paymentlab.payment.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.paymentlab.payment.Payment;
import dev.paymentlab.payment.PaymentRepository;
import dev.paymentlab.payment.PaymentStatus;
import dev.paymentlab.payment.PaymentStatusService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.lettuce.core.ClientOptions;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentStatusFailureIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    private static final String PREFIX = "stage4:payment:status:";

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.6")
            .withDatabaseName("payment_lab").withUsername("payment_app")
            .withPassword("test-" + UUID.randomUUID())
            .withNetwork(NETWORK).withNetworkAliases("mysql");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.5-alpine")
            .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> toxiproxy = new GenericContainer<>("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(NETWORK).withExposedPorts(8474, 26379, 23306)
            .dependsOn(mysql, redis)
            .waitingFor(Wait.forHttp("/version").forPort(8474)
                    .withHeader("User-Agent", "toxiproxy-cli/2.12.0"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        proxy("POST", "/proxies", Map.of(
                "name", "redis", "listen", "0.0.0.0:26379", "upstream", "redis:6379"));
        proxy("POST", "/proxies", Map.of(
                "name", "mysql", "listen", "0.0.0.0:23306", "upstream", "mysql:3306"));
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + toxiproxy.getHost() + ":"
                + toxiproxy.getMappedPort(23306)
                + "/payment_lab?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=1000&socketTimeout=10000");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "700");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "500");
        registry.add("spring.data.redis.host", toxiproxy::getHost);
        registry.add("spring.data.redis.port", () -> toxiproxy.getMappedPort(26379));
        registry.add("payment.status-cache.enabled", () -> true);
        registry.add("payment.status-cache.key-prefix", () -> PREFIX);
        registry.add("payment.status-cache.command-timeout", () -> "100ms");
        registry.add("payment.status-cache.connect-timeout", () -> "100ms");
    }

    @LocalServerPort
    int port;

    @Autowired
    PaymentStatusCacheAdapter adapter;

    @Autowired
    LettuceConnectionFactory connectionFactory;

    @Autowired
    MeterRegistry metrics;

    @Autowired
    PaymentRepository repository;

    @Autowired
    EntityManager entityManager;

    @MockitoSpyBean
    PaymentStatusService service;

    @BeforeEach
    void 정상연결을준비한다() throws Exception {
        restoreDependencies();
    }

    @AfterEach
    void 장애설정을복원한다() throws Exception {
        restoreDependencies();
    }

    @AfterAll
    static void 네트워크를정리한다() {
        toxiproxy.stop();
        redis.stop();
        mysql.stop();
        NETWORK.close();
    }

    @Test
    void 실제300ms지연을100msCommandTimeout으로제한하고Db로Fallback한다() throws Exception {
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout())
                .isEqualTo(Duration.ofMillis(100));
        double timeoutBefore = counter("payment.status.cache.access", "result", "timeout");
        double writeErrorBefore = counter("payment.status.cache.write", "result", "error");
        proxy("POST", "/proxies/redis/toxics", Map.of(
                "name", "read-latency", "type", "latency", "stream", "downstream",
                "attributes", Map.of("latency", 300, "jitter", 0)));
        long started = System.nanoTime();
        HttpResponse<String> response = getStatus(100);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertResponse(response, 200, "TIMEOUT_FALLBACK", "READY");
        assertThat(elapsed).isBetween(80L, 900L);
        assertThat(counter("payment.status.cache.access", "result", "timeout")).isEqualTo(timeoutBefore + 1);
        assertThat(counter("payment.status.cache.write", "result", "error")).isEqualTo(writeErrorBefore + 1);
        System.out.println("실제 지연 300ms / command timeout 100ms / HTTP 소요 " + elapsed + "ms");
    }

    @Test
    void 신규Redis연결거부시ErrorFallback하고재저장실패도정상응답한다() throws Exception {
        proxy("POST", "/proxies/redis", Map.of("enabled", false));
        connectionFactory.resetConnection();
        double errorBefore = counter("payment.status.cache.write", "result", "error");
        assertResponse(getStatus(103), 200, "ERROR_FALLBACK", "READY");
        assertThat(counter("payment.status.cache.write", "result", "error")).isEqualTo(errorBefore + 1);
        assertLiveness();
    }

    @Test
    void 실제Redis읽기권한오류에도Db응답과캐시재저장이성공한다() throws Exception {
        redisCommand("ACL", "SETUSER", "default", "-get");
        double successBefore = counter("payment.status.cache.write", "result", "success");
        assertResponse(getStatus(106), 200, "ERROR_FALLBACK", "READY");
        assertThat(counter("payment.status.cache.write", "result", "success")).isEqualTo(successBefore + 1);
        redisCommand("ACL", "SETUSER", "default", "+get");
        assertResponse(getStatus(106), 200, "HIT", "READY");
    }

    @Test
    void 실제Redis쓰기거부시Miss응답은성공하고캐시를만들지않는다() throws Exception {
        redisCommand("ACL", "SETUSER", "default", "-set");
        double errorBefore = counter("payment.status.cache.write", "result", "error");
        assertResponse(getStatus(109), 200, "MISS_FALLBACK", "READY");
        assertThat(redisCommand("EXISTS", PREFIX + 109)).isEqualTo("0");
        assertThat(counter("payment.status.cache.write", "result", "error")).isEqualTo(errorBefore + 1);
    }

    @Test
    void Commit후쓰기거부에도변경Api는성공하며남은Stale값을확인한다() throws Exception {
        assertResponse(getStatus(112), 200, "MISS_FALLBACK", "READY");
        redisCommand("ACL", "SETUSER", "default", "-set");
        double errorBefore = counter("payment.status.cache.write", "result", "error");
        HttpResponse<String> changed = patchStatus(112);
        assertResponse(changed, 200, null, "AUTH");
        assertThat(JSON.readTree(changed.body()).path("version").asLong()).isEqualTo(1);
        assertThat(repository.findById(112L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTH);
        assertResponse(getStatus(112), 200, "HIT", "READY");
        assertThat(counter("payment.status.cache.write", "result", "error")).isEqualTo(errorBefore + 1);
        assertThat(Long.parseLong(redisCommand("TTL", PREFIX + 112))).isPositive().isLessThanOrEqualTo(300);
        redisCommand("ACL", "SETUSER", "default", "+set");
        redisCommand("DEL", PREFIX + 112);
        assertResponse(getStatus(112), 200, "MISS_FALLBACK", "AUTH");
        assertResponse(getStatus(112), 200, "HIT", "AUTH");
    }

    @Test
    void Redis실제중단중조회변경과Liveness를유지하고재시작후복구한다() throws Exception {
        String redisId = redis.getContainerId();
        long processId = ProcessHandle.current().pid();
        redis.getDockerClient().stopContainerCmd(redisId).withTimeout(1).exec();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                !redis.getDockerClient().inspectContainerCmd(redisId).exec().getState().getRunning());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                adapter.read(999_999L).result() == PaymentStatusCacheResult.ERROR_FALLBACK);
        HttpResponse<String> duringStop = getStatus(115);
        assertResponse(duringStop, 200, "ERROR_FALLBACK", "READY");
        assertResponse(patchStatus(115), 200, null, "AUTH");
        assertThat(repository.findById(115L).orElseThrow().getStatus()).isEqualTo(PaymentStatus.AUTH);
        assertLiveness();
        assertThat(request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health", null).statusCode()).isEqualTo(503);
        redis.getDockerClient().startContainerCmd(redisId).exec();
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() ->
                adapter.read(999_999L).result() == PaymentStatusCacheResult.MISS_FALLBACK);
        assertThat(redis.getContainerId()).isEqualTo(redisId);
        assertThat(ProcessHandle.current().pid()).isEqualTo(processId);
        assertResponse(getStatus(115), 200, "MISS_FALLBACK", "AUTH");
        assertResponse(getStatus(115), 200, "HIT", "AUTH");
        assertLiveness();
    }

    @Test
    void Redis와Db연결이동시에실패하면503이고Liveness는정상이다() throws Exception {
        proxy("POST", "/proxies/redis", Map.of("enabled", false));
        proxy("POST", "/proxies/mysql", Map.of("enabled", false));
        HttpResponse<String> unavailable = getStatus(118);
        assertThat(unavailable.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(unavailable.body()).path("code").asText())
                .isEqualTo("PAYMENT_STATUS_UNAVAILABLE");
        HttpResponse<String> unavailableChange = patchStatus(118);
        assertThat(unavailableChange.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(unavailableChange.body()).path("code").asText())
                .isEqualTo("PAYMENT_STATUS_UNAVAILABLE");
        assertLiveness();
        assertThat(request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(503);
    }

    @Test
    void Db만실패해도RedisHit이면정상응답한다() throws Exception {
        assertResponse(getStatus(124), 200, "MISS_FALLBACK", "READY");
        proxy("POST", "/proxies/mysql", Map.of("enabled", false));
        double readsBefore = counter("payment.status.db.read", "reason", "miss");
        assertResponse(getStatus(124), 200, "HIT", "READY");
        assertThat(counter("payment.status.db.read", "reason", "miss")).isEqualTo(readsBefore);
        assertLiveness();
    }

    @Test
    void 끊긴연결에는명령을거부하지만자동재연결과Timeout설정은보존한다() {
        ClientOptions options = connectionFactory.getClientConfiguration().getClientOptions().orElseThrow();
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        assertThat(options.isAutoReconnect()).isTrue();
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(connectionFactory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(100));
    }

    @Test
    void 두Http상태변경이동일Version을읽으면한건만Commit하고다른한건은409다() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        doAnswer(invocation -> {
            // 두 실제 HTTP transaction이 같은 version을 보유하도록 테스트에서만 읽기를 동기화한다.
            assertThat(entityManager.isJoinedToTransaction()).isTrue();
            entityManager.find(Payment.class, 121L);
            bothRead.await(5, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(service).changeStatus(eq(121L), eq(PaymentStatus.AUTH));
        double writesBefore = counter("payment.status.cache.write", "result", "success");
        double successBefore = counter("payment.status.transition", "from", "READY", "to", "AUTH", "result", "success");
        double failureBefore = counter("payment.status.transition", "from", "READY", "to", "AUTH", "result", "failure");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> patchStatus(121));
            var second = executor.submit(() -> patchStatus(121));
            HttpResponse<String> a = first.get(10, TimeUnit.SECONDS);
            HttpResponse<String> b = second.get(10, TimeUnit.SECONDS);
            assertThat(new int[] {a.statusCode(), b.statusCode()}).containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> conflict = a.statusCode() == 409 ? a : b;
            assertThat(JSON.readTree(conflict.body()).path("code").asText()).isEqualTo("PAYMENT_STATUS_CONFLICT");
        } finally {
            doCallRealMethod().when(service).changeStatus(eq(121L), eq(PaymentStatus.AUTH));
        }
        assertThat(repository.findById(121L).orElseThrow().getVersion()).isEqualTo(1);
        assertResponse(getStatus(121), 200, "HIT", "AUTH");
        assertThat(counter("payment.status.cache.write", "result", "success")).isEqualTo(writesBefore + 1);
        assertThat(counter("payment.status.transition", "from", "READY", "to", "AUTH", "result", "success")).isEqualTo(successBefore + 1);
        assertThat(counter("payment.status.transition", "from", "READY", "to", "AUTH", "result", "failure")).isEqualTo(failureBefore + 1);
    }

    private void restoreDependencies() throws Exception {
        if (!redis.getDockerClient().inspectContainerCmd(redis.getContainerId()).exec().getState().getRunning()) {
            redis.getDockerClient().startContainerCmd(redis.getContainerId()).exec();
        }
        proxy("POST", "/reset", null);
        proxy("POST", "/proxies/redis", Map.of("enabled", true));
        proxy("POST", "/proxies/mysql", Map.of("enabled", true));
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(() ->
                assertThat(redisCommand("ACL", "SETUSER", "default", "+get", "+set")).isEqualTo("OK"));
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() ->
                adapter.read(999_999L).result() == PaymentStatusCacheResult.MISS_FALLBACK);
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() -> repository.findById(1L).isPresent());
    }

    private void assertLiveness() throws Exception {
        HttpResponse<String> health = request("GET", "/actuator/health/liveness", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(health.body()).path("status").asText()).isEqualTo("UP");
    }

    private HttpResponse<String> getStatus(long id) throws Exception {
        return request("GET", "/api/v1/payments/" + id + "/status", null);
    }

    private HttpResponse<String> patchStatus(long id) throws Exception {
        return request("PATCH", "/api/v1/payments/" + id + "/status", "{\"targetStatus\":\"AUTH\"}");
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertResponse(HttpResponse<String> response, int status, String cacheResult, String paymentStatus) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (cacheResult != null) {
            assertThat(response.headers().firstValue("X-Cache-Result").orElseThrow()).isEqualTo(cacheResult);
        }
        assertThat(JSON.readTree(response.body()).path("status").asText()).isEqualTo(paymentStatus);
    }

    private String redisCommand(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 2];
        command[0] = "redis-cli";
        command[1] = "--raw";
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        var result = redis.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().trim();
    }

    private double counter(String name, String... tags) {
        Counter counter = metrics.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private static JsonNode proxy(String method, String path, Object body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://"
                        + toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8474) + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("User-Agent", "toxiproxy-cli/2.12.0")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }
}

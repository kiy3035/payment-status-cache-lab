# Payment Status Cache Lab — Project Specification

## 1. 프로젝트 개요

`payment-status-cache-lab`은 결제 상태 조회 요청이 매번 DB를 조회할 때 발생하는 DB QPS·응답시간·CPU 부하를 재현하고, Redis cache-aside 적용 전후와 Redis 장애 상황을 같은 조건에서 비교하는 로컬 백엔드 실험 프로젝트다.

핵심은 Redis를 단순 적용하는 것이 아니라 다음 질문을 코드와 실제 측정 결과로 검증하는 것이다.

1. DB 직접 조회를 Redis 우선 조회로 변경하면 DB QPS가 얼마나 감소하는가?
2. 평균·p95·p99 응답시간과 애플리케이션·DB CPU는 어떻게 변하는가?
3. Redis miss, 100ms timeout, 연결 실패 시 DB fallback이 실제로 동작하는가?
4. Redis가 중단돼도 결제 상태 조회와 변경이 가능한가?
5. 결제 상태 변경 후 DB와 Redis의 상태는 어떤 순서로 동기화되는가?
6. cache-aside 방식이 보장하지 못하는 일관성 범위는 무엇인가?

## 2. 목표

- Spring Boot·JPA로 결제 상태 조회·변경 API를 구현한다.
- `READY → AUTH → APPROVED` 상태 전이만 허용한다.
- 동일 조회 API에서 DB-only와 Redis cache-aside 모드를 설정으로 전환한다.
- Redis hit이면 DB를 호출하지 않는다.
- Redis miss이면 DB 조회 후 TTL과 함께 Redis에 저장한다.
- Redis command가 100ms를 초과하거나 연결에 실패하면 DB로 fallback한다.
- DB commit 이후 Redis를 최신 상태로 갱신한다.
- Redis 장애가 DB transaction 성공 여부를 바꾸지 않도록 한다.
- k6 100 RPS에서 DB QPS·응답시간·CPU·cache hit ratio를 측정한다.
- Redis 중단·지연·복구 시나리오를 실제로 실행한다.
- 실험 환경과 원시 결과를 보존해 결과를 재현할 수 있게 한다.

## 3. 비목표

다음 항목은 이번 프로젝트 범위에 포함하지 않는다.

- 실제 PG사 결제 승인 연동
- 회원 인증·인가
- 프론트엔드 화면
- Kafka 또는 다른 메시지 브로커
- Redis Cluster·Sentinel 구축
- 분산락
- 결제 취소·환불 상태
- 여러 서비스로 분리한 MSA
- Kubernetes 배포
- 의도적인 DB 지연으로 만든 과장된 성능 수치

## 4. 기술 기준

- Java 21
- Spring Boot 3.5.16
- Gradle Wrapper 8.12.1
- Spring Web MVC
- Spring Data JPA
- MySQL 8.4
- Redis 7.4
- Lettuce
- Flyway
- Spring Boot Actuator
- Micrometer Prometheus Registry
- Testcontainers
- JUnit 5
- k6
- Docker Compose
- Toxiproxy 또는 동등한 네트워크 장애 주입 도구

로컬 컴퓨터에 MySQL, Redis, k6를 전역 설치하지 않는다. Docker와 Gradle Wrapper만으로 재현 가능해야 한다.

## 5. 전체 흐름

### 5.1 DB-only 조회

```text
Client
  → Payment Status API
  → MySQL SELECT
  → Response
```

캐시 비활성화 모드에서는 Redis 연결과 Redis 명령을 전혀 시도하지 않는다.

### 5.2 Redis 우선 조회

```text
Client
  → Payment Status API
  → Redis GET
      ├─ HIT → Response
      └─ MISS / TIMEOUT / ERROR
           → MySQL SELECT
           → Redis SET with TTL (best effort)
           → Response
```

### 5.3 상태 변경

```text
Client
  → Payment Status Change API
  → 상태 전이 검증
  → MySQL UPDATE
  → Transaction COMMIT
  → Redis 최신 상태 갱신 (best effort)
  → Response
```

MySQL이 원본이고 Redis는 파생 데이터다. Redis 실패로 이미 commit된 DB 변경을 rollback하거나 API 실패처럼 보이게 하지 않는다.

## 6. 데이터 모델

### 6.1 payments

최소 컬럼은 다음과 같다.

| 컬럼 | 타입 예시 | 제약 | 설명 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK | 결제 ID |
| `status` | VARCHAR(20) | NOT NULL | READY, AUTH, APPROVED |
| `version` | BIGINT | NOT NULL | JPA optimistic locking version |
| `created_at` | DATETIME(6) | NOT NULL | 생성 시각 |
| `updated_at` | DATETIME(6) | NOT NULL | 마지막 변경 시각 |

- 상태 값은 DB constraint 또는 동등한 방식으로 허용값을 제한한다.
- 스키마와 seed는 Flyway migration으로 관리한다.
- JPA의 `ddl-auto`는 `validate`로 사용한다.
- 부하 테스트용 합성 데이터는 최소 100,000건을 생성한다.
- seed 결과는 실행할 때마다 동일해야 한다.
- 상태 조회는 PK 기반으로 수행한다. 성능 차이를 과장하기 위한 비효율 쿼리를 만들지 않는다.

## 7. 상태 전이 규칙

결제 상태는 다음 순서로만 진행한다.

```text
READY → AUTH → APPROVED
```

허용:

- READY → AUTH
- AUTH → APPROVED

거부:

- READY → APPROVED
- AUTH → READY
- APPROVED → READY
- APPROVED → AUTH
- 같은 상태로 변경
- 정의되지 않은 상태

잘못된 상태 전이는 HTTP 409와 `INVALID_PAYMENT_STATUS_TRANSITION` 오류 코드로 반환한다.

JPA Entity 또는 명확한 도메인 객체가 상태 전이 가능 여부를 판단한다. 서비스가 `setStatus()`로 값을 무조건 덮어쓰는 구조는 사용하지 않는다.

동시 변경 감지를 위해 `@Version`을 사용한다. optimistic locking 충돌은 자동 무한 재시도하지 않고 HTTP 409와 `PAYMENT_STATUS_CONFLICT`로 변환한다.

## 8. API 규격

### 8.1 결제 상태 조회

```http
GET /api/v1/payments/{paymentId}/status
```

응답 예시:

```json
{
  "paymentId": 1,
  "status": "READY",
  "version": 0,
  "updatedAt": "2026-08-31T00:00:00Z"
}
```

실험과 테스트에서 조회 경로를 확인할 수 있도록 다음 응답 헤더를 제공한다.

```http
X-Cache-Result: HIT
```

허용 값:

- `HIT`
- `MISS_FALLBACK`
- `TIMEOUT_FALLBACK`
- `ERROR_FALLBACK`
- `DISABLED`

존재하지 않는 결제는 HTTP 404와 `PAYMENT_NOT_FOUND`를 반환한다. 이번 버전에서는 404를 negative caching하지 않는다.

### 8.2 결제 상태 변경

```http
PATCH /api/v1/payments/{paymentId}/status
Content-Type: application/json
```

요청 예시:

```json
{
  "targetStatus": "AUTH"
}
```

응답은 변경된 `paymentId`, `status`, `version`, `updatedAt`을 반환한다. 상태 변경 응답은 Redis를 다시 읽어서 만들지 않고 transaction에서 확정한 DB 결과를 기준으로 만든다.

### 8.3 오류 응답

공통 형식:

```json
{
  "code": "INVALID_PAYMENT_STATUS_TRANSITION",
  "message": "READY 상태에서는 AUTH 상태로만 변경할 수 있습니다.",
  "timestamp": "2026-08-31T00:00:00Z"
}
```

| 상황 | HTTP | 오류 코드 |
| --- | --- | --- |
| 잘못된 요청값 | 400 | `INVALID_REQUEST` |
| 결제 없음 | 404 | `PAYMENT_NOT_FOUND` |
| 잘못된 상태 전이 | 409 | `INVALID_PAYMENT_STATUS_TRANSITION` |
| optimistic locking 충돌 | 409 | `PAYMENT_STATUS_CONFLICT` |
| Redis 실패 후 DB도 실패 | 503 | `PAYMENT_STATUS_UNAVAILABLE` |

## 9. Cache-aside 정책

### 9.1 캐시 키와 값

- key: `payment:status:{paymentId}`
- value: 명시적인 JSON 또는 문자열 포맷
- 기본 TTL: 5분
- Java native serialization 금지

캐시 값에는 최소한 다음을 포함한다.

- paymentId
- status
- version
- updatedAt

### 9.2 조회 알고리즘

1. `payment.status-cache.enabled=false`이면 DB를 조회하고 `DISABLED`를 반환한다.
2. 활성화 상태이면 Redis GET을 실행한다.
3. 값이 있으면 `HIT`으로 반환하고 DB를 호출하지 않는다.
4. 값이 없으면 DB를 조회하고 `MISS_FALLBACK`으로 반환한다.
5. Redis command timeout이면 DB를 조회하고 `TIMEOUT_FALLBACK`으로 반환한다.
6. Redis 연결 실패 또는 Redis 접근 오류이면 DB를 조회하고 `ERROR_FALLBACK`으로 반환한다.
7. fallback DB 조회가 성공하면 TTL과 함께 Redis 재저장을 시도한다.
8. 재저장 실패는 metric과 로그를 남기고 DB 응답은 정상 반환한다.
9. Redis와 DB가 모두 실패한 경우에만 HTTP 503을 반환한다.

Redis timeout은 Lettuce command timeout으로 구현하며 기본값은 100ms다. 연결 timeout도 별도 설정값으로 노출한다.

### 9.3 상태 변경과 캐시 동기화

1. JPA transaction 안에서 결제를 조회한다.
2. 현재 상태와 목표 상태를 검증한다.
3. DB 상태를 변경한다.
4. commit 성공 이후 최신 결제 상태를 Redis에 `SET`한다.
5. Redis 갱신 실패는 별도 metric과 로그로 남긴다.
6. Redis 갱신 실패를 이유로 DB transaction을 실패 처리하지 않는다.
7. DB rollback 시 Redis를 변경하지 않는다.

구현은 `@TransactionalEventListener(phase = AFTER_COMMIT)` 또는 commit 이후 실행을 동일하게 보장하는 구조를 사용한다. listener 내부에서 Redis 예외를 처리해 이미 commit된 변경이 API 실패처럼 노출되지 않게 한다.

### 9.4 일관성 한계

이 프로젝트의 cache-aside 구조는 DB와 Redis를 하나의 원자적 transaction으로 묶지 않는다.

- Redis가 정상인 경우에는 DB commit 이후 최신값을 즉시 덮어쓴다.
- Redis가 완전히 중단된 동안 조회는 DB fallback으로 최신 상태를 반환한다.
- commit 이후 Redis 갱신만 실패하고 기존 key가 남으면 TTL 동안 stale 값이 존재할 수 있다.
- 기본 TTL 5분은 stale 가능 시간을 제한하지만 강한 일관성을 보장하지 않는다.
- 이 한계를 테스트 결과와 최종 문서에서 숨기지 않는다.

Outbox, CDC, versioned key 같은 강한 동기화 보완책은 후속 개선안으로만 설명하고 이번 기본 구현에 추가하지 않는다.

## 10. 설정

예시:

```yaml
payment:
  status-cache:
    enabled: true
    ttl: 5m
    command-timeout: 100ms
    connect-timeout: 100ms
```

최소 환경변수:

```text
PAYMENT_STATUS_CACHE_ENABLED=true
PAYMENT_STATUS_CACHE_TTL=5m
PAYMENT_STATUS_CACHE_COMMAND_TIMEOUT=100ms
PAYMENT_STATUS_CACHE_CONNECT_TIMEOUT=100ms
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=payment_lab
MYSQL_USER=payment_app
MYSQL_PASSWORD=local-only-secret
REDIS_HOST=localhost
REDIS_PORT=6379
```

실제 값은 `.env` 등 Git 제외 파일에 두고 `.env.example`에는 예시만 작성한다.

## 11. 상태 확인과 장애 격리

- Actuator health와 liveness를 활성화한다.
- Redis 장애 때문에 애플리케이션 liveness가 `DOWN`이 되면 안 된다.
- 전체 health 또는 readiness에서는 Redis 상태 저하를 표시할 수 있다.
- Docker healthcheck는 Redis 의존성을 포함한 전체 health가 아니라 애플리케이션 생존 여부를 확인한다.
- Redis가 중단돼도 애플리케이션 컨테이너가 자동 재시작 루프에 빠지지 않게 한다.

## 12. 관측 지표

Micrometer로 최소 다음 metric을 제공한다.

```text
payment_status_cache_access_total{result="hit|miss|timeout|error|disabled"}
payment_status_db_read_total{reason="cache_disabled|miss|timeout|redis_error"}
payment_status_cache_write_total{result="success|error"}
payment_status_transition_total{from="READY",to="AUTH",result="success|failure"}
```

추가로 조회 API와 DB 조회 시간 Timer를 제공한다.

제약:

- `paymentId`를 label로 사용하지 않는다.
- 예외 메시지 전체를 label로 사용하지 않는다.
- cache hit ratio를 계산할 수 있어야 한다.
- timeout과 connection error를 구분할 수 있어야 한다.
- 상태 변경 성공·실패와 Redis 동기화 실패를 구분한다.

## 13. DB QPS 정의

DB QPS는 다음 두 값을 함께 기록한다.

1. `payment_status_db_read_total` 증가량 ÷ 본 측정 시간
   - 결제 상태 조회 로직이 실제로 DB를 호출한 QPS
2. MySQL `Com_select` 전후 차이 ÷ 본 측정 시간
   - 격리된 MySQL 컨테이너의 전체 SELECT QPS

MySQL 전역 상태를 강제로 초기화하지 않고 측정 직전과 직후의 차이를 사용한다. 측정 환경에는 실험 외 트래픽을 넣지 않는다.

## 14. 테스트 요구사항

### 14.1 단위 테스트

- READY → AUTH 성공
- AUTH → APPROVED 성공
- READY → APPROVED 거부
- 역방향 전이 거부
- APPROVED 이후 변경 거부
- 같은 상태 변경 거부

### 14.2 MySQL 통합 테스트

- Flyway migration 적용
- JPA schema validation
- 결제 조회
- 유효한 상태 변경
- 잘못된 상태 변경 rollback
- optimistic locking 충돌
- 존재하지 않는 결제 404

### 14.3 Redis 통합 테스트

- cache miss → DB 조회 → Redis 저장
- 두 번째 조회는 cache hit
- cache hit에서는 DB repository 미호출
- TTL 적용
- JSON 직렬화·역직렬화
- 상태 변경 commit 이후 Redis 최신값 반영
- 상태 변경 rollback 시 Redis 미변경
- Redis write 실패 후 상태 변경 API 성공

### 14.4 장애 주입 테스트

Toxiproxy 또는 동등한 도구로 실제 Redis 경로에 장애를 주입한다.

- Redis 연결 실패 → DB fallback
- Redis read timeout → DB fallback
- fallback 이후 Redis write 실패 → DB 응답 성공
- Redis와 DB 동시 실패 → HTTP 503
- Redis 중단 중 애플리케이션 liveness 유지
- Redis 복구 후 cache miss → 재저장 → 다음 요청 hit

Redis 지연은 250ms 이상으로 주입하고 Lettuce command timeout은 100ms로 유지한다. wall-clock assertion은 CI 환경 편차를 고려하되 무한 대기나 초 단위 지연을 허용하지 않는다.

## 15. 성능 측정

### 15.1 공통 조건

- k6 `constant-arrival-rate`
- warm-up 30초
- 본 측정 120초
- 100 RPS
- hot-set 결제 ID 1,000개
- 정상 시나리오는 각 3회 실행
- 최종 비교값은 3회 중앙값
- 동일 애플리케이션 이미지와 JVM 옵션
- 동일 DB 데이터
- 동일 요청 ID 범위
- 동일 Docker CPU·메모리 제한

수집 항목:

- 요청 수
- 성공률
- 평균 응답시간
- p50
- p95
- p99
- 최대 응답시간
- 상태 조회 DB QPS
- MySQL SELECT QPS
- cache hit ratio
- 애플리케이션 CPU 평균·p95
- MySQL CPU 평균·p95
- Redis CPU 평균·p95

CPU는 `docker stats` 등을 사용해 1초 간격으로 수집한다.

### 15.2 시나리오 A — DB-only

- cache disabled
- 동일 GET API에 100 RPS
- Redis 명령 0회 확인
- DB QPS와 응답시간·CPU 기록

### 15.3 시나리오 B — Redis 정상

- cache enabled
- warm-up으로 hot-set 적재
- 동일 GET API에 100 RPS
- DB QPS, 응답시간, CPU, hit ratio 기록

### 15.4 시나리오 C — Redis 완전 중단

- 애플리케이션을 재시작하지 않고 Redis 중단
- 100 RPS로 최소 30초 측정
- API 성공률과 error fallback 기록
- DB QPS와 평균·p95·p99 응답시간 기록
- 애플리케이션 liveness와 프로세스 유지 확인

### 15.5 시나리오 D — Redis 100ms timeout

- Toxiproxy로 250ms 이상 지연 주입
- Redis command timeout 100ms
- 100 RPS로 최소 30초 측정
- timeout fallback 성공률과 응답시간 기록

### 15.6 시나리오 E — Redis 복구

- 장애 제거
- 첫 요청이 DB fallback 후 cache를 채우는지 확인
- 다음 요청이 Redis hit으로 전환되는지 확인

## 16. 결과 산출물

예시 디렉터리:

```text
results/
  2026xxxx-xxxxxx/
    environment.json
    db-only-run-1.json
    db-only-run-2.json
    db-only-run-3.json
    redis-run-1.json
    redis-run-2.json
    redis-run-3.json
    redis-down.json
    redis-timeout.json
    redis-recovery.json
    cpu.csv
    db-qps.csv
    summary.md
```

`environment.json`에는 다음을 기록한다.

- OS
- CPU
- 메모리
- Docker 버전
- Java 버전
- 애플리케이션·MySQL·Redis 이미지 버전
- 컨테이너 CPU·메모리 제한
- JVM 옵션
- k6 조건

결과 수치와 요약 문서가 원시 JSON·CSV와 일치해야 한다.

## 17. 단계별 구현 계획

### 1단계 — 프로젝트와 로컬 실행 환경

- Java 21 / Spring Boot / Gradle 프로젝트 구성
- 최소 의존성 구성
- Docker Compose에 MySQL, Redis, Toxiproxy 구성
- 환경변수 설정과 `.env.example`
- Flyway migration
- `payments` 테이블과 deterministic seed 100,000건
- Actuator health·liveness
- Testcontainers MySQL·Redis smoke test
- `README.md` 골격
- `PROGRESS.md`
- 전체 테스트·실제 애플리케이션·Docker 연결 검증

1단계에서는 조회·상태 변경 비즈니스 로직을 구현하지 않는다.

### 2단계 — 상태 전이와 DB-only API

- JPA Entity·Repository
- READY → AUTH → APPROVED 상태 머신
- `@Version` optimistic locking
- 상태 조회·변경 API
- 공통 오류 응답
- cache disabled 모드
- 단위·MySQL 통합 테스트
- DB-only k6 스크립트 골격

### 3단계 — Redis 우선 조회와 상태 동기화

- Redis cache adapter
- hit·miss 처리
- TTL
- DB fallback과 캐시 재저장
- 100ms Lettuce command timeout
- AFTER_COMMIT Redis 동기화
- `X-Cache-Result`
- Micrometer metric
- hit·miss·commit·rollback 통합 테스트

### 4단계 — Redis 장애와 복구 검증

- connection failure fallback
- Toxiproxy timeout
- Redis read·write 실패
- Redis 중단·복구
- Redis와 DB 동시 실패
- liveness 격리
- 동시 상태 전이 검증

### 5단계 — k6 측정 자동화

- DB-only, Redis 정상, Redis 중단, timeout, 복구 시나리오
- 100 RPS 실험
- DB QPS와 docker CPU 자동 수집
- JSON·CSV 결과 저장
- 정상 시나리오 3회 반복과 중앙값
- 실제 비교표 생성
- Windows PowerShell 실행 스크립트 우선 제공
- 가능하면 Bash 스크립트도 제공

### 6단계 — 최종 문서화

- 전체 재현 절차 재검증
- `README.md` 완성
- `RESULTS.md`
- `BLOG_DRAFT.md`
- 아키텍처와 조회·상태 변경 흐름
- 실제 성능 비교표
- 장애 실험 결과
- cache-aside 트레이드오프와 일관성 한계
- 테스트 목록
- 기술 선택 이유
- 이력서·포트폴리오용 사실 기반 요약

## 18. 전체 완료 기준

- `./gradlew test` 전체 통과
- 실제 MySQL·Redis·애플리케이션 실행 확인
- READY → AUTH → APPROVED 상태 전이 검증
- Redis hit에서 DB 미호출 검증
- miss·100ms timeout·connection failure에서 DB fallback 검증
- Redis 중단 중 조회·상태 변경 API 검증
- DB rollback 시 Redis 미갱신 검증
- commit 후 Redis 최신 상태 반영 검증
- optimistic locking 충돌 검증
- 100 RPS DB-only와 Redis 정상 비교 3회 완료
- Redis 중단·timeout·복구 측정 완료
- DB QPS·응답시간·CPU·hit ratio 원시 결과 보존
- 실제 수치와 문서 일치 확인
- README만으로 전체 실험 재현 가능

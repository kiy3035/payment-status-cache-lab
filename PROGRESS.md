# 진행 상황

기준일: 2026-09-04

## 단계 상태

- 1단계 — 프로젝트와 로컬 실행 환경: 완료
- 2단계 — 상태 전이와 DB-only API: 완료
- 3단계 — Redis 우선 조회와 상태 동기화: 완료
- 4단계 — Redis 장애와 복구 검증: 완료
- 5~6단계: 미착수

## 1단계 완료 항목

- Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.12.1 프로젝트 구성
- Spring Web MVC, Spring Data JPA, Spring Data Redis/Lettuce, MySQL Driver, Flyway, Actuator, Prometheus Registry 의존성 구성
- JUnit 5, Testcontainers MySQL·Redis smoke test 구성
- 환경변수 기반 MySQL·Redis·cache 설정과 type-safe `PaymentStatusCacheProperties` 구성
- JPA `ddl-auto=validate`, Flyway 활성화, UTC JDBC 시간대 구성
- Actuator 전체 health, liveness, readiness, `/livez`, `/readyz` 구성
- MySQL 8.4.6, Redis 7.4.5, Toxiproxy 2.12.0 Compose 서비스·포트·volume·healthcheck 구성
- Flyway `payments` schema와 상태 CHECK 제약 구성
- 무작위 함수 없이 ID 1~100,000을 만드는 deterministic seed 구성
- `.env.example` 제공 및 `.env`, 로컬 빌드·검증 디렉터리 Git 제외
- README 재현 절차 작성

1단계에서는 `Payment` JPA Entity, Repository, 서비스, Controller, 상태 전이, cache-aside 로직을 구현하지 않았다.

## 2단계 완료 항목

- `Payment` JPA Entity와 `PaymentStatus` enum 구성
- Entity 내부의 `READY → AUTH → APPROVED` 상태 전이 규칙 구성
- `@Version` 기반 optimistic locking 구성
- API DTO와 JPA Entity 분리
- DB-only 결제 상태 조회·변경 서비스와 Controller 구성
- 조회 응답 `X-Cache-Result: DISABLED` 헤더 구성
- HTTP 400·404·409·503 공통 오류 응답 구성
- cache disabled 기본값과 Redis health·repository 비활성화 구성
- 상태 전이 단위 테스트 구성
- 실제 MySQL 기반 조회·변경·rollback·optimistic locking 통합 테스트 구성
- optimistic locking HTTP 409 변환 테스트 구성
- 100 RPS `constant-arrival-rate` DB-only k6 골격 구성
- README DB-only 재현 절차 갱신

2단계에서는 Redis cache adapter, hit·miss·TTL, fallback, AFTER_COMMIT 동기화와 cache metric을 구현하지 않았다.

## 1단계 주요 설계 결정

- Spring Boot BOM은 별도 dependency-management 플러그인을 추가하지 않고 Gradle 내장 `platform`으로 적용했다.
- Redis 운영 command/connect timeout 기본값은 요구사항대로 100ms다. 1단계 smoke test는 장애 timeout 측정이 아니라 실제 연결 확인이 목적이므로 최초 handshake 환경 편차를 흡수하도록 Spring Redis 테스트 timeout만 2초로 오버라이드한다.
- `liveness` health group에는 `livenessState`만 포함하고, `readiness`에는 `readinessState`, DB, Redis를 포함해 Redis 상태가 애플리케이션 생존 판정을 내리지 않게 했다.
- seed는 0~9 derived table 다섯 개의 cross join으로 정확히 100,000개 번호를 만들며 ID, 상태, 시각을 항상 같은 규칙으로 생성한다.
- Toxiproxy 2.12.0 공식 컨테이너는 Docker Hub가 아닌 GitHub Container Registry에서 제공되므로 `ghcr.io/shopify/toxiproxy:2.12.0`을 사용한다.
- Toxiproxy proxy port `26379`는 4단계 장애 주입 기반으로만 노출하며 현재 proxy 생성은 하지 않는다.

## 2단계 주요 설계 결정

- 서비스가 상태 문자열을 덮어쓰지 않고 Entity의 `transitionTo`가 전이 가능 여부를 판단한다.
- 상태 변경 transaction에서 `flush()`를 수행해 DB가 확정한 증가 version을 응답한다.
- optimistic locking 충돌은 자동 재시도하지 않고 `PAYMENT_STATUS_CONFLICT` HTTP 409로 변환한다.
- cache disabled 기본값은 `false`이며 조회 API는 항상 DB를 사용하고 `DISABLED` 헤더를 반환한다.
- Redis 없이 DB-only 애플리케이션이 정상 기동하도록 Redis health indicator와 Redis repository scanning을 비활성화했다.
- 잘못된 전이는 Entity 변경 전에 예외를 발생시켜 transaction rollback과 원본 상태 유지를 보장한다.
- 운영 코드에 테스트용 sleep이나 충돌 유도 분기를 추가하지 않았다.

## 1단계 실제 실행한 검증 명령

```powershell
java -version
docker version
.\gradlew.bat --no-daemon testClasses
.\gradlew.bat --no-daemon test
docker pull mysql:8.4.6
docker pull redis:7.4.5-alpine
docker pull ghcr.io/shopify/toxiproxy:2.12.0
docker compose config --quiet
docker compose up -d --wait
.\gradlew.bat --no-daemon bootJar
java -jar build\libs\payment-status-cache-lab-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://localhost:18080/actuator/health
Invoke-RestMethod http://localhost:18080/actuator/health/liveness
Invoke-RestMethod http://localhost:18080/actuator/health/readiness
Invoke-RestMethod http://localhost:18080/livez
docker exec payment-status-cache-lab-mysql-1 mysql ...
docker exec payment-status-cache-lab-redis-1 redis-cli PING
docker exec payment-status-cache-lab-redis-1 redis-cli SET stage1:manual-roundtrip connected EX 10
docker exec payment-status-cache-lab-redis-1 redis-cli GET stage1:manual-roundtrip
docker exec payment-status-cache-lab-redis-1 redis-cli DEL stage1:manual-roundtrip
Invoke-WebRequest http://localhost:8474/version -Headers @{ 'User-Agent' = 'toxiproxy-cli/2.12.0' }
```

로컬 `3306` 포트가 사용 중이어서 실제 Compose 검증에서는 `MYSQL_PORT=13306`, 애플리케이션은 `SERVER_PORT=18080`을 사용했다. 비밀번호는 소스나 `.env`에 저장하지 않고 검증 프로세스 환경변수로만 전달했다.

## 1단계 테스트 결과

- 최종 명령: `.\gradlew.bat --no-daemon test --console=plain`
- 결과: 성공
- 테스트: 4개 실행, 4개 성공, 실패 0, skipped 0
- 실제 MySQL 컨테이너 기동과 Spring context: 성공
- Flyway migration 2개와 JPA `validate` context 기동: 성공
- deterministic `payments` 100,000건, 최소 ID 1, 최대 ID 100,000: 성공
- 실제 Redis 컨테이너 연결, PING, SET/GET round-trip: 성공
- 테스트 종료 후 Testcontainers 리소스와 Java 프로세스 종료: 확인

## 1단계 실제 실행 상태

- Java: Temurin OpenJDK 21.0.8
- Gradle Wrapper: 8.12.1
- Spring Boot: 3.5.16
- Docker Engine: 24.0.7
- MySQL: 8.4.6, Compose health `healthy`
- Redis: 7.4.5, Compose health `healthy`, `PONG` 및 `connected` round-trip 확인
- Toxiproxy: 2.12.0, Compose health `healthy`, HTTP `/version` 200 확인
- Flyway: 성공한 migration 2개, 현재 schema version 2
- `payments`: 100,000건, ID 1~100,000
- 애플리케이션: boot jar 실제 기동 성공
- Actuator 전체 health: `UP`
- Actuator liveness: `UP`
- Actuator readiness: `UP`
- `/livez`: `UP`
- 검증 종료 후 애플리케이션 프로세스와 Compose 컨테이너·네트워크 종료 확인, MySQL·Redis named volume 보존

## 1단계 발생한 오류와 해결

- 제한된 실행 환경의 기본 Gradle cache/temp 경로에서 native library 초기화가 실패했다. 프로젝트 내부 `.gradle-user-home`, `.tmp`를 사용해 wrapper와 빌드를 검증했다.
- 첫 의존성 해석에서 starter 버전이 비어 실패했다. Spring Boot 3.5.16 BOM을 Gradle `platform`으로 명시해 해결했다.
- 첫 전체 테스트에서 Redis 최초 handshake가 운영 기본 timeout 100ms를 넘겨 4개 중 1개가 실패했다. 운영 설정은 유지하고 smoke test의 Spring Redis timeout만 2초로 분리한 뒤 4개 전체 통과를 확인했다.
- `shopify/toxiproxy:2.12.0`은 Docker Hub에 존재하지 않았다. 공식 배포 위치인 `ghcr.io/shopify/toxiproxy:2.12.0`으로 변경하고 실제 pull과 기동을 확인했다.
- 공식 Toxiproxy 2.12.0 이미지의 binary 경로가 `/toxiproxy`인데 초기 healthcheck가 `/toxiproxy-server`를 사용해 unhealthy가 됐다. 실제 entrypoint와 version 명령을 확인해 경로를 수정했다.
- 호스트 `3306` 포트가 이미 사용 중이라 첫 Compose MySQL 기동이 실패했다. 외부 리소스를 변경하지 않고 검증용 `MYSQL_PORT=13306`으로 재기동했다.
- Toxiproxy 2.12.0 HTTP API가 PowerShell 기본 User-Agent를 거부했다. `toxiproxy-cli/2.12.0` User-Agent로 `/version` 200과 버전 응답을 확인했다.

## 1단계 주요 생성·수정 파일

- `build.gradle`, `settings.gradle`
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- `.gitignore`, `.env.example`
- `docker-compose.yml`
- `src/main/java/dev/paymentlab/PaymentStatusCacheLabApplication.java`
- `src/main/java/dev/paymentlab/config/PaymentStatusCacheProperties.java`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V1__create_payments.sql`
- `src/main/resources/db/migration/V2__seed_payments.sql`
- `src/test/java/dev/paymentlab/InfrastructureSmokeTest.java`
- `README.md`, `PROGRESS.md`

## 2단계 실제 실행한 검증 명령

```powershell
.\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=' test --rerun-tasks --max-workers=1 --console=plain
docker compose config --quiet
docker compose up -d --wait redis
docker exec payment-status-cache-lab-redis-1 redis-cli PING
docker exec payment-status-cache-lab-redis-1 redis-cli SET stage2:connection-check connected EX 30
docker exec payment-status-cache-lab-redis-1 redis-cli GET stage2:connection-check
docker exec payment-status-cache-lab-redis-1 redis-cli DEL stage2:connection-check
docker stop payment-status-cache-lab-redis-1
docker compose up -d --wait mysql
.\gradlew.bat --no-daemon bootJar
java -jar build\libs\payment-status-cache-lab-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://localhost:18080/actuator/health
Invoke-RestMethod http://localhost:18080/actuator/health/liveness
Invoke-RestMethod http://localhost:18080/actuator/health/readiness
Invoke-WebRequest http://localhost:18080/api/v1/payments/100000/status
Invoke-WebRequest http://localhost:18080/api/v1/payments/100000/status -Method Patch -ContentType 'application/json' -Body '{"targetStatus":"AUTH"}'
Invoke-WebRequest http://localhost:18080/api/v1/payments/99997/status -Method Patch -ContentType 'application/json' -Body '{"targetStatus":"APPROVED"}'
Invoke-WebRequest http://localhost:18080/api/v1/payments/999999/status
Invoke-WebRequest http://localhost:18080/api/v1/payments/99997/status -Method Patch -ContentType 'application/json' -Body '{"targetStatus":"CANCELLED"}'
docker exec payment-status-cache-lab-mysql-1 mysql ...
rg -n "RedisConnection|RedisCommand|Bootstrapping Spring Data Redis repositories" build/stage2-final-app.*.log
```

로컬 `3306` 포트 충돌을 피하기 위해 MySQL은 `13306`, 애플리케이션은 `18080`에서 검증했다. Redis 연결이 우연히 성공하지 않도록 Redis 컨테이너를 기동하지 않고 애플리케이션의 검증용 Redis port도 사용하지 않는 `6399`로 지정했다.

## 2단계 테스트 결과

- 최종 명령: `.\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=' test --rerun-tasks --max-workers=1 --console=plain`
- 결과: `BUILD SUCCESSFUL in 6m 19s`
- 전체 테스트: 20개 실행, 20개 성공, 실패 0, errors 0, skipped 0
- 상태 전이 단위 테스트: 9개 성공
- MySQL/API 통합 테스트: 6개 성공
- optimistic locking HTTP 변환 테스트: 1개 성공
- 기존 MySQL·Redis 인프라 smoke test: 4개 성공
- 실제 MySQL에서 stale entity update 충돌 감지: 성공
- 잘못된 상태 전이 후 DB 상태·version 미변경: 성공
- 존재하지 않는 결제 404와 정의되지 않은 상태 400: 성공

## 2단계 실제 실행 상태

- 애플리케이션: Redis 없이 boot jar 기동 성공
- MySQL: 8.4.6, Compose health `healthy`
- Redis 인프라: 7.4.5 Compose health `healthy`, `PONG`, `SET OK`, `GET connected`, 검증 키 삭제 확인
- DB-only 실행 중 Redis: 컨테이너 정지, 미사용 port `6399` 지정, 애플리케이션 로그에서 Redis 연결·명령 및 Redis repository 탐색 시도 없음
- 전체 health: `UP`
- liveness: `UP`
- readiness: `UP`
- 조회 API: HTTP 200, `X-Cache-Result: DISABLED`
- `READY → AUTH`: HTTP 200, ID 99994의 version 0에서 1로 증가, DB 값과 응답 일치
- `READY → APPROVED`: HTTP 409, DB 상태 `READY`와 version 0 유지
- 존재하지 않는 결제: HTTP 404, `PAYMENT_NOT_FOUND`
- 정의되지 않은 상태: HTTP 400, `INVALID_REQUEST`
- `payments`: 전체 100,000건 유지
- 검증 종료 후 애플리케이션 PID와 port `18080` 응답 종료 확인
- 이 프로젝트 Compose 컨테이너·네트워크 제거 확인, MySQL·Redis named volume 보존 확인

## 2단계 발생한 오류와 해결

- 실제 checkout이 작업 루트 아래에 한 단계 중첩되어 있어 첫 Gradle 호출이 새 테스트를 `UP-TO-DATE`로 판단했다. 변경 파일을 실제 Git checkout에 동기화하고 `--rerun-tasks`로 강제 재컴파일·전체 실행해 20개 통과를 확인했다.
- 장시간 절전 후 첫 health 요청이 5초 timeout으로 취소됐다. MySQL container health를 확인하고 20초 timeout으로 재호출해 애플리케이션 `UP`을 확인했다.
- Flyway가 MySQL 8.4를 자신이 확인한 최신 지원 범위보다 새 버전이라고 경고했지만 migration validation, JPA schema validation, 조회·변경 통합 테스트는 실제 MySQL 8.4.6에서 모두 성공했다. 경고를 성공으로 숨기지 않고 제한사항으로 유지한다.
- 최종 설정 반영 후 첫 강제 테스트 재실행에서 호스트의 심한 프로세스·Docker 응답 지연으로 Testcontainers 컨테이너 기동 timeout이 1회 발생했다. 잔여 Testcontainers 리소스가 없음을 확인하고 worker를 1개로 제한해 전체 테스트를 다시 실행했으며 20개 모두 성공했다.
- PowerShell에서 따옴표 없는 `-Dorg.gradle.jvmargs=`가 Gradle task 이름으로 해석돼 1회 즉시 실패했다. 인수를 따옴표로 고정한 실제 최종 명령으로 재실행해 성공했다.
- 재개 시 Docker Desktop 엔진이 종료돼 첫 Docker 상태 확인이 실패했다. Docker Desktop을 다시 기동한 뒤 Engine 24.0.7 응답과 MySQL·Redis 실제 연결을 확인했다.
- 샌드박스 실행에서 checkout 외부 Gradle cache의 wrapper lock 접근이 거부돼 `bootJar`가 1회 실패했다. 동일 명령을 승인된 실행 범위에서 다시 실행해 21초 만에 성공했다.

## 2단계 주요 생성·수정 파일

- `build.gradle`, `.env.example`
- `src/main/resources/application.yml`
- `src/main/java/dev/paymentlab/payment/Payment.java`
- `src/main/java/dev/paymentlab/payment/PaymentStatus.java`
- `src/main/java/dev/paymentlab/payment/PaymentRepository.java`
- `src/main/java/dev/paymentlab/payment/PaymentStatusService.java`
- `src/main/java/dev/paymentlab/payment/api/*`
- `src/main/java/dev/paymentlab/payment/PaymentNotFoundException.java`
- `src/main/java/dev/paymentlab/payment/InvalidPaymentStatusTransitionException.java`
- `src/main/java/dev/paymentlab/common/*`
- `src/test/java/dev/paymentlab/payment/*`
- `k6/db-only.js`
- `README.md`, `PROGRESS.md`

## 3단계 완료 항목

- `StringRedisTemplate` 기반 Redis adapter와 명시적 JSON 값 구성
- 설정 기반 cache enabled, key prefix, TTL, command/connect timeout 구성
- hit·miss·timeout·connection error 분류 및 `X-Cache-Result` 반환
- cache disabled에서 Redis 접근과 조회 서비스 transaction 없이 공통 repository 조회
- hit에서 DB repository 및 DB 조회 경로 생략
- miss·timeout·error에서 공통 DB 조회 후 TTL 캐시 재저장 시도
- 잘못된 JSON, JSON null, 다른 결제 ID의 캐시 값을 오류로 분류
- DB transaction 안에서 불변 상태 변경 이벤트 발행
- `AFTER_COMMIT`에서 Redis 최신 상태 갱신 및 쓰기 실패 결과 처리
- transaction 완료 결과를 기준으로 상태 변경 성공·실패 지표 기록
- cache access·DB read·cache write·transition counter와 조회 API·DB read timer 구성
- README에 두 모드 전환, miss/hit, TTL, 동기화, 지표와 정합성 한계 기록

## 3단계 주요 설계 결정

- 캐시 hit 자체가 DB transaction을 시작하지 않도록 서비스 전체의 read-only transaction을 제거했다. DB 조회 transaction은 Spring Data repository에, 변경 transaction은 `changeStatus`에 한정한다.
- Redis 접근 예외는 adapter에서 `DataAccessException`·`JsonProcessingException` 경계로 변환한다. timeout과 연결 오류를 구분하고, best-effort 쓰기 실패는 API 실패로 전파하지 않는다.
- Redis key prefix와 TTL은 configuration properties에서 읽는다. 값은 paymentId·status·version·updatedAt을 포함하는 JSON이며 Java native serialization은 사용하지 않는다.
- Lettuce timeout과 Redis health는 `payment.status-cache` 설정을 참조한다. 별도 설정 값이 서로 다르게 적용되지 않도록 단일 설정 원천을 사용한다.
- 상태 변경 응답은 flush 이후 DB entity에서 생성한다. Redis 갱신은 `@TransactionalEventListener(AFTER_COMMIT)`에서만 수행한다.
- 외부 transaction이 최종 rollback되면 이미 발행한 이벤트도 Redis를 갱신하지 않는다. 성공 counter 역시 commit 전에는 증가하지 않는다.
- metric label은 제한된 결과·사유·상태 enum만 사용하며 결제 ID와 예외 메시지를 포함하지 않는다.

## 3단계 실제 실행한 검증 명령

```powershell
.\gradlew.bat --no-daemon compileTestJava --console=plain
.\gradlew.bat --no-daemon test --tests dev.paymentlab.payment.cache.PaymentStatusCacheIntegrationTest --rerun-tasks --max-workers=1 --console=plain
.\gradlew.bat --no-daemon test --tests dev.paymentlab.payment.cache.PaymentStatusCacheAdapterTest --console=plain
.\gradlew.bat --no-daemon test --rerun-tasks --max-workers=1 --console=plain
.\gradlew.bat --no-daemon bootJar --console=plain
docker compose -p payment-status-cache-lab-stage3-check config --quiet
docker compose -p payment-status-cache-lab-stage3-check up -d --wait mysql redis
java -jar build\libs\payment-status-cache-lab-0.0.1-SNAPSHOT.jar
Invoke-RestMethod http://127.0.0.1:18081/actuator/health
Invoke-RestMethod http://127.0.0.1:18081/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:18081/actuator/health/readiness
Invoke-WebRequest http://127.0.0.1:18081/api/v1/payments/100/status
Invoke-WebRequest http://127.0.0.1:18081/api/v1/payments/100/status
Invoke-RestMethod http://127.0.0.1:18081/api/v1/payments/103/status -Method Patch -ContentType 'application/json' -Body '{"targetStatus":"AUTH"}'
Invoke-WebRequest http://127.0.0.1:18081/api/v1/payments/103/status
docker exec payment-status-cache-lab-stage3-check-redis-1 redis-cli GET stage3:manual:payment:status:103
docker exec payment-status-cache-lab-stage3-check-redis-1 redis-cli TTL stage3:manual:payment:status:103
Invoke-WebRequest http://127.0.0.1:18081/actuator/prometheus
docker compose -p payment-status-cache-lab-stage3-check down --volumes
```

실제 checkout의 부모에 있는 `.gradle-user-home`·`.tmp`를 검증 실행의 Gradle cache와 Java temp로 사용했다. 최종 실기동은 Git 제외 `build/`의 임시 PowerShell 검증 스크립트로 실행하고 `finally`에서 프로세스·Compose 리소스를 정리했다. 복제한 저장소의 재현 절차는 README를 따른다.

실기동은 기존 프로젝트 데이터와 분리된 Compose project를 사용했다. MySQL host port `13307`, Redis host port `16379`, 앱 port `18081`, key prefix `stage3:manual:payment:status:`, TTL `2m`, command/connect timeout 각각 `100ms`로 검증했다. DB 비밀번호는 실행 시 생성해 프로세스 환경변수로만 전달했다.

## 3단계 테스트 결과

- 최종 전체 명령: `.\gradlew.bat --no-daemon test --rerun-tasks --max-workers=1 --console=plain`
- 결과: `BUILD SUCCESSFUL in 1m 48s`, 4개 작업 모두 실행
- XML 집계: 40개 실행, 40개 성공, 실패 0, errors 0, skipped 0
- 기존 상태 전이 단위 테스트 9개, MySQL/API 통합 테스트 6개, 충돌 응답 테스트 1개, 인프라 smoke test 4개 성공
- 조회 서비스 분기 단위 테스트 4개 성공
- Redis adapter 단위 테스트 8개 성공
- AFTER_COMMIT 동기화 쓰기 실패 결과 처리 단위 테스트 1개 성공
- 실제 MySQL·Redis 캐시 통합 테스트 7개 성공
- 통합 검증: miss→JSON/TTL 저장→hit 및 hit에서 repository 미호출
- 통합 검증: 변경 commit 이후 Redis 갱신, 잘못된 전이 rollback 시 캐시 보존
- 통합 검증: 유효한 변경 이후 외부 transaction commit 전 캐시·성공지표 미변경
- 통합 검증: 유효한 변경 이후 외부 transaction rollback 시 DB·캐시 보존 및 failure 지표 기록
- 통합 검증: 404 negative caching 없음, 실제 Lettuce client command timeout `100ms`

## 3단계 실제 실행 상태

- 최종 boot jar 빌드: `BUILD SUCCESSFUL in 19s`
- MySQL 8.4.6 및 Redis 7.4.5 Compose health: `healthy`
- 앱 전체 health·liveness·readiness: 모두 `UP`
- ID 100 조회: HTTP 200 `MISS_FALLBACK` → HTTP 200 `HIT`
- ID 103 상태 변경: HTTP 200 `AUTH`, version 1 → 조회 `HIT`
- Redis JSON과 MySQL 원본: ID 103 `AUTH:1` 일치
- Redis 남은 TTL: 최종 확인 시 120초
- MySQL `payments`: 100,000건 유지
- Prometheus: cache access hit 2·miss 1, DB read miss 1, cache write success 2, transition success 1
- 조회 API timer count 3, DB read timer count 1 확인
- 위 수치는 소수 기능 검증 요청의 counter이며 부하 테스트·성능 개선 수치가 아니다.
- 최종 검증 스크립트: `STAGE3_RUNTIME_VERIFICATION=PASS`, `STAGE3_CLEANUP=PASS`
- 검증용 앱 종료, 임시 컨테이너·네트워크·볼륨 제거 확인. 기존 프로젝트 volume은 삭제하지 않았다.

## 3단계 문제와 제한사항

- 코드 검토에서 캐시 hit에도 적용되던 서비스 전체 transaction을 발견해 DB 접근 범위로 축소했다. 외부 transaction rollback을 성공으로 기록하지 않도록 지표 기록 시점도 보완했다.
- 실제 MySQL 8.4.6 기동 시 기존 Flyway 지원범위 경고는 남아 있다. migration·JPA validation·API 테스트는 모두 통과했다.
- timeout/connection error 결과 분류와 fallback은 단위 테스트로 확인했고, Lettuce `100ms` 설정은 실제 client configuration으로 확인했다. Toxiproxy 지연·Redis 중단으로 발생시킨 실제 장애 fallback은 아직 검증하지 않았다.
- Redis 쓰기 실패 결과가 listener 호출자에 전파되지 않는 것은 단위 테스트 범위다. 실제 Redis read/write 실패, 중단 중 상태 변경 API, Redis·DB 동시 실패는 4단계에서 검증한다.
- DB와 Redis는 원자적 transaction이 아니므로 쓰기 실패 시 stale 값이 남거나 동시 조회의 오래된 DB 결과가 최신 캐시를 덮어쓸 수 있다. TTL은 개별 값의 수명을 제한하지만 저장 시 갱신되므로 강한 일관성을 보장하지 않는다.
- 성능 측정·개선율은 산출하지 않았다. 유료 API·PG 연동·결제 작업은 수행하지 않았다.

## 3단계 주요 생성·수정 파일

- `.env.example`, `src/main/resources/application.yml`
- `src/main/java/dev/paymentlab/config/PaymentStatusCacheProperties.java`
- `src/main/java/dev/paymentlab/payment/PaymentStatusService.java`
- `src/main/java/dev/paymentlab/payment/PaymentStatusQueryResult.java`
- `src/main/java/dev/paymentlab/payment/PaymentStatusMetrics.java`
- `src/main/java/dev/paymentlab/payment/cache/*`
- `src/main/java/dev/paymentlab/payment/api/PaymentStatusController.java`
- `src/test/java/dev/paymentlab/payment/PaymentStatusServiceTest.java`
- `src/test/java/dev/paymentlab/payment/cache/*`
- `src/test/java/dev/paymentlab/InfrastructureSmokeTest.java`
- `README.md`, `PROGRESS.md`

## 4단계 구현 항목

- 실제 MySQL·Redis·Toxiproxy Testcontainers 및 RANDOM_PORT HTTP 서버 장애 통합 테스트
- 300ms Redis 응답 지연과 실제 Lettuce 100ms command timeout 검증
- 신규 Redis 연결 거부, 실제 Redis ACL GET/SET 권한 오류, 재저장 실패 검증
- commit 후 SET 실패에도 PATCH 성공 및 stale 캐시 보존 확인
- Redis 실제 stop/start, 앱 프로세스 유지, liveness·readiness 격리, miss→hit 복구 검증
- Redis·DB 동시 장애 GET/PATCH 503 및 DB만 장애인 경우 Redis hit 검증
- 두 실제 HTTP transaction의 동일 version 읽기·동시 변경·한 건 commit/한 건 409 검증
- DB transaction 생성 실패의 HTTP 500을 공통 HTTP 503으로 수정
- Lettuce 기존 client options를 복사해 단절 중 새 명령 거부, 자동 재연결·timeout 보존
- 고유 임시 Compose 프로젝트와 런타임 비밀번호를 사용하는 `scripts/verify-stage4.ps1`
- Toxiproxy 공개 포트 환경변수 및 MySQL·Redis·Toxiproxy 공개 포트의 loopback 바인딩

## 4단계 검증 중 발견한 문제와 수정

- 첫 장애 테스트 실행은 8개 모두 context 초기화에서 실패했다. 검증용 MySQL socket timeout 500ms가 100,000건 seed migration을 중단시킨 `Read timed out` 로그를 확인했다. 검증용 socket timeout을 10초, 연결 timeout을 1초로 조정했으며 Redis command/connect timeout 100ms는 유지했다.
- 두 번째 실행은 8개 중 6개 통과, 2개 실패였다. 실제 Redis·DB 연결 동시 차단에서 `CannotCreateTransactionException`이 기존 `DataAccessException` 처리 범위 밖이어서 HTTP 500이 반환됐다. 해당 예외만 공통 503 handler에 추가하고 GET/PATCH 회귀 테스트를 추가했다.
- 동시성 테스트의 Spring Data interface spy에서 추상 메서드 `callRealMethod()` 호출이 실패했다. 테스트용 concrete service spy의 transaction 안에서 두 최초 읽기를 barrier로 맞추고 나머지 서비스·repository·MySQL 경로는 실제 실행하도록 수정했다. 운영 코드에 테스트용 지연을 넣지 않았다.
- 위 수정 후 선택 테스트 11개가 `BUILD SUCCESSFUL in 2m 11s`로 통과했다.
- Redis 실제 중단 중 기본 Lettuce가 새 명령을 대기시켜 반복 timeout으로 분류되는 현상을 확인했다. 단절 감지 이후의 새 명령은 `REJECT_COMMANDS`로 즉시 거부하도록 보완했다. 이미 진행 중인 명령의 timeout 가능성과 자동 재연결은 유지한다.
- 초기 기동 중 Redis handshake가 100ms를 넘길 수 있어 테스트 준비 단계는 정상 연결을 제한 시간 안에서 재확인한다. 운영 timeout 값을 완화하지 않는다.
- 전체 테스트 종료 시 Hikari connection executor 종료 대기 경고가 1회 출력됐으나 이후 pool 종료 완료와 테스트 프로세스 종료를 확인했다. 테스트 로그에 기록된 컨테이너 9개는 모두 제거됐다. 다른 작업의 Testcontainers는 정리 대상으로 삼지 않았다.
- 별도 boot jar 검증의 첫 두 실행은 Redis 중단 뒤 Actuator 상태 검증에서 중단됐다. PowerShell의 Actuator 전용 JSON 응답이 바이트 배열로 반환되는 경우를 문자열과 동일하게 처리한 것이 원인이었다. 응답 바이트를 UTF-8로 해석하도록 스크립트를 보완했다. 두 실행 모두 `finally` 정리를 완료했다.

## 4단계 실제 실행 명령과 테스트 결과

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path -LiteralPath '..\.gradle-user-home').Path
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + (Resolve-Path -LiteralPath '..\.tmp').Path
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
.\gradlew.bat --no-daemon test --tests dev.paymentlab.payment.cache.PaymentStatusFailureIntegrationTest --max-workers=1 --console=plain
.\gradlew.bat --no-daemon test --tests dev.paymentlab.payment.cache.PaymentStatusFailureIntegrationTest --tests dev.paymentlab.payment.api.PaymentAvailabilityApiTest --max-workers=1 --console=plain
.\gradlew.bat --no-daemon test bootJar --rerun-tasks --max-workers=1 --console=plain
& '.\scripts\verify-stage4.ps1'
git diff --check
```

- 최종 전체 테스트와 bootJar: `BUILD SUCCESSFUL in 3m 24s`, 6개 작업 모두 실행
- XML 집계: **52개 실행, 성공 52, 실패 0, errors 0, skipped 0**
- 기존 테스트 40개 전부 통과
- 실제 장애·복구·동시성 통합 테스트 10개 통과
- transaction 시작 실패 HTTP 503 회귀 테스트 2개 통과
- 최종 실제 지연 테스트: Redis downstream 300ms, Lettuce command timeout 100ms, HTTP 256ms, `TIMEOUT_FALLBACK` 200
- 100ms command/connect timeout, 자동 재연결, 단절 중 새 명령 거부 옵션을 실제 client configuration에서 확인
- 신규 연결 거부는 테스트에서 공유 연결을 초기화한 후 proxy listener를 차단한다. Redis 실제 중단·복구 테스트에서는 연결 초기화나 앱 재시작 없이 자동 복구를 확인한다.
- 동시 HTTP 변경 결과: HTTP 200 1개, `PAYMENT_STATUS_CONFLICT` HTTP 409 1개, DB version 1, cache write success 1회
- 검증용 DB pool 대기는 700ms, DB 연결 timeout은 1초, socket timeout은 10초다. 운영 DB 설정은 변경하지 않았다.
- 전체 테스트 이후 운영·테스트 Java 코드는 변경하지 않았다. 이후 수정은 실기동 스크립트·Compose 바인딩·문서에 한정했다.

## 4단계 최종 실제 실행 상태

- 최종 스크립트 종료 코드 0: `STAGE4_RUNTIME_VERIFICATION=PASS`, `STAGE4_CLEANUP=PASS`
- 검증 project: `payment-status-cache-lab-stage4-cb249afb`, 앱 PID `16504`
- MySQL 8.4.6·Redis 7.4.5·Toxiproxy 2.12.0 모두 Compose `healthy`
- 시작 시 앱 전체 health UP, Redis PING 성공, 정상 miss→hit 확인
- Toxiproxy 300ms downstream 지연: HTTP 237ms, 200 `TIMEOUT_FALLBACK`
- 실제 Redis GET 거부: 200 `ERROR_FALLBACK`, DB 결과 재저장 후 GET 권한 복구 시 HIT
- 실제 Redis SET 거부: miss 조회 정상 응답, 새 캐시 키 미생성
- SET 거부 중 PATCH: 200 AUTH/version 1, DB commit 성공, 기존 캐시 READY HIT가 남는 stale 상태 확인
- Redis stop 중: 200 `ERROR_FALLBACK`, PATCH AUTH 성공, liveness/readiness UP, 전체 health DOWN
- Redis start 후 앱 PID 유지: 이전에 캐시되지 않은 ID 115가 AUTH `MISS_FALLBACK` → `HIT`
- MySQL 원본 ID 109·115 모두 AUTH/version 1, 전체 100,000건
- Redis 연결 차단 및 MySQL stop: 503 `PAYMENT_STATUS_UNAVAILABLE`, liveness UP, readiness DOWN
- 최종 기능 검증 지표: cache access error 3·hit 4·miss 4·timeout 1, cache write error 5·success 4
- DB read reason miss 4·redis_error 3·timeout 1, READY→AUTH transition success 2
- 위 지표에는 실패한 DB 조회 시도도 포함되며 기능 검증 요청의 counter일 뿐 QPS·성능 측정 결과가 아니다.
- 검증용 앱 종료, 임시 컨테이너·네트워크·볼륨 제거 확인. 기존 프로젝트 볼륨은 보존했다.
- 앱 로그는 Git 제외 `build/payment-status-cache-lab-stage4-cb249afb.stdout.log`·`.stderr.log`에 보존한다.

## 4단계 주요 생성·수정 파일

- `src/main/java/dev/paymentlab/common/GlobalExceptionHandler.java`
- `src/main/java/dev/paymentlab/config/RedisClientConfiguration.java`
- `src/test/java/dev/paymentlab/payment/api/PaymentAvailabilityApiTest.java`
- `src/test/java/dev/paymentlab/payment/cache/PaymentStatusFailureIntegrationTest.java`
- `scripts/verify-stage4.ps1`
- `docker-compose.yml`, `.env.example`, `README.md`, `PROGRESS.md`

## PR 이력

- 원격 `kiy3035/payment-status-cache-lab`의 초기 `main`에는 `.gitattributes`만 존재했다.
- 1단계 PR #1은 `main`에 병합됐다.
- 2단계 PR #2는 `main`에 병합됐다. 병합 기준 commit은 `d486245`다.
- 3단계 PR [#3](https://github.com/kiy3035/payment-status-cache-lab/pull/3)은 사용자 요청에 따라 `main`에 병합했다. 병합 commit은 `ca63634`다.
- PR 작성자와 실행 계정이 같으므로 별도 자기 승인 리뷰는 하지 않았다. 보호 규칙 우회 없이 일반 merge로 처리했다.
- 4단계는 병합된 `main` 기준 `codex/stage-4-failure-recovery` branch에서 진행한다.

## 미완료 작업과 제한사항

- 5단계 100 RPS·3회 반복 성능 비교와 원시 결과 수집은 미착수다. DB-only k6 골격 외의 측정 자동화는 아직 없다.
- 6단계 RESULTS·BLOG_DRAFT와 성능 결과 기반 최종 문서화는 미착수다.
- DB·Redis는 원자적 transaction이 아니다. SET 실패 시 stale 값, 늦게 도착한 DB 조회 결과에 의한 덮어쓰기, timeout 뒤 서버 쓰기 성공 가능성은 남는다.
- 100ms는 Redis 명령당 제한이다. 읽기 timeout·DB fallback·쓰기 timeout이 누적될 수 있으며 HTTP 전체 100ms 보장은 아니다.
- 단절 감지 전 진행 중이던 명령은 timeout으로 분류될 수 있다. 단절 감지 이후 새 명령은 오류로 거부하고 자동 재연결한다.
- 복구 시 기존 모든 키를 삭제하지 않는다. miss→hit 검증은 장애 중 캐시되지 않은 ID를 사용하며 기존 stale 캐시가 자동으로 모두 최신화된다는 뜻이 아니다.
- 기존 Flyway MySQL 8.4 지원범위 경고는 남지만 실제 migration·JPA validation·전체 테스트는 통과했다.
- 무료 로컬 도구만 사용했다. 유료 API·서비스·PG사 연동·실제 결제는 수행하지 않았다.

## 재현 명령

처음부터 재현하는 상세 절차는 `README.md`를 따른다. 핵심 순서는 다음과 같다.

```powershell
Copy-Item .env.example .env
# .env의 로컬 비밀번호를 변경하고 현재 PowerShell 프로세스에 불러온다.
.\gradlew.bat test
docker compose config --quiet
docker compose up -d --wait mysql redis
$env:PAYMENT_STATUS_CACHE_ENABLED = 'true'
.\gradlew.bat bootRun
```

## 다음 단계 범위

5단계에서는 동일 조건 100 RPS의 DB-only·Redis 정상·중단·timeout·복구 측정, DB QPS·CPU 자동 수집, 정상 비교 3회 반복·중앙값, JSON·CSV 원시 결과와 비교표 생성을 구현한다. 사용자 요청 전에는 착수하지 않는다.

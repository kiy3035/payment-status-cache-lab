# 진행 상황

기준일: 2026-09-03

## 단계 상태

- 1단계 — 프로젝트와 로컬 실행 환경: 완료
- 2단계 — 상태 전이와 DB-only API: 완료
- 3~6단계: 미착수

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

## PR

- 원격 `kiy3035/payment-status-cache-lab`의 초기 `main`에는 `.gitattributes`만 존재했다.
- 1단계 PR #1은 `main`에 병합됐다.
- 2단계 변경은 `codex/stage-2-db-only-api` branch로 분리했다.
- 2단계 PR 본문은 상태 전이·DB-only API 작업과 적용 전후 비교를 중심으로 작성한다.

## 미완료 작업과 제한사항

- Redis 장애 시 liveness 유지의 실제 장애 주입은 4단계 범위다. 1단계에서는 Redis를 liveness group에서 제외한 설정 구조와 정상 기동 상태만 확인했다.
- Toxiproxy proxy/toxic 생성과 지연 주입은 4단계 범위이므로 수행하지 않았다.
- DB-only k6 골격만 생성했으며 성능 측정과 결과 수집은 5단계 범위이므로 실행하지 않았다.

## 재현 명령

처음부터 재현하는 상세 절차는 `README.md`를 따른다. 핵심 순서는 다음과 같다.

```powershell
Copy-Item .env.example .env
# .env의 로컬 비밀번호를 변경하고 현재 PowerShell 프로세스에 불러온다.
.\gradlew.bat test
docker compose config --quiet
docker compose up -d --wait
.\gradlew.bat bootRun
```

## 다음 단계 범위

3단계에서는 Redis cache adapter, hit·miss, TTL, DB fallback, 100ms Lettuce command timeout, AFTER_COMMIT 동기화, cache metric과 관련 통합 테스트를 구현한다. 사용자 요청 전에는 착수하지 않는다.

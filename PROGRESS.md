# 진행 상황

기준일: 2026-09-01

## 단계 상태

- 1단계 — 프로젝트와 로컬 실행 환경: 완료
- 2단계 — 상태 전이와 DB-only API: 미착수
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

## 주요 설계 결정

- Spring Boot BOM은 별도 dependency-management 플러그인을 추가하지 않고 Gradle 내장 `platform`으로 적용했다.
- Redis 운영 command/connect timeout 기본값은 요구사항대로 100ms다. 1단계 smoke test는 장애 timeout 측정이 아니라 실제 연결 확인이 목적이므로 최초 handshake 환경 편차를 흡수하도록 Spring Redis 테스트 timeout만 2초로 오버라이드한다.
- `liveness` health group에는 `livenessState`만 포함하고, `readiness`에는 `readinessState`, DB, Redis를 포함해 Redis 상태가 애플리케이션 생존 판정을 내리지 않게 했다.
- seed는 0~9 derived table 다섯 개의 cross join으로 정확히 100,000개 번호를 만들며 ID, 상태, 시각을 항상 같은 규칙으로 생성한다.
- Toxiproxy 2.12.0 공식 컨테이너는 Docker Hub가 아닌 GitHub Container Registry에서 제공되므로 `ghcr.io/shopify/toxiproxy:2.12.0`을 사용한다.
- Toxiproxy proxy port `26379`는 4단계 장애 주입 기반으로만 노출하며 현재 proxy 생성은 하지 않는다.

## 실제 실행한 검증 명령

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

## 테스트 결과

- 최종 명령: `.\gradlew.bat --no-daemon test --console=plain`
- 결과: 성공
- 테스트: 4개 실행, 4개 성공, 실패 0, skipped 0
- 실제 MySQL 컨테이너 기동과 Spring context: 성공
- Flyway migration 2개와 JPA `validate` context 기동: 성공
- deterministic `payments` 100,000건, 최소 ID 1, 최대 ID 100,000: 성공
- 실제 Redis 컨테이너 연결, PING, SET/GET round-trip: 성공
- 테스트 종료 후 Testcontainers 리소스와 Java 프로세스 종료: 확인

## 실제 실행 상태

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

## 발생한 오류와 해결

- 제한된 실행 환경의 기본 Gradle cache/temp 경로에서 native library 초기화가 실패했다. 프로젝트 내부 `.gradle-user-home`, `.tmp`를 사용해 wrapper와 빌드를 검증했다.
- 첫 의존성 해석에서 starter 버전이 비어 실패했다. Spring Boot 3.5.16 BOM을 Gradle `platform`으로 명시해 해결했다.
- 첫 전체 테스트에서 Redis 최초 handshake가 운영 기본 timeout 100ms를 넘겨 4개 중 1개가 실패했다. 운영 설정은 유지하고 smoke test의 Spring Redis timeout만 2초로 분리한 뒤 4개 전체 통과를 확인했다.
- `shopify/toxiproxy:2.12.0`은 Docker Hub에 존재하지 않았다. 공식 배포 위치인 `ghcr.io/shopify/toxiproxy:2.12.0`으로 변경하고 실제 pull과 기동을 확인했다.
- 공식 Toxiproxy 2.12.0 이미지의 binary 경로가 `/toxiproxy`인데 초기 healthcheck가 `/toxiproxy-server`를 사용해 unhealthy가 됐다. 실제 entrypoint와 version 명령을 확인해 경로를 수정했다.
- 호스트 `3306` 포트가 이미 사용 중이라 첫 Compose MySQL 기동이 실패했다. 외부 리소스를 변경하지 않고 검증용 `MYSQL_PORT=13306`으로 재기동했다.
- Toxiproxy 2.12.0 HTTP API가 PowerShell 기본 User-Agent를 거부했다. `toxiproxy-cli/2.12.0` User-Agent로 `/version` 200과 버전 응답을 확인했다.

## 주요 생성·수정 파일

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

## PR

- 원격 `kiy3035/payment-status-cache-lab`의 초기 `main`에는 `.gitattributes`만 존재했다.
- 1단계 변경은 `codex/stage-1-local-environment` branch로 분리했다.
- PR 본문은 구현 항목과 1단계 적용 전후 비교를 중심으로 작성한다.

## 미완료 작업과 제한사항

- Redis 장애 시 liveness 유지의 실제 장애 주입은 4단계 범위다. 1단계에서는 Redis를 liveness group에서 제외한 설정 구조와 정상 기동 상태만 확인했다.
- Toxiproxy proxy/toxic 생성과 지연 주입은 4단계 범위이므로 수행하지 않았다.
- 성능 수치와 k6 스크립트는 5단계 범위이므로 생성하거나 실행하지 않았다.

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

2단계에서는 JPA Entity·Repository, `READY → AUTH → APPROVED` 상태 머신, `@Version` optimistic locking, DB-only 조회·변경 API, 공통 오류 응답, 단위·MySQL 통합 테스트를 구현한다. 사용자 요청 전에는 착수하지 않는다.

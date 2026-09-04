# payment-status-cache-lab

결제 상태 조회 실험을 위한 로컬 백엔드 프로젝트다. **4단계 Redis 장애·복구 검증까지 완료**했다. Redis cache-aside 조회·DB commit 이후 캐시 동기화와 실제 장애 재현 코드를 제공한다. 실행 결과는 `PROGRESS.md`에 기록한다. 성능 측정은 아직 수행하지 않았다.

## 요구 환경

- Java 21
- Docker Desktop 또는 Docker Engine
- Docker Compose
- 프로젝트에 포함된 Gradle Wrapper 8.12.1
- 자동 실기동 검증 스크립트를 사용할 경우 PowerShell 7 이상

MySQL, Redis, Gradle을 전역 설치할 필요는 없다.

## 환경변수 준비

PowerShell에서 예시 파일을 복사하고 `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`를 로컬 전용 값으로 변경한다.

```powershell
Copy-Item .env.example .env
```

기본 포트가 이미 사용 중이면 `MYSQL_PORT`, `REDIS_PORT`, `SERVER_PORT`를 사용 가능한 값으로 바꾼다. Toxiproxy 포트도 `TOXIPROXY_API_PORT`, `TOXIPROXY_REDIS_PORT`로 변경할 수 있다. Compose의 MySQL·Redis·Toxiproxy 포트는 로컬 loopback에만 공개한다. `.env`는 Git 제외 대상이다.

Docker Compose는 `.env`를 자동으로 읽는다. Spring Boot 실행 전에는 다음 명령으로 `.env`를 현재 PowerShell 프로세스에 불러온다.

```powershell
Get-Content .env |
    Where-Object { $_ -match '^[^#][^=]*=' } |
    ForEach-Object {
        $name, $value = $_.Split('=', 2)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
```

기본값 `PAYMENT_STATUS_CACHE_ENABLED=false`는 DB-only 모드다. 아래 절차에서 환경변수만 바꾸고 앱을 재시작해 Redis 모드로 전환한다. 키 prefix, TTL, command/connect timeout도 `.env.example`의 설정으로 변경할 수 있다.

기존 MySQL volume을 사용한다면 최초 생성 시의 비밀번호를 유지해야 한다. `.env` 값만 바꿔도 이미 생성된 DB 계정 비밀번호가 바뀌지는 않는다. 기존 데이터가 필요하면 volume을 삭제하지 말고 올바른 자격정보를 사용한다.

## 전체 테스트

Docker가 실행 중인 상태에서 실행한다.

```powershell
.\gradlew.bat test
```

테스트는 실제 MySQL 8.4.6과 Redis 7.4.5 Testcontainers를 사용한다. 현재 전체 테스트에는 상태 전이 단위 테스트, DB-only API, Flyway/JPA validation, rollback, optimistic locking 충돌, 공통 오류 응답과 인프라 smoke test가 포함된다.

캐시 테스트는 miss→JSON/TTL 저장→hit, hit에서 repository 미호출, commit 전 미갱신·commit 후 갱신, 외부 transaction rollback 시 캐시 보존, Lettuce command timeout 설정을 확인한다. timeout/연결 오류 분기 단위 테스트는 실제 네트워크 장애 검증을 대신하지 않는다.

4단계 통합 테스트는 MySQL·Redis·Toxiproxy 2.12.0 컨테이너와 실제 HTTP 서버를 사용한다. Toxiproxy 300ms 지연, 연결 차단, Redis ACL 기반 GET/SET 거부, Redis 실제 stop/start, Redis·DB 동시 연결 차단, liveness·readiness, 실제 동시 HTTP 변경을 검증한다. 동시성 테스트는 테스트용 service spy에서 두 transaction의 최초 읽기만 barrier로 동기화하며 DB 갱신·commit·충돌은 실제 MySQL에서 처리한다. 운영 코드에는 테스트용 대기나 분기가 없다.

장애 테스트만 실행하려면 다음을 사용한다.

```powershell
.\gradlew.bat test --tests dev.paymentlab.payment.cache.PaymentStatusFailureIntegrationTest --rerun-tasks --max-workers=1
```

## Redis 인프라 연결 확인

다음 명령은 Redis 컨테이너 자체의 연결만 확인한다. 애플리케이션의 캐시 경로는 아래 Redis 모드 절차에서 별도로 검증한다.

```powershell
docker compose up -d --wait redis
docker compose exec -T redis redis-cli PING
docker compose exec -T redis redis-cli SET stage2:connection-check connected EX 30
docker compose exec -T redis redis-cli GET stage2:connection-check
docker compose exec -T redis redis-cli DEL stage2:connection-check
docker compose stop redis
```

정상 결과는 차례로 `PONG`, `OK`, `connected`, `1`이다. 마지막에 Redis를 정지해 다음 DB-only 실행 조건을 만든다.

## DB-only 실행

DB-only 모드는 Redis 없이 동작한다. Compose 설정을 확인하고 MySQL만 기동한다.

```powershell
docker compose config --quiet
docker compose up -d --wait mysql
docker compose ps
```

환경변수를 불러온 PowerShell에서 애플리케이션을 실행한다.

```powershell
.\gradlew.bat bootRun
```

다른 PowerShell에서 health를 확인한다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/livez
```

cache disabled 모드에서는 Redis health indicator와 Redis repository 탐색을 비활성화한다. MySQL만 정상이라면 전체 health와 readiness는 `UP`이다.

## Redis 모드 실행과 miss·hit 확인

실행 중인 앱을 `Ctrl+C`로 종료한 뒤 환경변수를 불러온 PowerShell에서 다음을 실행한다.

```powershell
$env:PAYMENT_STATUS_CACHE_ENABLED = 'true'
docker compose up -d --wait mysql redis
.\gradlew.bat bootRun
```

같은 `.env`를 불러온 다른 PowerShell에서 실행한다. `SERVER_PORT`를 바꿨다면 아래 base URL에도 적용된다.

```powershell
$baseUrl = "http://localhost:$env:SERVER_PORT"
$cacheKey = $env:PAYMENT_STATUS_CACHE_KEY_PREFIX + '1'
docker compose exec -T redis redis-cli DEL $cacheKey
$first = Invoke-WebRequest "$baseUrl/api/v1/payments/1/status"
$first.Headers['X-Cache-Result']
$second = Invoke-WebRequest "$baseUrl/api/v1/payments/1/status"
$second.Headers['X-Cache-Result']
docker compose exec -T redis redis-cli GET $cacheKey
docker compose exec -T redis redis-cli TTL $cacheKey
```

첫 조회는 `MISS_FALLBACK`, 두 번째는 `HIT`이다. Redis에는 `paymentId`, `status`, `version`, `updatedAt`을 가진 JSON이 저장되며 TTL은 기본 300초 이하의 양수다. Redis 모드의 전체 health에는 Redis 상태가 포함되고 liveness·readiness는 각각 애플리케이션 생존과 DB 준비 상태를 확인한다.

## 결제 상태 조회 API

```powershell
Invoke-WebRequest http://localhost:8080/api/v1/payments/1/status
```

정상 응답은 `paymentId`, `status`, `version`, `updatedAt`을 포함한다. `X-Cache-Result`는 모드·조회 경로에 따라 `DISABLED`, `HIT`, `MISS_FALLBACK`, `TIMEOUT_FALLBACK`, `ERROR_FALLBACK`이다.

## 결제 상태 변경 API

```powershell
Invoke-RestMethod `
    -Uri http://localhost:8080/api/v1/payments/1/status `
    -Method Patch `
    -ContentType 'application/json' `
    -Body '{"targetStatus":"AUTH"}'
```

허용하는 전이는 `READY → AUTH → APPROVED`뿐이다. 잘못된 전이는 HTTP 409와 `INVALID_PAYMENT_STATUS_TRANSITION`, optimistic locking 충돌은 HTTP 409와 `PAYMENT_STATUS_CONFLICT`를 반환한다. 존재하지 않는 결제는 HTTP 404, 정의되지 않은 상태는 HTTP 400이다.

Redis 모드에서는 DB commit 이후 캐시를 갱신한다. PATCH가 성공한 다음 GET은 갱신된 상태를 `HIT`으로 반환한다. rollback하면 캐시를 변경하지 않는다. 같은 예제를 반복하면 이미 AUTH인 결제에 AUTH를 요청해 409가 발생하므로 현재 상태를 확인하거나 다른 READY 결제를 사용한다.

## 지표 확인

```powershell
(Invoke-WebRequest http://localhost:8080/actuator/prometheus).Content
```

요청을 실행하면 다음 지표가 노출된다. label에는 결제 ID를 포함하지 않는다.

- `payment_status_cache_access_total{result="hit|miss|timeout|error|disabled"}`
- `payment_status_db_read_total{reason="cache_disabled|miss|timeout|redis_error"}`
- `payment_status_cache_write_total{result="success|error"}`
- `payment_status_transition_total{from="...",to="...",result="success|failure"}`
- `payment_status_api_read_seconds`, `payment_status_db_read_duration_seconds`

counter는 사용한 조합부터 나타난다. 상태 변경 성공·실패는 transaction 완료 결과로 기록한다.

## 캐시 정합성 한계

MySQL과 Redis는 하나의 원자적 transaction이 아니다. commit 후 Redis 쓰기만 실패하면 이전 캐시가 TTL 동안 남을 수 있다. 동시 조회가 읽은 과거 DB 값을 나중에 저장해 최신 캐시를 덮어쓰는 경쟁도 가능하다. TTL은 각 캐시 값의 수명을 제한할 뿐 강한 일관성을 보장하지 않으며 저장 시 갱신된다. Outbox·CDC·versioned key는 이번 구현에 추가하지 않았다.

Redis timeout은 서버에서 명령이 전혀 실행되지 않았다는 뜻이 아니다. 특히 SET 응답만 지연되면 서버 쓰기는 성공했어도 클라이언트에는 timeout으로 보일 수 있다. 캐시 쓰기 error metric을 서버 쓰기 미실행 건수로 해석하지 않는다.

## 실제 장애·복구 일괄 검증

기존 앱을 종료하거나 기존 DB를 비울 필요 없이 별도의 PowerShell 7 프로세스에서 실행한다.

```powershell
.\gradlew.bat bootJar
pwsh -NoProfile -File .\scripts\verify-stage4.ps1
```

스크립트는 임의의 고유 Compose 프로젝트와 빈 MySQL·Redis 볼륨을 생성하고, 런타임에 비밀번호와 사용 가능한 포트를 정한다. `.env`를 수정하지 않고 프로세스 환경변수는 종료 시 복원한다. 포트 선택과 바인딩 사이에 다른 프로세스가 포트를 점유하면 실패할 수 있으므로 정리 결과를 확인한 뒤 재실행한다.

실행 순서는 다음과 같다.

1. MySQL·Redis·Toxiproxy health, 앱 health, Redis PING 및 miss→hit 확인
2. 이미 연결된 Redis 응답에 300ms 지연을 주입하고 100ms command timeout fallback 확인
3. 실제 GET 거부와 SET 거부에서 정상 DB 응답, commit 성공 및 남은 stale 캐시 확인
4. Redis stop 중 조회·변경 성공, liveness·readiness UP, 전체 health DOWN 확인
5. 앱 재시작 없이 Redis start 후 이전에 캐시되지 않은 ID의 miss→hit 복구 확인
6. MySQL 상태·version과 합성 데이터 100,000건 확인
7. Redis 연결 차단과 MySQL stop 중 GET 503, liveness UP, readiness DOWN 확인
8. 지표 출력 후 검증용 앱·컨테이너·네트워크·볼륨 정리

성공하면 `STAGE4_RUNTIME_VERIFICATION=PASS`와 `STAGE4_CLEANUP=PASS`가 출력된다. 앱 로그는 Git 제외 `build/payment-status-cache-lab-stage4-*.log`에 남는다. 실패하더라도 `finally`에서 이 실행이 만든 리소스만 정리하며 기존 프로젝트 볼륨은 건드리지 않는다. 동시 HTTP 충돌과 신규 연결 거부는 앞의 통합 테스트에서 별도로 검증한다.

Lettuce는 자동 재연결을 유지하되 연결 단절을 감지한 뒤의 새 명령은 큐에 쌓지 않고 거부한다. 연결 단절 감지 이전에 진행 중이던 명령은 timeout으로 끝날 수 있다. 관련 설정의 의미는 [Lettuce 공식 Client Options](https://github.com/redis/lettuce/wiki/Client-Options), 장애 주입 API는 [Toxiproxy 2.12.0 공식 문서](https://github.com/Shopify/toxiproxy/blob/v2.12.0/README.md)를 따른다.

100ms는 Redis **명령당** 제한이며 HTTP 전체 응답시간 제한이 아니다. GET timeout 후 DB 조회와 best-effort SET timeout이 각각 더해질 수 있다. 지연 검증의 HTTP 허용 범위는 80~900ms이며 처리량·성능 개선 측정이 아니다. 이 검증에서만 DB pool 대기를 700ms, 연결 timeout을 1초, socket timeout을 10초로 지정하며 운영 기본 DB 설정은 변경하지 않는다.

## MySQL migration과 데이터 확인

```powershell
docker compose exec `
    -e MYSQL_PWD="$env:MYSQL_PASSWORD" `
    mysql mysql `
    -u"$env:MYSQL_USER" `
    -D"$env:MYSQL_DATABASE" `
    -Nse "SELECT VERSION(); SELECT COUNT(*), MIN(id), MAX(id) FROM payments; SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
```

초기 정상 결과는 결제 100,000건, ID 1~100,000, 성공한 Flyway migration `v1`, `v2`다.

## DB-only k6 골격

`k6/db-only.js`에 100 RPS `constant-arrival-rate` 골격이 있다. 실제 성능 측정과 결과 수집은 5단계 범위이므로 아직 실행 결과를 제공하지 않는다.

## 종료

포그라운드 애플리케이션은 `Ctrl+C`로 종료하고 Compose 리소스는 다음 명령으로 종료한다. named volume은 보존된다.

```powershell
docker compose down
```

로컬 실험 데이터를 함께 삭제해야 할 때만 이 프로젝트 루트에서 다음 명령을 사용한다.

```powershell
docker compose down --volumes
```

## 현재 범위와 다음 단계

4단계 장애·복구 검증의 실제 결과는 `PROGRESS.md`를 참고한다. 5단계 100 RPS 성능 측정·수집 자동화와 6단계 최종 결과 문서는 아직 수행하지 않았다. 사용자 요청 전에는 다음 단계를 시작하지 않는다.

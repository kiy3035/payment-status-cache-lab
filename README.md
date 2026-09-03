# payment-status-cache-lab

결제 상태 조회 실험을 위한 로컬 백엔드 프로젝트다. 현재 **2단계 DB-only 조회·상태 변경 API까지 구현**되어 있다. Redis cache-aside 조회와 DB commit 이후 Redis 동기화는 아직 구현하지 않았다.

## 요구 환경

- Java 21
- Docker Desktop 또는 Docker Engine
- Docker Compose
- 프로젝트에 포함된 Gradle Wrapper 8.12.1

MySQL, Redis, Gradle을 전역 설치할 필요는 없다.

## 환경변수 준비

PowerShell에서 예시 파일을 복사하고 `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`를 로컬 전용 값으로 변경한다.

```powershell
Copy-Item .env.example .env
```

기본 포트가 이미 사용 중이면 `MYSQL_PORT`, `REDIS_PORT`, `SERVER_PORT`를 사용 가능한 값으로 바꾼다. `.env`는 Git 제외 대상이다.

Docker Compose는 `.env`를 자동으로 읽는다. Spring Boot 실행 전에는 다음 명령으로 `.env`를 현재 PowerShell 프로세스에 불러온다.

```powershell
Get-Content .env |
    Where-Object { $_ -match '^[^#][^=]*=' } |
    ForEach-Object {
        $name, $value = $_.Split('=', 2)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
```

2단계 기본값인 `PAYMENT_STATUS_CACHE_ENABLED=false`를 유지한다.

## 전체 테스트

Docker가 실행 중인 상태에서 실행한다.

```powershell
.\gradlew.bat test
```

테스트는 실제 MySQL 8.4.6과 Redis 7.4.5 Testcontainers를 사용한다. 현재 전체 테스트에는 상태 전이 단위 테스트, DB-only API, Flyway/JPA validation, rollback, optimistic locking 충돌, 공통 오류 응답과 인프라 smoke test가 포함된다.

## Redis 인프라 연결 확인

다음 명령은 Redis 컨테이너 자체의 연결만 확인한다. 2단계 애플리케이션에는 아직 Redis cache adapter가 없으므로 캐시 기능 검증을 의미하지 않는다.

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

2단계 애플리케이션은 Redis 없이 동작한다. Compose 설정을 확인하고 MySQL만 기동한다.

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

## 결제 상태 조회 API

```powershell
Invoke-WebRequest http://localhost:8080/api/v1/payments/1/status
```

정상 응답은 `paymentId`, `status`, `version`, `updatedAt`을 포함하고 `X-Cache-Result: DISABLED` 헤더를 반환한다.

## 결제 상태 변경 API

```powershell
Invoke-RestMethod `
    -Uri http://localhost:8080/api/v1/payments/1/status `
    -Method Patch `
    -ContentType 'application/json' `
    -Body '{"targetStatus":"AUTH"}'
```

허용하는 전이는 `READY → AUTH → APPROVED`뿐이다. 잘못된 전이는 HTTP 409와 `INVALID_PAYMENT_STATUS_TRANSITION`, optimistic locking 충돌은 HTTP 409와 `PAYMENT_STATUS_CONFLICT`를 반환한다. 존재하지 않는 결제는 HTTP 404, 정의되지 않은 상태는 HTTP 400이다.

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

현재 2단계까지 완료됐다. 3단계에서 별도 Redis adapter, hit·miss, TTL, DB fallback, Lettuce timeout, AFTER_COMMIT 동기화와 관련 metric·통합 테스트를 구현한다. 사용자 요청 전에는 3단계를 시작하지 않는다.

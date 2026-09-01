# payment-status-cache-lab

결제 상태 조회 실험을 위한 로컬 백엔드 프로젝트다. 현재는 **1단계 로컬 실행 기반만 구현**되어 있으며, 결제 조회·상태 변경 API와 Redis cache-aside 비즈니스 로직은 아직 구현하지 않았다.

## 요구 환경

- Java 21
- Docker Desktop 또는 Docker Engine
- Docker Compose
- 프로젝트에 포함된 Gradle Wrapper 8.12.1

MySQL, Redis, Gradle을 전역 설치할 필요는 없다.

## 환경변수 준비

PowerShell에서 예시 파일을 복사한다.

```powershell
Copy-Item .env.example .env
```

`.env`의 `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`를 로컬 전용 값으로 변경한다. 기본 포트가 이미 사용 중이면 `MYSQL_PORT`, `REDIS_PORT`, `SERVER_PORT`도 사용 가능한 값으로 바꾼다. `.env`는 Git 제외 대상이다.

Docker Compose는 `.env`를 자동으로 읽지만, 로컬에서 실행하는 Spring Boot 애플리케이션은 현재 PowerShell 프로세스의 환경변수를 사용한다. 다음 명령으로 `.env`를 현재 프로세스에 불러온다.

```powershell
Get-Content .env |
    Where-Object { $_ -match '^[^#][^=]*=' } |
    ForEach-Object {
        $name, $value = $_.Split('=', 2)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
```

## 전체 테스트

Docker가 실행 중인 상태에서 Testcontainers smoke test를 실행한다.

```powershell
.\gradlew.bat test
```

테스트는 실제 `mysql:8.4.6`, `redis:7.4.5-alpine` 컨테이너를 사용해 Spring context, Flyway migration, 100,000건 seed, Redis round-trip을 확인한다.

## Docker Compose 실행

설정을 검증하고 MySQL, Redis, Toxiproxy를 기동한다.

```powershell
docker compose config --quiet
docker compose up -d --wait
docker compose ps
```

사용 이미지:

- `mysql:8.4.6`
- `redis:7.4.5-alpine`
- `ghcr.io/shopify/toxiproxy:2.12.0`

Toxiproxy는 4단계 장애 주입을 위한 기반만 구성했다. 현재 단계에서는 proxy와 toxic을 생성하지 않는다.

## 애플리케이션 실행과 health 확인

환경변수를 현재 PowerShell 프로세스에 불러온 뒤 애플리케이션을 실행한다.

```powershell
.\gradlew.bat bootRun
```

다른 PowerShell 창에서 다음 endpoint를 확인한다. 기본 애플리케이션 포트는 `8080`이다.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/livez
```

`liveness` 그룹은 애플리케이션 생존 상태만 포함한다. DB와 Redis는 전체 health와 readiness에서 확인한다.

## MySQL migration과 seed 확인

환경변수를 불러온 PowerShell에서 실행한다.

```powershell
docker compose exec `
    -e MYSQL_PWD="$env:MYSQL_PASSWORD" `
    mysql mysql `
    -u"$env:MYSQL_USER" `
    -D"$env:MYSQL_DATABASE" `
    -Nse "SELECT VERSION(); SELECT COUNT(*), MIN(id), MAX(id) FROM payments; SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
```

정상 결과는 MySQL `8.4.x`, 결제 `100000`건, ID 범위 `1`부터 `100000`, 성공한 Flyway migration `v1`, `v2`다.

## Redis 연결과 round-trip 확인

```powershell
docker compose exec redis redis-cli PING
docker compose exec redis redis-cli SET stage1:manual-check connected EX 10
docker compose exec redis redis-cli GET stage1:manual-check
docker compose exec redis redis-cli DEL stage1:manual-check
docker compose exec redis redis-cli INFO server
```

`PING`은 `PONG`을, `GET`은 `connected`를 반환해야 한다.

## 종료

포그라운드에서 실행한 애플리케이션은 `Ctrl+C`로 종료한다. Compose 컨테이너와 네트워크는 다음 명령으로 종료하며, MySQL·Redis named volume은 보존한다.

```powershell
docker compose down
```

로컬 실험 데이터를 함께 삭제해야 할 때만 이 프로젝트 루트에서 다음 명령을 사용한다.

```powershell
docker compose down --volumes
```

## 현재 범위와 다음 단계

현재 1단계에는 프로젝트 구성, 환경변수 설정, Compose 인프라, Flyway schema·seed, Actuator, Testcontainers smoke test만 포함된다. 2단계에서 JPA Entity·Repository, 상태 전이, DB-only 조회·변경 API를 구현할 예정이며 아직 착수하지 않았다.

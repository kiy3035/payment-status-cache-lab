# Repository Guidelines

## Scope

이 파일의 규칙은 저장소 전체에 적용한다.

프로젝트명은 `payment-status-cache-lab`이다. 결제 상태 조회 API의 DB 직접 조회 방식과 Redis cache-aside 방식을 동일 조건에서 구현·비교하고, Redis 지연·중단 상황의 DB fallback을 검증하는 로컬 백엔드 실험 프로젝트다.

## Required Reading Order

작업을 시작하기 전에 저장소 루트에서 다음 순서로 문서를 읽는다.

1. `AGENTS.md`
2. `PROJECT_SPEC.md`
3. `PROGRESS.md` — 존재하는 경우
4. 사용자가 이번 요청에서 지정한 단계와 추가 지시

`PROJECT_SPEC.md`는 프로젝트 요구사항과 완료 기준의 기준 문서다. `PROGRESS.md`는 실제 구현·검증 상태의 기준 문서다. 문서와 구현이 다르면 추측하지 말고 실제 코드와 테스트 결과를 확인한 후 문서를 바로잡는다.

## Stage Execution Rules

- `PROJECT_SPEC.md`의 1~6단계를 순서대로 진행한다.
- 사용자가 요청한 단계만 구현·검증한다.
- 현재 단계를 완료했다고 보고한 뒤 반드시 멈춘다.
- 사용자가 다음 단계 진행을 명시하기 전에는 다음 주요 단계에 착수하지 않는다.
- 계획만 작성하고 멈추지 않는다. 요청된 단계 범위 안에서 구현, 테스트, 실제 실행 검증, 문서 갱신까지 끝낸다.
- 다음 단계에 필요한 인터페이스나 빈 디렉터리를 현재 단계에서 미리 대량 생성하지 않는다.
- 단계 범위를 넘어서는 구현이 불가피하면 이유와 영향을 먼저 설명하고 최소 범위만 처리한다.

## Fixed Technical Baseline

다음을 기본 기술 기준으로 사용한다.

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

버전 충돌이나 실제 이미지 부재가 확인되면 임의로 숨기지 않는다. 호환 가능한 버전을 선택하고 이유와 검증 결과를 `PROGRESS.md`에 기록한다. Docker 이미지는 `latest` 대신 검증한 고정 태그를 우선한다.

## Architecture Constraints

- MySQL을 결제 상태의 Source of Truth로 사용한다.
- Redis는 조회 성능을 위한 파생 캐시이며 원본 데이터로 취급하지 않는다.
- 캐시 비활성화 모드와 활성화 모드는 동일한 API와 동일한 DB 조회 로직을 사용한다.
- 캐시 활성화 여부는 설정으로 전환하고 소스 코드를 바꿔 비교하지 않는다.
- Redis hit·miss·timeout·connection error를 명시적으로 구분한다.
- Redis miss 또는 장애 시 DB fallback 결과가 정상이라면 API도 정상 응답해야 한다.
- Redis와 DB가 모두 실패한 경우에만 의존성 장애 응답을 반환한다.
- Redis 접근 로직은 별도 adapter로 격리한다.
- 모든 캐시 동작을 `@Cacheable` 뒤에 숨기지 않는다. fallback 원인과 metric을 코드에서 검증할 수 있어야 한다.
- Redis command timeout은 애플리케이션 레벨 시간 측정이 아니라 Lettuce client 설정으로 적용한다.
- 결제 상태 변경은 DB commit 이후 Redis에 반영한다.
- DB rollback 시 Redis를 갱신하지 않는다.
- commit 이후 Redis 갱신 실패가 이미 성공한 DB 변경을 실패로 보이게 만들면 안 된다.
- 동시 상태 변경은 JPA `@Version` 기반 optimistic locking으로 감지하고 무조건 재시도하지 않는다.
- 캐시 정합성의 한계와 TTL 동안의 잠재적 stale 상태를 숨기지 않는다.

## Code Rules

- 패키지는 결제 기능 중심으로 구성하되 불필요한 계층과 인터페이스를 남발하지 않는다.
- 상태 전이 규칙은 Entity 또는 명확한 도메인 객체에 둔다.
- 서비스에서 상태 문자열을 임의로 덮어쓰지 않는다.
- API DTO와 JPA Entity를 분리한다.
- Redis 값에 Java native serialization을 사용하지 않는다. 명시적인 JSON 또는 문자열 포맷을 사용한다.
- Redis key, TTL, timeout, cache enabled 설정을 코드에 하드코딩하지 않는다.
- 예외를 무분별하게 `catch (Exception)`으로 삼키지 않는다. Redis 장애처럼 fallback 가능한 실패의 경계를 adapter에서 명확히 변환한다.
- 로그와 metric label에 `paymentId`를 넣어 high cardinality를 만들지 않는다.
- 테스트 편의를 위해 운영 코드에 숨은 분기나 sleep을 추가하지 않는다.
- 성능 차이를 부풀리기 위해 의도적인 지연이나 비효율 쿼리를 추가하지 않는다.

## Database Rules

- 스키마는 Flyway migration으로만 관리한다.
- `spring.jpa.hibernate.ddl-auto`는 로컬 실행과 통합 테스트에서 `validate`를 기본으로 한다.
- H2를 사용하지 않는다.
- MySQL 전용 동작은 실제 MySQL Testcontainers로 검증한다.
- 합성 데이터는 재실행할 때 동일하게 생성되는 deterministic 데이터여야 한다.
- 비밀번호와 접속 문자열을 소스·문서·Git에 직접 넣지 않는다.
- 실제 비밀값 파일은 `.gitignore`에 포함하고 `.env.example`만 제공한다.

## Testing Rules

- 테스트가 통과했다고 보고하기 전에 실제 명령을 실행한다.
- 기본 검증 명령은 `./gradlew test`다. Windows에서도 Gradle Wrapper를 사용한다.
- 실제 MySQL과 Redis가 필요한 테스트는 Testcontainers를 사용한다.
- Redis timeout 검증은 mock 예외만 던지는 방식으로 끝내지 않고 Toxiproxy 등으로 실제 지연을 주입한다.
- 시간 기반 테스트는 지나치게 좁은 범위로 작성해 flaky하게 만들지 않는다.
- Redis hit 테스트에서는 DB repository가 호출되지 않았음을 검증한다.
- DB transaction rollback 테스트에서는 캐시가 변경되지 않았음을 검증한다.
- Redis 중단 테스트에서는 애플리케이션 프로세스가 살아 있고 DB fallback이 성공하는지 확인한다.
- 현재 환경에서 Docker 또는 네트워크 문제로 검증하지 못한 항목은 통과로 기록하지 않는다.

## Measurement Rules

- DB-only와 Redis 적용 비교는 동일한 애플리케이션 이미지, JVM 옵션, 데이터, API, 요청 ID 범위, RPS, 측정 시간, 컨테이너 자원 제한을 사용한다.
- k6 정상 측정은 100 RPS constant-arrival-rate를 사용한다.
- warm-up과 본 측정을 분리한다.
- 정상 비교는 3회 실행 후 중앙값을 사용한다.
- DB QPS, 평균·p95·p99 응답시간, cache hit ratio, 애플리케이션 CPU, MySQL CPU를 실제로 수집한다.
- Redis 중단과 100ms timeout은 별도 시나리오로 측정한다.
- 실행하지 않은 수치, 예상치, 임의의 개선율을 실제 결과처럼 작성하지 않는다.
- 차이가 작거나 측정 노이즈가 크면 그대로 기록한다.
- 결과 JSON·CSV·Markdown은 동일 실행의 원시 데이터와 일치해야 한다.

## Documentation Rules

각 단계 종료 시 `PROGRESS.md`에 다음을 갱신한다.

- 완료한 단계와 구현 항목
- 실제 실행한 명령
- 테스트 개수와 성공·실패 결과
- 실제로 확인한 애플리케이션·MySQL·Redis 상태
- 정상 동작이 확인된 기능
- 실패한 검증과 원인
- 미완료 작업
- 다음 단계 범위
- 주요 생성·수정 파일
- 재현 명령

`README.md`에는 사용자가 처음부터 재현할 수 있는 실행 절차만 기록한다. 미래 단계의 기능을 이미 구현된 것처럼 설명하지 않는다.

## Repository Safety

- 기존 사용자 파일과 변경사항을 보존한다.
- 무관한 파일을 수정하거나 정리하지 않는다.
- `git reset --hard`, 광범위한 삭제, 저장소 전체 포맷 변경을 하지 않는다.
- 프로젝트와 무관한 Docker 컨테이너·이미지·볼륨을 삭제하지 않는다.
- 검증을 위해 만든 일회성 프로세스와 컨테이너는 종료하되, 삭제 범위를 프로젝트 리소스로 한정한다.
- 비밀정보, 로컬 결과 대용량 파일, IDE 설정을 실수로 커밋하지 않도록 확인한다.

## Completion Report

각 단계가 끝나면 다음 순서로 사용자에게 보고하고 멈춘다.

1. 구현한 내용
2. 주요 설계 결정
3. 생성·수정한 파일
4. 실행한 검증 명령
5. 테스트 개수와 결과
6. 실제 실행 상태
7. 발견된 문제와 제한사항
8. 다음 단계 범위

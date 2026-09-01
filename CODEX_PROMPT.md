# Codex Implementation Prompt

현재 디렉터리는 `payment-status-cache-lab` 저장소 루트다.

이 저장소의 결제 상태 조회 API와 Redis DB fallback 실험 프로젝트를 단계별로 구현한다.

## 먼저 읽을 문서

작업 전에 반드시 다음 파일을 순서대로 모두 읽는다.

1. `AGENTS.md`
2. `PROJECT_SPEC.md`
3. `PROGRESS.md` — 존재하는 경우

`AGENTS.md`의 저장소 작업 규칙과 `PROJECT_SPEC.md`의 요구사항을 모두 준수한다. 일부만 읽고 임의로 축약하거나 다른 구조로 변경하지 않는다.

## 이번 작업 범위

이번 실행에서는 `PROJECT_SPEC.md`의 **1단계 — 프로젝트와 로컬 실행 환경만 구현하고 실제 검증까지 완료한다.**

2단계의 JPA 결제 상태 조회·변경 비즈니스 로직은 구현하지 않는다. 1단계 완료 후 다음 단계로 넘어가지 말고 사용자에게 결과를 보고한 뒤 멈춘다.

## 1단계 구현 항목

### 프로젝트 구성

- Java 21
- Spring Boot 3.5.16
- Gradle Wrapper 8.12.1
- 기본 패키지와 애플리케이션 entry point
- Spring Web MVC
- Spring Data JPA
- MySQL Driver
- Spring Data Redis with Lettuce
- Flyway
- Actuator
- Micrometer Prometheus Registry
- Testcontainers
- JUnit 5

필요하지 않은 라이브러리를 미리 추가하지 않는다. 의존성을 추가할 때는 1단계에서 필요한 이유가 있어야 한다.

### Docker Compose

다음 서비스를 구성한다.

- MySQL 8.4
- Redis 7.4
- Toxiproxy 또는 `PROJECT_SPEC.md` 요구를 충족하는 동등 도구

고정 버전 태그를 우선하고 서비스 이름, port, volume, healthcheck를 명확하게 작성한다. 프로젝트와 무관한 기존 Docker 리소스를 수정하거나 삭제하지 않는다.

1단계에서는 Toxiproxy를 실제 장애 실험에 사용하지 않아도 되지만, 4단계에서 Redis 지연을 주입할 수 있는 로컬 구성 기반은 준비한다.

### 설정과 비밀정보

- 환경변수 기반 MySQL·Redis 설정
- `.env.example`
- 실제 `.env` 또는 비밀값 Git 제외
- cache enabled, TTL, command timeout, connect timeout 설정 구조
- JPA `ddl-auto=validate`
- Actuator health·liveness 설정
- Redis 장애가 애플리케이션 liveness를 `DOWN`으로 만들지 않는 기본 구조

비즈니스 로직은 아직 구현하지 않더라도 이후 단계에서 사용할 설정 prefix와 type-safe configuration properties를 과도하지 않은 범위에서 구성할 수 있다.

### Flyway와 합성 데이터

Flyway migration으로 다음을 구현한다.

- `payments` 테이블
- `id`, `status`, `version`, `created_at`, `updated_at`
- 상태 허용값 제약
- 필요한 PK·인덱스
- deterministic 합성 데이터 최소 100,000건

seed 데이터는 매번 동일해야 하며 임의 랜덤 함수에 의존하지 않는다. MySQL에서 실제 migration과 row count를 확인한다.

1단계에서는 `Payment` JPA Entity, Repository, 상태 전이 서비스, 조회·변경 Controller를 구현하지 않는다.

### 테스트

Testcontainers를 사용해 최소 다음 smoke test를 작성한다.

- 실제 MySQL 컨테이너 기동
- Flyway migration 성공
- `payments` 100,000건 확인
- 실제 Redis 컨테이너 기동
- Redis 연결과 기본 round-trip 확인
- Spring context 기동

H2, embedded Redis, 단순 mock으로 대체하지 않는다.

### 문서

저장소 루트에 다음을 생성하거나 갱신한다.

- `README.md`
- `PROGRESS.md`

`README.md`에는 현재 1단계에서 실제 가능한 다음 절차만 기록한다.

- 요구 환경
- 환경변수 준비
- 테스트 실행
- Docker Compose 실행
- 애플리케이션 실행
- health 확인
- MySQL row count 확인
- Redis 연결 확인
- 종료 방법

미구현 API와 성능 결과를 이미 완료된 것처럼 작성하지 않는다.

`PROGRESS.md`에는 다음을 기록한다.

- 1단계 완료·미완료 항목
- 실제 실행 명령
- 테스트 개수와 결과
- 애플리케이션 health 결과
- MySQL 버전과 row count
- Redis 버전과 연결 결과
- 생성·수정 파일
- 발생한 오류와 해결 내용
- 2단계 미착수 상태

## 실제 검증

계획이나 코드 작성만으로 완료 처리하지 않는다. 가능한 환경에서 다음을 실제로 실행한다.

1. Gradle Wrapper 전체 테스트
2. Docker Compose 설정 검증
3. MySQL·Redis 기동과 health 확인
4. Spring Boot 애플리케이션 실제 기동
5. Actuator liveness `UP` 확인
6. MySQL migration과 `payments` 100,000건 확인
7. Redis ping 또는 실제 round-trip 확인
8. 비밀정보 Git 제외 확인
9. 검증 후 일회성 프로세스와 Testcontainers 종료 확인

Windows 환경에서도 재현할 수 있도록 명령과 경로를 작성한다. 프로젝트 내부 작업은 Gradle Wrapper와 Docker Compose를 사용한다.

Docker·네트워크·권한 문제로 일부 검증하지 못하면 성공으로 간주하지 않는다. 실패한 명령, 실제 오류, 현재까지 확인된 범위, 사용자가 재현할 명령을 `PROGRESS.md`와 완료 보고에 남긴다.

## 완료 조건

다음 조건을 모두 충족해야 1단계를 완료로 표시한다.

- Gradle Wrapper build 가능
- 전체 테스트 통과
- 실제 MySQL·Redis 기동
- Flyway migration 성공
- `payments` 100,000건 확인
- 실제 Spring Boot 애플리케이션 기동
- Actuator liveness `UP`
- Testcontainers MySQL·Redis smoke test 통과
- `.env.example` 제공과 실제 비밀값 Git 제외
- README 재현 명령 확인
- `PROGRESS.md` 갱신
- 2단계 비즈니스 로직 미착수

## 완료 보고 형식

1단계가 끝나면 다음 순서로 사용자에게 보고한다.

1. 구현한 내용
2. 주요 설계 결정
3. 생성·수정한 파일
4. 실제 실행한 검증 명령
5. 테스트 개수와 성공·실패 결과
6. 실제 애플리케이션·MySQL·Redis 상태
7. 발견된 문제와 제한사항
8. 2단계에서 구현할 범위

보고 후 **2단계를 시작하지 말고 멈춘다.**

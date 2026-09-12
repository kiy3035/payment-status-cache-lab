# Redis를 붙이면 결제 상태 조회는 얼마나 달라질까? 100 RPS로 직접 비교해봤다

결제 상태 조회 API가 요청마다 MySQL을 읽는다면 구조는 단순하다.

결제 ID로 한 건을 찾고 상태를 응답하면 끝이다. 다만 같은 결제 상태를 반복해서 확인하는 요청이 많아지면 비슷한 SELECT도 계속 쌓인다.

이럴 때 자주 떠올리는 방법이 Redis cache-aside다. 먼저 Redis를 보고, 값이 없을 때만 DB를 읽는다.

구조는 익숙하지만 궁금한 점이 남았다.

정말 DB 조회가 줄어들까? Redis가 느려지거나 멈추면 결제 조회까지 같이 실패할까?

같은 API를 DB-only와 Redis 우선 조회 모드로 나눠 직접 확인해 봤다.

실제 결제나 PG사 연동을 사용한 건 아니다. 로컬 Docker, 100,000건의 합성 결제 데이터와 k6 부하로 진행한 개인 실험이다.

정상 상태의 결과부터 짧게 적으면 이렇다.

> 100 RPS 조건에서 앱 DB 조회 QPS는 100.0083에서 0.0083으로 줄었다. p95는 19.2359ms에서 4.5394ms로 낮아졌다.

하지만 Redis 장애까지 넣자 이야기가 달라졌다. fallback 덕분에 완료된 요청은 성공했지만 응답시간은 정상 상태처럼 유지되지 않았다.

## MySQL을 원본으로 두었다

가장 먼저 정한 원칙은 MySQL이 결제 상태의 원본이라는 점이었다. Redis는 TTL이 있는 파생 데이터일 뿐이다.

조회 흐름은 다음처럼 만들었다.

```text
GET /api/v1/payments/{id}/status
  ├─ cache disabled → MySQL 조회
  └─ cache enabled  → Redis GET
                       ├─ hit → 바로 응답
                       └─ miss / timeout / error
                            → MySQL 조회
                            → Redis SET + TTL 시도
```

캐시가 꺼진 모드에서는 Redis 연결 자체를 시도하지 않는다. 켜진 모드에서는 Redis를 먼저 보고, 실패하면 같은 repository 경로로 MySQL을 읽는다.

어느 경로를 탔는지는 응답 헤더에 남겼다.

```text
HIT
MISS_FALLBACK
TIMEOUT_FALLBACK
ERROR_FALLBACK
DISABLED
```

Spring의 `@Cacheable`을 사용하지 않은 이유도 여기에 있다. miss와 timeout, connection error를 따로 세고 fallback 원인을 지표로 남기고 싶었다.

## 상태 변경은 DB commit이 먼저다

결제 상태는 `READY → AUTH → APPROVED` 방향으로만 바뀐다. 중간 단계를 건너뛰거나 이전 상태로 돌아가는 요청은 Entity가 거절한다.

같은 결제를 동시에 바꾸는 요청은 JPA `@Version`으로 충돌을 감지했다. 먼저 commit한 요청만 성공하고 다른 요청은 HTTP 409를 받는다.

캐시 갱신 순서는 다음과 같다.

```text
상태 전이 검증
→ MySQL UPDATE
→ COMMIT
→ Redis 최신 상태 SET
```

DB transaction이 rollback되면 Redis도 바뀌지 않는다. 반대로 commit은 성공했지만 Redis SET만 실패했다면 이미 성공한 상태 변경을 API 실패로 바꾸지 않았다.

물론 이 선택으로 일관성 문제가 전부 사라지는 건 아니다.

기존 cache key를 새 값으로 덮어쓰는 데 실패하면 TTL 동안 이전 값이 남을 수 있다. 실제 SET 거부 테스트에서도 DB는 `AUTH`로 바뀌었지만 Redis에는 `READY`가 남았다.

기본 TTL 5분은 오래된 값이 남을 수 있는 시간을 제한할 뿐, DB와 Redis를 하나의 transaction으로 만들어 주지는 않는다.

## 비교 조건

DB-only와 Redis 정상 모드는 다음 조건에서 비교했다.

- 같은 Boot JAR과 JVM 옵션
- MySQL 합성 데이터 100,000건
- 결제 ID 1~1,000 hot set
- 앱 1 CPU·512MiB, MySQL 1 CPU·1GiB, Redis 0.5 CPU·256MiB
- 30초 warm-up 뒤 120초 본 측정
- k6 `constant-arrival-rate` 100 RPS
- 정상 시나리오별 3회 실행

응답시간뿐 아니라 앱 DB 조회 counter, MySQL `Com_select`, Redis GET, cache hit ratio와 컨테이너 CPU도 함께 수집했다.

정상 비교값은 가장 빠른 회차가 아니라 지표별 3회 중앙값이다. 그래서 한 표의 값들이 반드시 같은 회차에서 나온 것은 아니다.

## 정상 상태에서는 반복 SELECT가 거의 사라졌다

| 지표 | DB-only | Redis 정상 | 변화 |
| --- | ---: | ---: | ---: |
| 요청 수 / RPS | 12,001 / 100.0083 | 12,001 / 100.0083 | 동일 |
| 평균 응답시간 | 6.3199ms | 3.2311ms | 48.8742% 감소 |
| p95 | 19.2359ms | 4.5394ms | 76.4014% 감소 |
| p99 | 41.6904ms | 18.6836ms | 55.1849% 감소 |
| 앱 DB QPS | 100.0083 | 0.0083 | 99.9917% 감소 |
| MySQL SELECT QPS | 100.0167 | 0.0167 | 99.9833% 감소 |
| cache hit ratio | 0 | 99.9917% | - |
| 앱 CPU 평균 | 47.7763% | 26.7878% | 43.9308% 감소 |
| MySQL CPU 평균 | 15.4181% | 1.8980% | 87.6898% 감소 |
| Redis CPU 평균 | 0.8315% | 2.4172% | 190.7035% 증가 |

두 모드 모두 120초 동안 12,001건, 약 100 RPS를 처리했고 HTTP 성공률은 100%였다.

Redis 정상 모드의 hit ratio는 99.9917%. 앱 DB QPS는 100.0083에서 0.0083으로 줄었다. 평균과 p95·p99, 앱과 MySQL CPU 중앙값도 함께 낮아졌다.

대신 Redis CPU는 0.8315%에서 2.4172%로 늘었다. 조회 비용이 없어진 게 아니라 MySQL에서 Redis 쪽으로 옮겨간 셈이다.

매 회차가 고르게 나온 건 아니다. DB-only 3회차의 p95는 198.2641ms, p99는 959.7855ms까지 튀었다. 측정 당시 같은 호스트에는 다른 Docker 컨테이너 2개도 실행 중이었다.

이상치를 지우거나 가장 좋은 회차만 고르지 않고, 처음 정한 대로 3회 중앙값을 썼다.

## Redis를 멈추자 기능은 살았지만 느려졌다

Redis를 완전히 중단한 시나리오와 Toxiproxy로 downstream 300ms 지연을 준 시나리오도 실행했다. 애플리케이션은 재시작하지 않았다.

| 시나리오 | 완료 요청 / RPS | dropped | fallback | 평균 | p95 | p99 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Redis 중단 | 2,991 / 99.7 | 11 | ERROR 2,991 | 813.6432ms | 2,862.956ms | 4,875.826ms |
| Redis 100ms timeout | 2,854 / 95.1333 | 146 | TIMEOUT 2,853, HIT 1 | 2,704.1618ms | 5,111.2817ms | 6,475.5296ms |

Redis 중단에서는 완료된 2,991건이 모두 MySQL fallback으로 성공했다. 앱 프로세스와 liveness도 유지됐다.

그렇다고 정상 상태의 성능까지 유지한 건 아니다. p95는 약 2.86초, p99는 약 4.88초까지 늘었다.

MySQL까지 함께 차단한 통합 테스트에서는 HTTP 503 `PAYMENT_STATUS_UNAVAILABLE`을 반환했다. Redis만 실패하면 원본 DB로 넘어가고, DB까지 실패했을 때 의존성 장애를 드러내는 경계다.

## 100ms timeout인데 p95는 5초를 넘었다

지연 시나리오에서는 Lettuce command timeout을 100ms로 두었다. 그런데 HTTP p95는 5,111.2817ms, p99는 6,475.5296ms였다. 목표 100 RPS도 채우지 못해 146건의 dropped iteration이 생겼다.

100ms는 Redis 명령 하나의 timeout이지 HTTP 요청 전체의 deadline이 아니다. Redis GET 뒤에는 MySQL fallback과 best-effort Redis SET도 이어진다.

다만 5초가 넘은 시간을 특정 내부 대기 하나로 설명하지는 않았다. 이번 측정은 GET, connection pool, DB 조회, SET의 대기시간을 각각 분리해서 기록하지 않았기 때문이다.

확인한 사실은 좁다. 짧은 Redis command timeout만으로 API의 tail latency까지 짧아지지는 않았다.

## 복구와 stale cache도 따로 봤다

Redis를 다시 시작한 뒤 애플리케이션은 그대로 둔 채, 장애 중 캐시되지 않았던 결제 ID를 두 번 조회했다.

```text
첫 요청  → MISS_FALLBACK
두 번째 → HIT
```

재연결만 확인한 게 아니라 DB fallback 결과가 캐시에 들어가고 다음 요청이 hit으로 바뀌는 데까지 봤다.

이 결과가 기존 stale key를 전부 자동으로 최신화한다는 뜻은 아니다. 확인 범위는 이전에 캐시되지 않았던 ID 하나의 miss→hit 전환까지다.

상태 변경 테스트도 같은 선을 지켰다. DB rollback이면 캐시는 바뀌지 않았고, commit 이후 SET 실패에서는 stale 값이 남을 수 있음을 실제로 확인했다.

## mock 예외만 던지고 끝내지 않았다

프로젝트 완료 시점의 JUnit 기록은 52개 통과, failures·errors·skipped 0이다.

단위 테스트 외에도 Testcontainers와 Toxiproxy로 실제 MySQL·Redis 동작과 네트워크 장애를 확인했다.

- miss→hit, TTL, Redis JSON
- DB rollback과 commit 이후 캐시 갱신
- optimistic locking 충돌
- 실제 Redis GET·SET 거부
- 300ms 지연과 Lettuce 100ms timeout
- Redis stop/start와 같은 앱 프로세스의 복구
- Redis·MySQL 동시 실패의 HTTP 503
- 같은 version을 읽은 두 변경 요청의 200/409 충돌

이번 글을 다시 검토하면서 결과 검증 스크립트도 실행했다. manifest 결과 파일 22개, 측정 시나리오 8개, CPU 1초 표본 2,340개와 문서 핵심 수치 대조가 모두 통과했다.

## 이 결과에서 말할 수 있는 범위

이번 로컬 실험에서 직접 확인한 건 다음과 같다.

- Redis hit이 반복 DB 조회를 크게 줄였다.
- 정상 상태에서 응답시간과 앱·MySQL CPU 중앙값이 낮아졌다.
- Redis miss·timeout·connection error에서 MySQL fallback이 동작했다.
- Redis 중단 중 완료된 요청은 성공했지만 지연시간과 처리량은 나빠졌다.
- DB commit 뒤 캐시를 갱신하고 rollback이면 캐시를 바꾸지 않았다.
- Redis 복구 뒤 같은 앱 프로세스에서 miss가 hit으로 전환됐다.

반대로 모든 환경에서 같은 개선율이 나온다거나, Redis 장애에서도 정상 latency를 유지한다고 말할 수는 없다. TTL 안에 stale cache가 절대 없다는 보장도 아니다.

한 노트북에서 얻은 수치로 운영 용량을 정할 수도 없다. 이번 결과는 구조와 실패 경로가 의도대로 움직이는지 확인한 로컬 측정값이다.

## 직접 돌려보려면

Java 21과 Docker Compose가 필요하다.

```powershell
git clone https://github.com/kiy3035/payment-status-cache-lab.git
cd payment-status-cache-lab

.\gradlew.bat test
pwsh -NoProfile -File .\scripts\verify-stage4.ps1
pwsh -NoProfile -File .\scripts\run-stage5.ps1
pwsh -NoProfile -File .\scripts\verify-stage6.ps1
```

부하 실험은 30초 warm-up과 120초 본 측정을 여러 번 실행하므로 시간이 걸린다.

전체 소스, 원본 JSON·CSV와 재현 절차는 저장소에 남겨뒀다.

- [payment-status-cache-lab GitHub 저장소](https://github.com/kiy3035/payment-status-cache-lab)

## 마치며

Redis를 붙이자 정상 상태의 반복 SELECT는 거의 사라졌다. 앱 DB QPS가 100.0083에서 0.0083으로 줄었고 p95도 19.2359ms에서 4.5394ms로 낮아졌다.

정상 결과만 보면 여기서 끝내기 쉽다.

하지만 Redis를 멈추거나 느리게 만들자 다른 문제가 드러났다. fallback으로 완료 요청의 성공률은 지켰지만 p95는 2.86초와 5.11초까지 늘었다. 100ms command timeout도 HTTP 전체 시간을 100ms로 제한해 주지는 않았다.

상태 변경 쪽에도 선이 있었다. DB commit 뒤 Redis SET이 실패하면 이전 값이 TTL 동안 남을 수 있었다.

그래서 cache-aside를 `Redis를 붙여서 빨라졌다` 한 줄로 정리하기는 어려웠다.

무엇을 원본으로 둘지, Redis 실패 뒤 어디로 넘어갈지, 전체 요청시간을 어디서 끊을지, stale 값을 얼마나 허용할지까지 같이 정해야 했다.

이번 실험에서 가장 크게 줄어든 건 몇 ms보다 반복되는 MySQL SELECT였다. 반대로 가장 선명하게 드러난 한계는 캐시가 느려졌을 때 fallback 경로의 tail latency였다.

둘 다 원본 결과에 그대로 남겨뒀다.

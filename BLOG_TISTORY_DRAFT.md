# Redis를 붙이면 결제 상태 조회는 얼마나 달라질까? 100 RPS로 직접 비교해봤다

결제 상태 조회 API가 요청마다 MySQL을 읽는다면 구조는 단순하다.

결제 ID로 한 건을 조회하고 상태를 응답하면 끝이다. 하지만 같은 결제 상태를 여러 번 확인하는 요청이 늘어나면 비슷한 SELECT도 계속 반복된다.

이럴 때 흔히 떠올리는 방법이 Redis cache-aside다.

먼저 Redis를 확인하고 값이 없을 때만 DB를 읽는 방식. 익숙한 구조지만 Redis를 붙였다는 사실만으로 실제 효과나 장애 상황까지 설명할 수는 없다.

정말 DB 조회가 줄어들까? Redis가 100ms 안에 응답하지 않거나 아예 멈추면 결제 조회도 같이 실패할까?

직접 확인하기 위해 같은 결제 상태 API를 DB-only와 Redis 우선 조회 모드로 나눠 실행했다.

실제 결제나 PG사 연동을 사용한 것은 아니다.

로컬 Docker 환경, 100,000건의 합성 결제 데이터와 k6 부하로 만든 개인 실험이다.

먼저 정상 상태의 결과부터 보면 다음과 같다.

| 지표 | DB-only | Redis 정상 |
| --- | ---: | ---: |
| 평균 응답시간 | 6.3199ms | 3.2311ms |
| p95 | 19.2359ms | 4.5394ms |
| p99 | 41.6904ms | 18.6836ms |
| 앱 DB QPS | 100.0083 | 0.0083 |
| MySQL SELECT QPS | 100.0167 | 0.0167 |
| cache hit ratio | 0 | 99.9917% |

Redis 정상 모드의 앱 DB 조회 QPS는 DB-only보다 99.9917% 줄었다.

하지만 장애 실험의 결과는 그렇게 깔끔하지 않았다.

Redis를 완전히 중단했을 때 완료된 요청은 모두 DB fallback으로 성공했지만 p95는 2,862.956ms까지 늘었다. Lettuce command timeout을 100ms로 설정한 지연 시나리오의 p95는 오히려 5,111.2817ms였다.

짧은 Redis timeout이 짧은 HTTP 응답시간을 보장하지는 않았다.

## 실험에서 확인하고 싶었던 것

질문은 다섯 가지였다.

1. Redis hit이 실제로 MySQL SELECT를 줄이는가?
2. 평균뿐 아니라 p95·p99 응답시간과 CPU도 낮아지는가?
3. Redis miss·timeout·connection error를 구분하면서 DB로 fallback하는가?
4. DB commit과 Redis 갱신 순서를 지켜 rollback과 stale cache 문제를 통제하는가?
5. Redis 중단 뒤 애플리케이션을 재시작하지 않아도 다시 hit으로 복구되는가?

사용한 기술은 Java 21, Spring Boot 3.5.16, MySQL 8.4.6, Redis 7.4.5, Lettuce, Flyway, Testcontainers, Toxiproxy, k6다.

외부 유료 서비스나 실제 결제 데이터는 사용하지 않았다.

## MySQL을 원본으로 두었다

가장 먼저 정한 원칙은 MySQL이 결제 상태의 원본이라는 점이다.

Redis는 조회 속도를 위한 파생 데이터일 뿐이다. Redis에 값이 있다고 해서 그 값을 영구 원본으로 취급하지 않았다.

조회 흐름은 다음과 같다.

```text
GET /api/v1/payments/{id}/status
  ├─ cache disabled
  │    └─ MySQL 조회 → DISABLED
  │
  └─ cache enabled
       └─ Redis GET
            ├─ hit → HIT
            └─ miss / timeout / error
                 └─ MySQL 조회
                      └─ Redis SET + TTL 시도
```

캐시가 꺼진 모드에서는 Redis 연결 자체를 시도하지 않는다.

캐시가 켜져 있으면 Redis GET을 먼저 실행한다. hit이면 DB를 호출하지 않고 바로 응답한다. miss·timeout·connection error라면 같은 repository 경로로 MySQL을 읽은 뒤 Redis SET을 best effort로 시도한다.

어느 경로를 탔는지는 응답 헤더에 남겼다.

```text
X-Cache-Result: HIT
X-Cache-Result: MISS_FALLBACK
X-Cache-Result: TIMEOUT_FALLBACK
X-Cache-Result: ERROR_FALLBACK
X-Cache-Result: DISABLED
```

처음에는 Spring의 `@Cacheable`을 쓰는 방법도 생각했다.

하지만 이번 실험에서는 miss, timeout, connection error를 따로 세고 싶었다. fallback 원인과 캐시 쓰기 실패까지 지표로 남겨야 했기 때문에 Redis 접근을 명시적인 adapter로 분리했다.

## 상태 변경에서는 DB commit을 먼저 끝냈다

결제 상태는 다음 방향으로만 바뀐다.

```text
READY → AUTH → APPROVED
```

`READY → APPROVED`처럼 중간 단계를 건너뛰거나 이전 상태로 돌아가는 변경은 거절한다. 이 규칙은 서비스의 문자열 대입이 아니라 Entity가 판단하도록 했다.

동시에 같은 결제를 수정하는 요청은 JPA `@Version`으로 충돌을 감지한다. 먼저 commit한 요청만 성공하고 나머지는 HTTP 409를 받는 구조다.

캐시 갱신 시점은 상태 전이만큼 중요했다.

```text
상태 변경 요청
  → Entity 상태 전이 검증
  → MySQL UPDATE
  → COMMIT
  → Redis 최신 상태 SET
```

Redis는 DB commit이 끝난 뒤에만 갱신한다.

DB transaction이 rollback되면 Redis도 바뀌지 않는다. 반대로 DB commit은 성공했지만 Redis SET이 실패한 경우, 이미 성공한 결제 상태 변경을 API 실패처럼 보이게 하지 않았다.

이 선택으로 모든 정합성 문제가 사라지는 것은 아니다.

기존 cache key를 최신 값으로 덮어쓰는 작업만 실패하면 TTL 동안 오래된 값이 남을 수 있다. 실제 Redis SET 거부 테스트에서도 DB는 `AUTH`로 바뀌었지만 캐시에는 이전 `READY` 값이 남는 상황을 확인했다.

기본 TTL 5분은 stale 가능 시간을 제한할 뿐 DB와 Redis 사이에 강한 일관성을 만들어 주지는 않는다.

## 비교 조건을 먼저 고정했다

DB-only와 Redis 정상 모드를 다음 조건에서 비교했다.

- 같은 Boot JAR 이미지
- 같은 JVM 옵션
- 같은 MySQL 합성 데이터 100,000건
- 같은 결제 ID 1~1,000 hot set
- 같은 GET API
- 같은 Docker CPU·메모리 제한
- 30초 warm-up
- 120초 본 측정
- k6 `constant-arrival-rate` 100 RPS
- 각 정상 시나리오 3회 실행

애플리케이션은 1 CPU·512MiB, MySQL은 1 CPU·1GiB, Redis는 0.5 CPU·256MiB로 제한했다.

응답시간만 보지는 않았다.

- 애플리케이션의 DB 조회 counter
- MySQL `Com_select`
- Redis GET 횟수
- cache hit ratio
- 앱·MySQL·Redis CPU
- k6 요청 수와 dropped iteration

CPU는 `docker stats`로 1초마다 수집했다.

정상 비교값은 가장 빠른 회차가 아니라 각 지표의 3회 중앙값이다. 따라서 한 표의 중앙값들이 반드시 같은 회차에서 나온 값은 아니다.

## 결과: 반복 SELECT가 거의 사라졌다

정상 시나리오의 중앙값은 다음과 같았다.

| 지표 | DB-only | Redis 정상 | 변화 |
| --- | ---: | ---: | ---: |
| 요청 수 | 12,001 | 12,001 | 동일 |
| 달성 RPS | 100.0083 | 100.0083 | 동일 |
| 성공률 | 100% | 100% | 동일 |
| dropped iteration | 0 | 0 | 동일 |
| 평균 응답시간 | 6.3199ms | 3.2311ms | 48.8742% 감소 |
| p95 | 19.2359ms | 4.5394ms | 76.4014% 감소 |
| p99 | 41.6904ms | 18.6836ms | 55.1849% 감소 |
| 앱 DB QPS | 100.0083 | 0.0083 | 99.9917% 감소 |
| MySQL SELECT QPS | 100.0167 | 0.0167 | 99.9833% 감소 |
| 앱 CPU 평균 | 47.7763% | 26.7878% | 43.9308% 감소 |
| MySQL CPU 평균 | 15.4181% | 1.8980% | 87.6898% 감소 |
| Redis CPU 평균 | 0.8315% | 2.4172% | 190.7035% 증가 |

DB-only에서는 Redis GET이 0회였다.

Redis 정상 모드에서는 실행당 12,001번 Redis GET이 발생했고, hit ratio 중앙값은 99.9917%였다. 앱 DB QPS는 100.0083에서 0.0083으로 줄었다.

평균과 tail latency, 앱과 MySQL CPU도 이번 로컬 실행에서는 함께 낮아졌다.

대신 Redis CPU 평균은 0.8315%에서 2.4172%로 늘었다. 읽기 비용이 사라진 것이 아니라 MySQL에서 Redis로 이동한 셈이다.

수치가 매번 깔끔하게 나온 것은 아니다.

DB-only 3회차의 p95는 198.2641ms, p99는 959.7855ms까지 튀었다. 다른 두 회차보다 눈에 띄게 큰 값이다.

측정 당시 같은 호스트에는 이 프로젝트 외 Docker 컨테이너 2개가 실행 중이었다. 그래서 가장 좋은 결과만 고르지 않고 처음부터 정한 3회 중앙값을 사용했다. 튄 회차도 원본에서 지우지 않았다.

Redis 정상 1회차와 2회차에서도 각각 timeout 1건과 10건이 있었다. 해당 요청은 DB fallback으로 성공해 HTTP 성공률은 100%를 유지했다.

## Redis를 멈추자 기능은 살아 있었지만 느려졌다

Redis 컨테이너를 완전히 멈춘 뒤 애플리케이션을 재시작하지 않고 30초 동안 100 RPS를 목표로 요청했다.

| 항목 | 결과 |
| --- | ---: |
| 완료 요청 | 2,991건 |
| 실제 처리량 | 99.7 RPS |
| dropped iteration | 11건 |
| HTTP 성공률 | 100% |
| `ERROR_FALLBACK` | 2,991건 |
| 앱 DB QPS | 99.7 |
| 평균 | 813.6432ms |
| p95 | 2,862.956ms |
| p99 | 4,875.826ms |

완료된 요청 2,991건은 모두 MySQL fallback으로 성공했다. 애플리케이션 프로세스와 liveness도 유지됐다.

하지만 정상 Redis hit 수준의 응답시간까지 유지된 것은 아니다.

p95는 약 2.86초, p99는 약 4.88초까지 늘었다. fallback의 역할은 정상 상태와 같은 성능을 만드는 것이 아니라 MySQL이 살아 있는 동안 기능을 계속 제공하는 것이었다.

Redis와 MySQL을 동시에 차단한 통합 테스트에서는 HTTP 503 `PAYMENT_STATUS_UNAVAILABLE`을 반환했다.

Redis만 실패했을 때는 DB로 넘어가고, 원본 DB까지 실패했을 때만 의존성 장애를 노출하는 경계다.

## 100ms timeout인데 왜 응답은 5초가 넘었을까

다음에는 Toxiproxy로 Redis downstream에 300ms 지연을 넣었다. Lettuce command timeout은 100ms로 유지했다.

| 항목 | 결과 |
| --- | ---: |
| 완료 요청 | 2,854건 |
| 실제 처리량 | 95.1333 RPS |
| dropped iteration | 146건 |
| HTTP 성공률 | 100% |
| `TIMEOUT_FALLBACK` | 2,853건 |
| 정상 hit | 1건 |
| 평균 | 2,704.1618ms |
| p95 | 5,111.2817ms |
| p99 | 6,475.5296ms |

완료된 요청의 성공률은 100%였지만 목표했던 100 RPS를 채우지 못했다. 실제 처리량은 95.1333 RPS였고 dropped iteration도 146건 발생했다.

100ms는 Redis 명령 하나의 timeout이다.

전체 HTTP 요청의 deadline이 아니다. Redis GET이 timeout된 뒤 MySQL 조회가 이어지고, 조회 결과를 Redis에 다시 쓰는 best-effort SET도 시도한다. 높은 동시성에서는 connection pool과 fallback 경로의 대기가 겹칠 수 있다.

그렇다고 5초가 넘은 지연을 특정 내부 원인 하나로 단정할 수는 없다.

이번 측정은 내부 대기 시간을 단계별로 분해하지 않았다. 확실히 확인한 것은 짧은 Redis command timeout만으로 API의 tail latency까지 짧아지지는 않았다는 점이다.

운영 환경이라면 HTTP deadline, bulkhead, connection pool, 부하 차단과 best-effort 쓰기 범위를 별도로 설계해야 한다.

## 복구는 같은 프로세스에서 확인했다

Redis 장애를 제거한 뒤 애플리케이션을 재시작하지 않고, 장애 중 캐시되지 않았던 결제 ID를 두 번 조회했다.

```text
첫 번째 요청 → MISS_FALLBACK
두 번째 요청 → HIT
```

Redis 컨테이너가 다시 켜졌다는 사실만 확인한 것은 아니다.

첫 요청의 DB fallback 결과가 실제로 캐시에 저장되고, 다음 요청이 Redis hit으로 바뀌는 데까지 확인했다.

물론 이것이 장애 중 남은 모든 stale key가 자동으로 최신화된다는 뜻은 아니다. 이번 복구 확인은 이전에 캐시되지 않았던 ID 하나의 miss→hit 전환 범위다.

## mock 예외만 던지고 끝내지 않았다

프로젝트 완료 당시 병합된 `main`에서는 52개 테스트가 통과했다. 기록된 failures, errors, skipped는 모두 0이다.

상태 전이와 adapter 분기는 단위 테스트로 확인하고, 데이터베이스와 네트워크 동작은 실제 컨테이너로 검증했다.

- Testcontainers MySQL의 Flyway migration과 100,000건 seed
- `READY → AUTH → APPROVED` 상태 전이와 rollback
- optimistic locking 충돌
- Redis JSON·TTL·miss→hit
- Redis hit에서 DB repository 미호출
- DB rollback 시 Redis 미갱신
- commit 이후 Redis SET 실패와 stale cache
- Toxiproxy 300ms 지연과 실제 Lettuce 100ms timeout
- Redis ACL을 이용한 GET·SET 거부
- Redis 실제 stop/start와 같은 앱 프로세스의 복구
- Redis와 MySQL 동시 실패의 HTTP 503
- 같은 version을 읽은 두 HTTP 변경 중 한 건만 commit되고 다른 한 건은 409

이번 원고를 작성하면서 결과 검증 스크립트도 다시 실행했다.

```text
manifest 결과 파일 22개
측정 시나리오 8개
CPU 1초 표본 2,340개
문서와 원본 결과 대조 PASS
```

현재 개발 환경에서는 `JAVA_HOME`이 존재하지 않는 JDK 경로를 가리키고 있어 Gradle 테스트를 새로 실행하지 못했다. 따라서 52개는 현재 재실행 결과가 아니라 프로젝트 완료 당시 보존한 검증 기록이다.

## 이 결과에서 말할 수 있는 것

이번 로컬 실험에서 직접 확인한 내용은 다음과 같다.

- Redis hit이 반복 DB 조회를 크게 줄였다.
- 정상 상태에서 평균·p95·p99와 앱·MySQL CPU 중앙값이 낮아졌다.
- Redis miss·timeout·connection error에서 MySQL fallback이 동작했다.
- Redis 중단 중 완료된 요청은 모두 성공했지만 지연시간과 처리량은 악화됐다.
- DB commit 이후 캐시를 갱신하고 rollback이면 캐시를 바꾸지 않았다.
- Redis 복구 뒤 같은 앱 프로세스에서 miss가 hit으로 전환됐다.

반대로 다음 내용까지 증명한 것은 아니다.

- 모든 하드웨어와 운영 트래픽에서 같은 개선율이 나온다는 주장
- Redis 장애에서도 정상 상태와 같은 latency·throughput을 유지한다는 보장
- TTL 안에 stale cache가 절대 존재하지 않는다는 보장
- 100ms Redis timeout이 HTTP 응답시간도 100ms로 제한한다는 주장
- 한 노트북의 측정값만으로 운영 용량을 산정할 수 있다는 주장

## 직접 실행해 보고 싶다면

Java 21과 Docker Compose가 필요하다.

```powershell
git clone https://github.com/kiy3035/payment-status-cache-lab.git
cd payment-status-cache-lab

.\gradlew.bat test
pwsh -NoProfile -File .\scripts\verify-stage4.ps1
pwsh -NoProfile -File .\scripts\run-stage5.ps1
pwsh -NoProfile -File .\scripts\verify-stage6.ps1
```

`run-stage5.ps1`은 30초 warm-up과 120초 본 측정을 여러 번 실행하므로 완료까지 시간이 걸릴 수 있다.

결과는 실행 ID별 디렉터리에 저장된다.

- `scenario-results.json`: 시나리오별 집계
- `cpu.csv`: 1초 간격 CPU 원본
- `db-qps.csv`: 애플리케이션·MySQL DB QPS
- k6 JSON: 요청별 부하 결과
- `manifest.json`: 결과 파일 SHA-256
- `summary.md`: 비교표

전체 소스 코드와 재현 절차도 저장소에 남겨뒀다.

- [payment-status-cache-lab GitHub 저장소](https://github.com/kiy3035/payment-status-cache-lab)

## 마치며

Redis cache-aside를 적용하자 정상 상태의 앱 DB QPS는 100.0083에서 0.0083으로 줄었다. 평균 응답시간은 6.3199ms에서 3.2311ms, p95는 19.2359ms에서 4.5394ms로 낮아졌다.

하지만 장애 상황에서는 다른 문제가 보였다.

Redis를 멈춰도 완료된 요청은 모두 DB fallback으로 성공했다. 대신 p95가 2.86초까지 늘었다. 100ms command timeout 시나리오는 p95 5.11초와 146건의 dropped iteration을 기록했다.

fallback을 구현하면 기능 가용성을 유지할 수 있다. 그렇다고 정상 상태와 같은 지연시간과 처리량까지 보장되는 것은 아니다.

DB commit 이후 Redis SET이 실패하면 stale 값이 남을 수도 있다. timeout 뒤 Redis 서버에서는 명령이 처리됐지만 클라이언트만 실패로 판단했을 가능성도 있다.

그래서 cache-aside를 단순히 `Redis를 붙여서 빨라졌다`고만 정리하기는 어려웠다.

어떤 데이터를 원본으로 둘지, Redis 실패를 어디서 끊을지, fallback 뒤 전체 요청시간을 어떻게 제한할지, stale 상태를 어디까지 허용할지까지 함께 정해야 하는 구조였다.

이번 실험에서 가장 크게 줄어든 것은 몇 ms의 응답시간보다 반복되는 MySQL SELECT였다. 반대로 가장 크게 드러난 한계는 캐시가 느려졌을 때 fallback 경로의 tail latency였다.

정상 상태의 숫자만 봤다면 놓쳤을 부분이다.

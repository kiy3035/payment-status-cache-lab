# Redis를 붙이면 결제 상태 조회는 얼마나 달라질까

결제 상태 조회 API가 매 요청마다 MySQL을 읽는 구조라면 구현은 단순하다. 하지만 읽기 트래픽이 늘면 같은 상태를 확인하기 위한 SELECT가 반복되고, DB가 처리해야 할 QPS와 CPU 사용량도 함께 증가한다. Redis cache-aside는 흔한 해결책이지만 단순히 Redis를 추가했다는 사실만으로 효과와 장애 안전성을 설명할 수는 없다.

이 프로젝트에서는 같은 API를 DB-only와 Redis 우선 조회 모드로 실행하고, 동일 조건에서 성능과 장애 동작을 직접 측정했다. 특히 “Redis가 빠르다”보다 “Redis가 느리거나 멈췄을 때 결제 조회가 어떻게 되는가”를 더 중요하게 다뤘다.

## 실험에서 확인하려던 것

질문은 다섯 가지였다.

1. Redis hit이 실제로 MySQL SELECT를 줄이는가?
2. 평균뿐 아니라 p95·p99 응답시간과 CPU도 개선되는가?
3. Redis miss·timeout·connection error를 서로 구분하면서 DB로 fallback하는가?
4. DB commit과 Redis 갱신 순서를 지켜 rollback과 stale cache 문제를 통제하는가?
5. Redis가 중단돼도 애플리케이션이 살아 있고, 복구 뒤 다시 hit으로 전환되는가?

답을 만들기 위해 Spring Boot 3.5.16, Java 21, MySQL 8.4.6, Redis 7.4.5, Lettuce, Flyway, Testcontainers, Toxiproxy, k6를 사용했다. 외부 유료 서비스나 실제 PG 결제 연동은 사용하지 않았다.

## MySQL을 원본으로 두었다

핵심 설계는 MySQL을 Source of Truth로 두고 Redis를 파생 캐시로 취급하는 것이다.

조회할 때 캐시가 꺼져 있으면 바로 MySQL을 읽는다. 캐시가 켜져 있으면 Redis GET을 먼저 실행한다. hit이면 DB를 호출하지 않고, miss·timeout·error이면 같은 repository 경로로 MySQL을 조회한 뒤 Redis SET을 best effort로 시도한다.

```text
GET 요청
  ├─ cache disabled → MySQL → DISABLED
  └─ cache enabled → Redis GET
       ├─ hit → HIT
       └─ miss / timeout / error → MySQL → Redis SET → fallback 응답
```

`X-Cache-Result`에는 `HIT`, `MISS_FALLBACK`, `TIMEOUT_FALLBACK`, `ERROR_FALLBACK`, `DISABLED` 중 하나를 넣었다. 이 헤더 덕분에 부하 테스트와 장애 테스트가 실제로 어느 경로를 통과했는지 응답 단위로 확인할 수 있었다.

캐시 접근은 별도 adapter에 두었다. `@Cacheable` 하나로 감싸지 않은 이유는 timeout과 connection error를 구분하고, 각 fallback 원인과 쓰기 실패를 metric으로 검증해야 했기 때문이다.

## 상태 변경은 DB commit이 먼저다

결제 상태는 `READY → AUTH → APPROVED`만 허용한다. 규칙은 서비스의 문자열 대입이 아니라 Entity가 판단하고, 동시 변경은 JPA `@Version`으로 감지한다.

상태 변경에서 더 중요한 것은 Redis 갱신 시점이다.

```text
상태 변경 요청
  → Entity 상태 전이 검증
  → MySQL UPDATE
  → COMMIT
  → Redis 최신값 SET (best effort)
```

Redis는 DB commit 이후에만 갱신한다. DB가 rollback되면 Redis는 바뀌지 않는다. 반대로 commit은 성공했지만 Redis SET이 실패한 경우에는 이미 성공한 결제 변경을 API 실패처럼 보이게 하지 않는다.

이 선택에는 명확한 한계가 있다. DB와 Redis는 하나의 transaction이 아니기 때문에 기존 key 갱신만 실패하면 TTL 동안 stale 값이 남을 수 있다. 이 프로젝트는 그 한계를 숨기지 않고 실제 SET 거부 테스트에서 stale cache가 남는 상황을 확인했다.

## 비교 조건을 먼저 고정했다

성능 숫자를 비교하려면 코드 외의 조건부터 같아야 한다.

- 같은 Boot JAR 이미지와 JVM 옵션
- 같은 MySQL 100,000건 데이터
- 같은 결제 ID 1~1,000 hot set
- 같은 GET API
- 같은 Docker CPU·메모리 제한
- 30초 워밍업과 120초 본 측정
- k6 constant-arrival-rate 100 RPS
- DB-only와 Redis 정상 각각 3회 실행

앱 DB 조회 counter, MySQL `Com_select`, cache hit ratio, 응답시간, 앱·MySQL·Redis CPU를 함께 수집했다. 정상 비교는 가장 좋은 회차가 아니라 각 지표의 3회 중앙값을 사용했다.

## 정상 상태 결과

| 지표 | DB-only | Redis 정상 | 변화 |
| --- | ---: | ---: | ---: |
| 평균 응답시간 | 6.3199ms | 3.2311ms | 48.8742% 감소 |
| p95 | 19.2359ms | 4.5394ms | 76.4014% 감소 |
| p99 | 41.6904ms | 18.6836ms | 55.1849% 감소 |
| 앱 DB QPS | 100.0083 | 0.0083 | 99.9917% 감소 |
| MySQL SELECT QPS | 100.0167 | 0.0167 | 99.9833% 감소 |
| 앱 CPU 평균 | 47.7763% | 26.7878% | 43.9308% 감소 |
| MySQL CPU 평균 | 15.4181% | 1.898% | 87.6898% 감소 |
| Redis CPU 평균 | 0.8315% | 2.4172% | 190.7035% 증가 |

두 모드 모두 중앙값 기준 12,001건, 100.0083 RPS, HTTP 성공률 100%, dropped iteration 0이었다. Redis 정상 hit ratio는 99.9917%였다.

DB 부하는 예상대로 크게 줄었다. 앱과 MySQL CPU, 평균과 tail latency도 이 로컬 실행에서는 낮아졌다. 대신 Redis CPU 사용량이 늘었다. 캐시는 비용을 없앤 것이 아니라 읽기 부하를 더 적합한 저장소로 이동한 것이다.

숫자가 깔끔하기만 했던 것은 아니다. DB-only 3회차 p95는 198.2641ms, p99는 959.7855ms까지 튀었다. 측정 당시 같은 호스트에 다른 Docker 컨테이너 2개가 실행 중이었다. 그래서 단일 회차나 가장 좋은 값 대신 미리 정한 중앙값을 사용했고, 원시 결과도 그대로 남겼다.

## Redis가 멈추면 성능보다 가용성 문제로 바뀐다

Redis 컨테이너를 멈춘 뒤 앱을 재시작하지 않고 30초 동안 100 RPS를 목표로 요청했다.

- 완료 요청 2,991건
- 실제 99.7 RPS
- dropped iteration 11건
- HTTP 성공률 100%
- `ERROR_FALLBACK` 2,991건
- 앱 DB QPS 99.7
- 평균 813.6432ms, p95 2862.9560ms, p99 4875.8260ms

완료된 요청은 모두 MySQL fallback으로 성공했고 애플리케이션 liveness도 유지됐다. 그러나 정상 Redis hit 수준의 지연시간은 유지되지 않았다. fallback은 “정상 상태와 같은 성능”이 아니라 “원본 DB가 살아 있는 동안 기능을 계속 제공하는 경로”였다.

Redis와 MySQL을 동시에 끊은 통합 테스트에서는 HTTP 503 `PAYMENT_STATUS_UNAVAILABLE`을 반환했다. Redis 장애만으로 503을 만들지 않고, fallback DB까지 실패했을 때만 의존성 장애를 노출했다.

## 100ms timeout이 HTTP 100ms를 뜻하지 않았다

Toxiproxy downstream에 300ms 지연을 넣고 Lettuce command timeout을 100ms로 유지한 시나리오는 더 거칠었다.

- 완료 요청 2,854건
- 실제 95.1333 RPS
- dropped iteration 146건
- HTTP 성공률 100%
- `TIMEOUT_FALLBACK` 2,853건, 정상 hit 1건
- 평균 2704.1618ms, p95 5111.2817ms, p99 6475.5296ms

모든 완료 요청은 성공했지만 목표 처리량을 채우지 못했고 전체 응답시간은 수 초까지 늘었다. 100ms는 Redis 명령 하나의 timeout이지 HTTP 요청 전체의 deadline이 아니다. GET timeout 이후 DB 조회와 best-effort SET이 이어지고, 높은 동시성에서는 전체 경로의 대기가 누적될 수 있다.

이 실험은 각 내부 대기 시간을 별도로 분해하지 않았기 때문에 수 초 지연을 특정 원인 하나로 단정하지 않는다. 확실히 말할 수 있는 것은 짧은 Redis command timeout만으로 API tail latency가 짧아진다고 보장할 수 없다는 점이다. 운영 환경이라면 HTTP deadline, bulkhead, connection pool, 재시도 금지/제한, 부하 차단 정책을 별도로 설계해야 한다.

## 복구는 miss에서 hit으로 전환되는지 확인했다

장애를 제거한 뒤 같은 앱 프로세스에서 이전에 캐시되지 않은 결제 ID를 두 번 조회했다.

1. 첫 요청: `MISS_FALLBACK`
2. 두 번째 요청: `HIT`

Redis 컨테이너를 다시 켰다는 사실만 확인하지 않고, fallback 결과가 실제로 캐시에 다시 저장되고 다음 조회가 Redis hit으로 바뀌는 것까지 검증했다. 그렇다고 장애 동안 남은 모든 stale key가 자동 갱신된다는 뜻은 아니다.

## mock만으로 끝내지 않은 테스트

전체 테스트는 52개다. 상태 전이와 adapter 분기는 단위 테스트로 빠르게 확인하고, 데이터베이스와 네트워크 동작은 실제 컨테이너로 검증했다.

- Testcontainers MySQL에서 Flyway migration, 100,000건 seed, JPA validation, optimistic locking 확인
- 실제 Redis에서 JSON·TTL·miss→hit·hit 시 repository 미호출 확인
- 외부 transaction rollback에서 DB와 Redis가 모두 유지되는지 확인
- Toxiproxy 300ms 지연으로 실제 Lettuce timeout 확인
- Redis ACL로 GET·SET 거부를 각각 재현
- Redis 실제 stop/start 중 앱 생존과 복구 확인
- Redis와 DB 동시 실패의 HTTP 503 확인
- 두 HTTP 상태 변경이 같은 version을 읽었을 때 한 건만 commit되고 다른 한 건은 409인지 확인

최종 강제 재실행 결과는 52 tests, failures 0, errors 0, skipped 0이었다.

## 이 결과에서 말할 수 있는 것과 없는 것

말할 수 있는 것:

- 이 로컬 조건에서 Redis hit은 DB QPS를 거의 제거했다.
- 정상 상태의 응답시간과 앱·MySQL CPU 중앙값이 감소했다.
- Redis miss·timeout·error에서 MySQL fallback이 실제로 동작했다.
- Redis 장애 중 완료 요청은 모두 성공했지만 지연시간과 처리량은 악화됐다.
- DB commit 이후 캐시 갱신과 rollback 시 미갱신이 검증됐다.

말할 수 없는 것:

- 모든 하드웨어와 운영 트래픽에서 같은 개선율이 나온다는 주장
- Redis 장애 시 정상 상태와 같은 latency·throughput 보장
- TTL 안에 stale cache가 절대 존재하지 않는다는 보장
- 이 단일 노트북 측정만으로 운영 용량을 산정할 수 있다는 주장

## 마무리

Redis cache-aside의 장점은 단순히 응답시간 몇 ms를 줄이는 데 있지 않았다. 이 실험에서 더 큰 변화는 반복 SELECT를 Redis로 옮겨 MySQL QPS와 CPU를 낮춘 것이다.

반면 장애 실험은 캐시가 새로운 실패 지점이라는 사실을 보여줬다. fallback을 구현하면 기능 가용성은 유지할 수 있지만, command timeout 하나만으로 tail latency와 처리량 문제가 해결되지는 않는다. 그리고 DB commit과 Redis 갱신 사이에는 여전히 stale 가능 구간이 남는다.

결론적으로 cache-aside는 “붙이면 빨라지는 기능”이 아니라 원본 데이터, 실패 경계, timeout, 관측 지표, 정합성 한계를 함께 설계해야 하는 구조다. 재현 명령과 전체 원시 결과는 [`README.md`](README.md)와 [`results/20260905-222015/`](results/20260905-222015/)에 보존했다.

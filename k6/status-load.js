import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://app:8080';
const rps = Number(__ENV.RPS || 100);
const duration = __ENV.DURATION || '120s';
const hotSetSize = Number(__ENV.HOT_SET_SIZE || 1000);
const scenarioName = __ENV.SCENARIO_NAME || 'measurement';
const expectedCacheResults = (__ENV.EXPECTED_CACHE_RESULTS || 'DISABLED').split(',');

const cacheHit = new Counter('cache_result_hit');
const cacheMiss = new Counter('cache_result_miss');
const cacheTimeout = new Counter('cache_result_timeout');
const cacheError = new Counter('cache_result_error');
const cacheDisabled = new Counter('cache_result_disabled');

const thresholds = {
  checks: ['rate>0.999'],
  http_req_failed: ['rate<0.001'],
};
if (__ENV.ALLOW_DROPPED_ITERATIONS !== 'true') {
  thresholds.dropped_iterations = ['count==0'];
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    [scenarioName]: {
      executor: 'constant-arrival-rate',
      rate: rps,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 200,
      maxVUs: 1000,
      gracefulStop: '5s',
    },
  },
  thresholds,
};

export default function () {
  // 실행 전체의 순번을 사용해 모든 시나리오에서 동일한 ID 범위만 조회한다.
  const paymentId = (exec.scenario.iterationInTest % hotSetSize) + 1;
  const response = http.get(`${baseUrl}/api/v1/payments/${paymentId}/status`);
  const cacheResult = response.headers['X-Cache-Result'] || '';

  cacheHit.add(cacheResult === 'HIT');
  cacheMiss.add(cacheResult === 'MISS_FALLBACK');
  cacheTimeout.add(cacheResult === 'TIMEOUT_FALLBACK');
  cacheError.add(cacheResult === 'ERROR_FALLBACK');
  cacheDisabled.add(cacheResult === 'DISABLED');

  check(response, {
    '상태 코드가 200이다': (result) => result.status === 200,
    '기대한 캐시 경로를 사용한다': () => expectedCacheResults.includes(cacheResult),
  });
}

export function handleSummary(data) {
  const output = JSON.stringify(data, null, 2);
  return {
    [__ENV.SUMMARY_PATH || '/results/summary.json']: output,
    stdout: `${scenarioName} 요약을 저장했습니다.\n`,
  };
}

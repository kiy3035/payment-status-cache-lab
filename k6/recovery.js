import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://app:8080';
const recoveryId = Number(__ENV.RECOVERY_ID || 50000);
const miss = new Counter('recovery_miss');
const hit = new Counter('recovery_hit');

export const options = {
  vus: 1,
  iterations: 2,
  thresholds: { checks: ['rate==1'] },
};

export default function () {
  const expected = __ITER === 0 ? 'MISS_FALLBACK' : 'HIT';
  const response = http.get(`${baseUrl}/api/v1/payments/${recoveryId}/status`);
  const actual = response.headers['X-Cache-Result'] || '';
  miss.add(actual === 'MISS_FALLBACK');
  hit.add(actual === 'HIT');
  check(response, {
    '상태 코드가 200이다': (result) => result.status === 200,
    '복구 후 첫 요청은 miss이고 다음 요청은 hit이다': () => actual === expected,
  });
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY_PATH || '/results/redis-recovery.json']: JSON.stringify(data, null, 2) };
}

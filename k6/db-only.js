import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const rps = Number(__ENV.RPS || 100);
const duration = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    db_only: {
      executor: 'constant-arrival-rate',
      rate: rps,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
};

export default function () {
  const paymentId = (__ITER % 1000) + 1;
  const response = http.get(`${baseUrl}/api/v1/payments/${paymentId}/status`);

  check(response, {
    '상태 코드가 200이다': (result) => result.status === 200,
    '캐시 결과가 DISABLED이다': (result) => result.headers['X-Cache-Result'] === 'DISABLED',
  });
}

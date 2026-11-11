// 웹훅 수신 부하 측정 (k6). 실행: k6 run -e BASE_URL=http://localhost:8081 -e OUT=summary.json load-test/webhook-ingest.js
// 요청마다 CHANGE_ORDER_STATUS 원소 5개(서로 다른 주문옵션)를 보낸다. 수신은 원본 저장 + outbox 기록 후 200이어야 한다.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 20),
      duration: __ENV.DURATION || '30s',
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const ELEMENTS = 5;

export default function () {
  const iter = exec.scenario.iterationInTest;
  const items = [];
  for (let i = 0; i < ELEMENTS; i++) {
    const optionNo = 5000000 + iter * ELEMENTS + i;
    items.push({
      eventType: 'CHANGE_ORDER_STATUS',
      orderProductOptionNo: optionNo,
      orderNo: 'K6' + optionNo,
      deliveryNo: 7000000 + iter,
      orderStatusType: 'PAY_DONE',
      claimStatusType: null,
      claimNo: null,
      orderYmdt: '2026-11-11 10:00:00',
      payYmdt: '2026-11-11 10:00:01',
    });
  }
  const res = http.post(`${BASE_URL}/webhooks/shopby`, JSON.stringify(items), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
  const out = __ENV.OUT || 'k6-summary.json';
  return { [out]: JSON.stringify(data, null, 2) };
}

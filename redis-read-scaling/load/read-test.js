// Ramping read load against the app.
//
// Uses ramping-arrival-rate (not ramping-vus) on purpose: we want to hold a
// fixed *request rate* and watch what happens when the system can't keep up.
// With ramping-vus, a slowing system just sends fewer requests and quietly
// hides its own saturation.
//
// Run it (from the load/ directory's parent):
//   docker run --rm -i --network redis-read-scaling_lab \
//     -e MAX_RPS=6000 grafana/k6 run - < load/read-test.js
//
// Override the peak with MAX_RPS. Suggested per step:
//   step 1 (0 replicas):  MAX_RPS=6000
//   step 2 (1 replica):   MAX_RPS=6000   then 12000 to break it again
//   step 3 (3 replicas):  MAX_RPS=12000

import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const MAX_RPS = Number(__ENV.MAX_RPS || 6000);
const BASE = __ENV.BASE_URL || 'http://app:8080';
const HOLD = __ENV.HOLD || '90s';

export const errorRate = new Rate('read_errors');

export const options = {
  discardResponseBodies: true,
  scenarios: {
    reads: {
      executor: 'ramping-arrival-rate',
      startRate: Math.round(MAX_RPS / 10),
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 3000,
      stages: [
        { target: Math.round(MAX_RPS / 2), duration: '20s' },  // warm up
        { target: MAX_RPS, duration: '30s' },                  // ramp to peak
        { target: MAX_RPS, duration: HOLD },                   // hold — read the numbers here
      ],
    },
  },
  thresholds: {
    // Not a pass/fail gate, just a line in the summary that makes the failure
    // obvious when you cross it.
    read_errors: ['rate<0.01'],
    http_req_duration: ['p(95)<50'],
  },
};

export default function () {
  const res = http.get(`${BASE}/read`);
  const ok = check(res, { 'status 200': (r) => r.status === 200 });
  errorRate.add(!ok);
}

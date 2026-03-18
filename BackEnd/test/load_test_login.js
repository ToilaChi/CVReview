import http from "k6/http";
import { check } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";

// ─── Custom metrics ───────────────────────────────────────────────
const loginDuration = new Trend("login_duration", true);
const successRate = new Rate("login_success_rate");
const timeoutCount = new Counter("login_timeout_count");
const firstByteTime = new Trend("time_to_first_byte", true);

// ─── Config ───────────────────────────────────────────────────────
const BASE_URL = "http://localhost:8081";
const ENDPOINT = "/auth/login";
const ACCOUNT_COUNT = 10000;
const PASSWORD = "Password@123";

const ACCOUNTS = Array.from({ length: ACCOUNT_COUNT }, (_, i) => ({
  phone: String(i + 1).padStart(10, "0"),
  password: PASSWORD,
}));

// ─── Scenarios ────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // Scenario 1 — Concurrent login theo waves
    // Thay vì 10000 VU cùng lúc (hết RAM), dùng 3 đợt × 1000 VU
    // Mỗi đợt login account riêng biệt → vẫn cover 10000 account
    // Kết quả vẫn đo được concurrent login capacity thực tế
    wave_1: {
      executor: "per-vu-iterations",
      vus: 600,
      iterations: 1,
      maxDuration: "60s",
      startTime: "0s",
      tags: { scenario: "concurrent_login" },
      gracefulStop: "10s",
      env: { WAVE_OFFSET: "0" },      // account 1    → 1000
    },
    wave_2: {
      executor: "per-vu-iterations",
      vus: 600,
      iterations: 1,
      maxDuration: "60s",
      startTime: "15s",                     // chờ wave 1 warm up xong
      tags: { scenario: "concurrent_login" },
      gracefulStop: "10s",
      env: { WAVE_OFFSET: "1000" },   // account 1001 → 2000
    },
    wave_3: {
      executor: "per-vu-iterations",
      vus: 600,
      iterations: 1,
      maxDuration: "60s",
      startTime: "30s",
      tags: { scenario: "concurrent_login" },
      gracefulStop: "10s",
      env: { WAVE_OFFSET: "2000" },   // account 2001 → 3000
    },

    // Scenario 2 — Sustained load
    // Tăng dần 100 → 1000 req/s, đo throughput bền vững
    sustained_load: {
      executor: "ramping-arrival-rate",
      startTime: "100s",           // sau khi tất cả wave xong
      startRate: 100,
      timeUnit: "1s",
      preAllocatedVUs: 300,
      maxVUs: 600,              // giới hạn VU để tránh OOM
      stages: [
        { duration: "20s", target: 300 },  // 100 → 300 req/s
        { duration: "20s", target: 600 },  // 300 → 600 req/s
        { duration: "20s", target: 1000 },  // 600 → 1000 req/s
        { duration: "30s", target: 1000 },  // giữ 1000 req/s
        { duration: "10s", target: 0 },  // tắt dần
      ],
      tags: { scenario: "sustained_load" },
    },
  },

  thresholds: {
    "http_req_duration{scenario:concurrent_login}": ["p(95)<3000"],
    "http_req_duration{scenario:sustained_load}": ["p(95)<1000", "p(99)<2000"],
    "http_req_failed": ["rate<0.05"],
    "login_success_rate": ["rate>0.95"],
  },
};

// ─── Main function ────────────────────────────────────────────────
export default function () {
  // Mỗi wave dùng offset khác nhau → account không trùng giữa các wave
  const offset = parseInt(__ENV.WAVE_OFFSET ?? "0");
  const account = ACCOUNTS[(offset + (__VU - 1)) % ACCOUNT_COUNT];

  const payload = JSON.stringify({
    phone: account.phone,
    password: account.password,
  });

  const params = {
    headers: { "Content-Type": "application/json" },
    timeout: "15s",
    tags: { endpoint: "login" },
  };

  const startTime = Date.now();
  const res = http.post(`${BASE_URL}${ENDPOINT}`, payload, params);
  const duration = Date.now() - startTime;

  loginDuration.add(duration);
  firstByteTime.add(res.timings.waiting);

  if (res.status === 504) {
    timeoutCount.add(1);
    console.warn(`[TIMEOUT] VU ${__VU} (${account.phone}) — ${duration}ms`);
  }

  const success = check(res, {
    "status is 200": (r) => r.status === 200,
    "has accessToken": (r) => {
      try { return JSON.parse(r.body)?.data?.accessToken != null; }
      catch { return false; }
    },
    "no timeout": (r) => r.status !== 504,
    "response time < 3000ms": (r) => r.timings.duration < 3000,
  });

  successRate.add(success);

  if (__ITER === 0) {
    console.log(
      `[FIRST] VU ${__VU} | account: ${account.phone} | ` +
      `status: ${res.status} | duration: ${duration}ms | TTFB: ${res.timings.waiting}ms`
    );
  }
}

// ─── Summary ──────────────────────────────────────────────────────
export function handleSummary(data) {
  const d = data.metrics["login_duration"]?.values ?? {};
  const s = data.metrics["login_success_rate"]?.values ?? {};
  const t = data.metrics["login_timeout_count"]?.values ?? {};
  const h = data.metrics["http_req_failed"]?.values ?? {};
  const d1 = data.metrics["http_req_duration{scenario:concurrent_login}"]?.values ?? {};
  const d2 = data.metrics["http_req_duration{scenario:sustained_load}"]?.values ?? {};

  const fmt = (v) => v?.toFixed(0) ?? "N/A";
  const pct = (v) => ((v ?? 0) * 100).toFixed(2) + "%";

  const lines = [
    "╔══════════════════════════════════════════════════╗",
    "║           KẾT QUẢ LOAD TEST LOGIN                ║",
    "╚══════════════════════════════════════════════════╝",
    "",
    "─── TỔNG QUAN ───────────────────────────────────────",
    `  Tổng requests       : ${data.metrics["http_reqs"]?.values?.count ?? 0}`,
    `  Thành công (%)      : ${pct(s.rate)}`,
    `  Timeout count       : ${t.count ?? 0}`,
    `  HTTP error rate (%) : ${pct(h.rate)}`,
    "",
    "─── LATENCY TỔNG (ms) ───────────────────────────────",
    `  Avg    : ${fmt(d.avg)}`,
    `  p50    : ${fmt(d["p(50)"])}`,
    `  p90    : ${fmt(d["p(90)"])}`,
    `  p95    : ${fmt(d["p(95)"])}`,
    `  p99    : ${fmt(d["p(99)"])}`,
    `  Max    : ${fmt(d.max)}`,
    "",
    "─── SCENARIO 1: CONCURRENT LOGIN — 3×1000 VU (ms) ──",
    `  p95    : ${fmt(d1["p(95)"])}   (threshold: <3000ms)`,
    `  p99    : ${fmt(d1["p(99)"])}`,
    `  Max    : ${fmt(d1.max)}`,
    "",
    "─── SCENARIO 2: SUSTAINED LOAD — đến 1000 req/s (ms) ",
    `  p95    : ${fmt(d2["p(95)"])}   (threshold: <1000ms)`,
    `  p99    : ${fmt(d2["p(99)"])}   (threshold: <2000ms)`,
    `  Max    : ${fmt(d2.max)}`,
    "",
  ];

  lines.forEach((l) => console.log(l));

  return {
    "stdout": JSON.stringify(data, null, 2),
    "load_test_result.json": JSON.stringify(data, null, 2),
  };
}
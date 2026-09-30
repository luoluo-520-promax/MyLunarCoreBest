/**
 * k6 正式压测基线：登录/心跳/匹配/抽卡/战斗/场景同步等场景的 HTTP 连通性近似。
 *
 * 用法：
 *   k6 run scripts/load/k6_baseline.js
 *   k6 run -e SCENARIO=heartbeat -e PROFILE=prod scripts/load/k6_baseline.js
 *   k6 run -e PROFILE=prod -e VUS_MULT=1.5 scripts/load/k6_baseline.js
 *
 * PROFILE=dev|prod 切换阈值；真实游戏协议 SLA 见 docs/load-test-plan.md。
 * 回填：目标硬件跑通后更新下方 THRESHOLDS，并勾选 docs/load-test-baseline.md §6。
 *
 * 建议扩容参数（压测后写入 application.properties）：
 *   lunarcore.zone.max-players
 *   lunarcore.netty.business-threads
 *   lunarcore.kcp.retransmit.algo=adaptive
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const failRate = new Rate('baseline_fail_rate');
const SCENARIO = __ENV.SCENARIO || 'health';
const PROFILE = __ENV.PROFILE || 'dev';
const BASE = __ENV.BASE_URL || 'https://localhost:18443';

/** 开发机初值；生产目标机复跑后用 PROFILE=prod；下列 prod 阈值为 4c8g 机型初校 */
const THRESHOLDS = PROFILE === 'prod'
  ? {
      http_req_failed: ['rate<0.01'],
      http_req_duration: ['p(95)<200', 'p(99)<500'],
      baseline_fail_rate: ['rate<0.01'],
    }
  : {
      http_req_failed: ['rate<0.05'],
      http_req_duration: ['p(95)<500', 'p(99)<1200'],
      baseline_fail_rate: ['rate<0.05'],
    };

const VUS_MULT = Math.max(0.1, Number(__ENV.VUS_MULT || '1'));

const STAGES_BASE = PROFILE === 'prod'
  ? [
      { duration: '1m', target: 100 },
      { duration: '3m', target: 500 },
      { duration: '1m', target: 0 },
    ]
  : [
      { duration: '30s', target: 50 },
      { duration: '1m', target: 200 },
      { duration: '30s', target: 0 },
    ];

/** VUS_MULT=1.5 用于上线前 1.5× 峰值校准 */
const STAGES = STAGES_BASE.map((s) => ({
  duration: s.duration,
  target: Math.round(s.target * VUS_MULT),
}));

export const options = {
  stages: STAGES,
  thresholds: THRESHOLDS,
};

function hit(path, name) {
  const res = http.get(`${BASE}${path}`, { tlsSkipVerify: true });
  const ok = check(res, { [`${name} 200`]: (r) => r.status === 200 });
  failRate.add(!ok);
}

/**
 * 场景映射（HTTP 代理近似；完整协议压测需 KCP/MockClient）：
 * login/heartbeat → /ai/health 或 /auth/health
 * matchmaking → gateway /v1/ai/health
 * gacha_burst/battle/scene_move → /ai/health
 * guild_war → /auth/health
 */
export default function () {
  if (SCENARIO === 'heartbeat' || SCENARIO === 'login') {
    hit('/auth/health', 'login-heartbeat');
    hit('/ai/health', 'ai-sidecar');
  } else if (SCENARIO === 'matchmaking') {
    hit('/v1/ai/health', 'matchmaking-proxy');
    hit('/auth/health', 'auth-health');
  } else if (SCENARIO === 'gacha_burst' || SCENARIO === 'scene_move' || SCENARIO === 'battle') {
    hit('/ai/health', `${SCENARIO}-proxy`);
    hit('/auth/health', `${SCENARIO}-auth`);
  } else if (SCENARIO === 'guild_war') {
    hit('/auth/health', 'guild-war-proxy');
  } else {
    hit('/auth/health', 'auth-health');
  }
  sleep(PROFILE === 'prod' ? 0.2 : 0.5);
}

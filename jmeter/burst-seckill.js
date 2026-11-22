// 秒杀入口分层压测：统计每个请求落在哪一层拒绝
// 用法：
//   node burst-seckill.js --total 30000 --concurrency 1000 --repeat 30 --ticket 8
//   node burst-seckill.js --single-user 20        # 单用户连打 20 次，验证用户级限流
const fs = require('fs');
const http = require('http');

function arg(name, def) {
  const i = process.argv.indexOf('--' + name);
  return i > -1 ? process.argv[i + 1] : def;
}

const TOTAL = parseInt(arg('total', '1000'), 10);
const CONCURRENCY = parseInt(arg('concurrency', '500'), 10);
const REPEAT = parseInt(arg('repeat', '1'), 10);
const TICKET = arg('ticket', '8');
const SINGLE = arg('single-user', '');
const TOKEN_FILE = arg('tokens', 'D:/echo/jmeter/tokens1000.csv');

const agent = new http.Agent({ keepAlive: true, maxSockets: CONCURRENCY + 10 });

function post(token, csrf) {
  return new Promise((resolve) => {
    const started = Date.now();
    const req = http.request({
      host: '127.0.0.1',
      port: 8081,
      path: `/ticket-order/seckill/${TICKET}`,
      method: 'POST',
      agent,
      headers: {
        'authorization': token,
        'X-CSRF-Token': csrf,
        'Content-Type': 'application/json',
        'Content-Length': 0
      }
    }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => body += c);
      res.on('end', () => resolve({ status: res.statusCode, body, elapsed: Date.now() - started, start: started }));
    });
    req.on('error', (e) => resolve({ status: 0, body: 'NETWORK:' + e.code, elapsed: Date.now() - started, start: started }));
    req.end();
  });
}

function classify(r) {
  if (r.status === 0) return 'NETWORK_ERROR';
  if (r.status !== 200) return 'HTTP_' + r.status;
  let j = null;
  try { j = JSON.parse(r.body); } catch (e) { return 'NON_JSON'; }
  if (j.success) {
    if (j.data && j.data.status === 'QUEUED') return '排队成功(QUEUED)';
    return '受理成功(其他)';
  }
  const m = j.message || '';
  if (m.includes('当前请求过多')) return '拒绝-本地令牌桶';
  if (m.includes('当前参与用户过多')) return '拒绝-全局限流';
  if (m.includes('1秒内最多5次')) return '拒绝-用户级限流';
  if (m.includes('操作过于频繁')) return '拒绝-下单频控';
  if (m.includes('您已购买过')) return '拒绝-一人一单';
  if (m.includes('秒杀已经结束')) return '拒绝-活动已结束';
  if (m.includes('秒杀尚未开始')) return '拒绝-未开始';
  if (m.includes('库存不足') || m.includes('售罄')) return '拒绝-售罄';
  return '拒绝-其他:' + m.slice(0, 20);
}

(async () => {
  const lines = fs.readFileSync(TOKEN_FILE, 'utf8').trim().split('\n').slice(1);
  const tokens = lines.map((l) => l.split(','));

  let tasks;
  if (SINGLE) {
    const n = parseInt(SINGLE, 10);
    const t = tokens[0];
    tasks = Array.from({ length: n }, () => t);
    console.log(`单用户模式：用第 1 个账号连打 ${n} 次`);
  } else {
    tasks = [];
    for (let r = 0; r < REPEAT; r++) for (const t of tokens) tasks.push(t);
  }

  const total = Math.min(TOTAL, tasks.length);
  let cursor = 0;
  const results = [];

  async function worker() {
    while (true) {
      const idx = cursor++;
      if (idx >= total) return;
      const [token, csrf] = tasks[idx];
      results.push(await post(token, csrf));
    }
  }

  const t0 = Date.now();
  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));
  const wall = (Date.now() - t0) / 1000;

  const dist = {};
  const samples = {};
  for (const r of results) {
    const k = classify(r);
    dist[k] = (dist[k] || 0) + 1;
    if (!samples[k]) samples[k] = r.body.slice(0, 180);
  }
  const lat = results.map((r) => r.elapsed).sort((a, b) => a - b);
  const q = (p) => lat[Math.min(lat.length - 1, Math.round((lat.length - 1) * p))] || 0;
  let minStart = Infinity, maxEnd = 0;
  for (const r of results) {
    if (r.start < minStart) minStart = r.start;
    const end = r.start + r.elapsed;
    if (end > maxEnd) maxEnd = end;
  }
  const spanMs = maxEnd - minStart;
  // 峰值 QPS：按 1 秒窗口统计请求发起数
  const buckets = {};
  for (const r of results) {
    const sec = Math.floor((r.start - minStart) / 1000);
    buckets[sec] = (buckets[sec] || 0) + 1;
  }
  let peak = 0;
  for (const v of Object.values(buckets)) if (v > peak) peak = v;

  console.log('='.repeat(64));
  console.log(`请求总数=${results.length}  并发=${CONCURRENCY}  墙钟=${wall.toFixed(2)}s  平均吞吐=${(results.length / wall).toFixed(0)} req/s`);
  console.log(`峰值秒级吞吐=${peak} req/s   请求时间跨度=${(spanMs / 1000).toFixed(2)}s`);
  console.log(`延迟 P50=${q(0.5)}ms P90=${q(0.9)}ms P99=${q(0.99)}ms Max=${lat[lat.length - 1]}ms`);
  console.log('-'.repeat(64));
  Object.entries(dist).sort((a, b) => b[1] - a[1]).forEach(([k, v]) => {
    console.log(`${k.padEnd(22)} ${String(v).padStart(7)}  ${(v / results.length * 100).toFixed(1)}%`);
  });
  console.log('-'.repeat(64));
  Object.entries(samples).forEach(([k, v]) => console.log(`[${k}] 样例: ${v}`));

  const out = { total: results.length, concurrency: CONCURRENCY, wall: +wall.toFixed(2), qps: Math.round(results.length / wall), peak, p50: q(0.5), p90: q(0.9), p99: q(0.99), max: lat[lat.length - 1], dist };
  fs.writeFileSync(process.env.BURST_OUT || 'D:/echo/jmeter/bench/burst_result.json', JSON.stringify(out, null, 2), 'utf8');
})();

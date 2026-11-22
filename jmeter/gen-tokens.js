// 预登录 500 个压测账号，产出 tokens.csv（token,csrfToken），供秒杀纯链路压测使用
const fs = require('fs');

const BASE = 'http://127.0.0.1:8081';
const TOTAL = 500;
const CONCURRENCY = 20;
const out = ['token,csrfToken'];

function phone(i) {
  return '199' + String(i).padStart(8, '0');
}

async function login(i) {
  const res = await fetch(BASE + '/user/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phone: phone(i), password: '123456' })
  });
  const j = await res.json();
  if (!j.success || !j.data || !j.data.token) {
    throw new Error('登录失败 ' + phone(i) + ' -> ' + JSON.stringify(j).slice(0, 120));
  }
  return j.data.token + ',' + j.data.csrfToken;
}

(async () => {
  const results = new Array(TOTAL);
  let cursor = 0;
  let failed = 0;

  async function worker() {
    while (true) {
      const idx = cursor++;
      if (idx >= TOTAL) return;
      try {
        results[idx] = await login(idx + 1);
      } catch (e) {
        failed++;
        console.error(e.message);
      }
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, () => worker()));

  for (const r of results) {
    if (r) out.push(r);
  }
  fs.writeFileSync('D:/echo/jmeter/tokens.csv', out.join('\n') + '\n', 'utf8');
  console.log('成功登录:', out.length - 1, '失败:', failed);
})();

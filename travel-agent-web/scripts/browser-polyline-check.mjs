// 浏览器端验证：高德 JS API 收到的 Polyline 是「真实道路几何」还是「两点直连」。
// 关键：SDK 自己给 window.AMap 赋值，所以必须在赋值那一刻就接管（属性 setter），
// 否则补丁打在一个会被替换掉的旧对象上（上一版就是这样 0 命中）。
import { chromium, request } from '@playwright/test';

const BASE = 'http://127.0.0.1:4173';
const TRIP_ID = process.argv[2] || '1';

const api = await request.newContext({ baseURL: BASE });
const login = await api.post('/api/auth/login', { data: { username: 'demo', password: 'demo123' } });
if (!login.ok()) throw new Error(`login failed ${login.status()}`);
const state = await api.storageState();
await api.dispose();

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, storageState: state });
const page = await context.newPage();

await page.addInitScript(() => {
  window.__polylinePoints = [];
  window.__polylineSources = [];
  let current;

  const record = (path) => {
    if (!Array.isArray(path)) return;
    window.__polylinePoints.push(path.length);
  };

  const patch = (AMap) => {
    if (!AMap || AMap.__patchedForProbe) return;
    AMap.__patchedForProbe = true;

    // 1) 每次 new Polyline 都记录构造参数里的 path 点数
    const OrigPolyline = AMap.Polyline;
    if (typeof OrigPolyline === 'function') {
      const Wrapped = function (...args) {
        const inst = new OrigPolyline(...args);
        try {
          const opts = args[0] && args[0].path ? args[0].path : inst.getPath?.();
          record(opts);
        } catch { /* 忽略 */ }
        return inst;
      };
      Wrapped.prototype = OrigPolyline.prototype;
      Object.setPrototypeOf(Wrapped, OrigPolyline);
      AMap.Polyline = Wrapped;
    }

    // 2) 兜底：Map.add 收到的覆盖物也记录一次（覆盖 createPolyline 之类的路径）
    const proto = AMap.Map && AMap.Map.prototype;
    if (proto && !proto.__patchedForProbe) {
      proto.__patchedForProbe = true;
      const add = proto.add;
      proto.add = function (overlay, ...rest) {
        const list = Array.isArray(overlay) ? overlay : [overlay];
        for (const o of list) {
          try {
            if (o && typeof o.getPath === 'function') record(o.getPath());
          } catch { /* 忽略 */ }
        }
        return add.apply(this, [overlay, ...rest]);
      };
    }
  };

  Object.defineProperty(window, 'AMap', {
    configurable: true,
    get: () => current,
    set: (value) => {
      current = value;
      try { patch(value); } catch { /* 忽略 */ }
    },
  });
});

await page.goto(`${BASE}/trip/${TRIP_ID}`, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('.amap-container canvas', { timeout: 60_000 }).catch(() => {});
await page.waitForTimeout(12_000);

const result = await page.evaluate(() => ({
  hasAmap: typeof window.AMap === 'object',
  ready: document.querySelector('.amap-container')?.getAttribute('data-map-ready') ?? null,
  style: document.querySelector('.amap-container')?.getAttribute('data-map-style') ?? null,
  points: window.__polylinePoints || [],
}));

const points = result.points;
const maxPoints = points.length ? Math.max(...points) : 0;
const pass = result.hasAmap && points.length > 0 && maxPoints > 2;

console.log('AMap:', result.hasAmap, '| ready:', result.ready, '| style:', result.style);
console.log('折线构造次数:', points.length, '| 每段点数:', JSON.stringify(points));
console.log('最大点数:', maxPoints);

await page.screenshot({ path: '.evidence/polyline-browser.png' });
console.log('截图: .evidence/polyline-browser.png');
console.log(pass ? 'PASS：地图收到多点真实几何（不是两点直连）' : 'FAIL：未观察到多点几何');
await browser.close();
process.exit(pass ? 0 : 1);
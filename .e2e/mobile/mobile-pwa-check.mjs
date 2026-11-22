#!/usr/bin/env node
/**
 * 移动端（iPhone 13 / Pixel 7）PWA 安装性 + 离线恢复验收脚本。
 *
 * 为什么单独一个脚本、不塞进 travel-agent-web/e2e：
 *   playwright.config.ts 固定 serviceWorkers: 'block'（既有 e2e 用例依赖它屏蔽 SW），
 *   PWA 验收必须允许 SW，所以独立跑；不修改任何产品源码与既有配置。
 *
 * 前置（本脚本自己不起站）：
 *   1) 后端：http://127.0.0.1:18090（demo/demo123，RATE_LIMIT 关闭）
 *   2) 静态站：node .e2e/mobile/serve-dist.mjs --port 4173 --api http://127.0.0.1:18090
 *      （等价于把 travel-agent-web/dist + /api 反代挂到 4173）
 *   3) 浏览器：npx playwright install chromium；iPhone Safari 引擎保真需要
 *      npx playwright install webkit（未安装时自动退化为 chromium + iPhone 13 设备参数并记录）
 *
 * 用法：
 *   node .e2e/mobile/mobile-pwa-check.mjs \
 *     [--base http://127.0.0.1:4173] \
 *     [--api http://127.0.0.1:18090] \
 *     [--out .evidence/reports/mobile-pwa.json] \
 *     [--screenshots .evidence/screenshots] \
 *     [--only iphone|pixel] [--headed] [--timeout 30000]
 *
 * 退出码：0 = 全部 PASS（允许 SKIP）；1 = 有 FAIL；2 = 环境不可用（BLOCKED，未跑起来）。
 */
import { createRequire } from 'node:module';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
const require = createRequire(join(REPO, 'travel-agent-web', 'package.json'));
const { chromium, webkit, devices } = require('playwright');
const PLAYWRIGHT_VERSION = (() => {
  try {
    return require('playwright/package.json').version;
  } catch {
    return 'unknown';
  }
})();

// ---------------------------------------------------------------- CLI

function parseArgs(argv) {
  const opts = {
    base: 'http://127.0.0.1:4173',
    api: 'http://127.0.0.1:18090',
    out: join(REPO, '.evidence', 'reports', 'mobile-pwa.json'),
    screenshots: join(REPO, '.evidence', 'screenshots'),
    only: null,
    headed: false,
    timeout: 30000,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => argv[++i];
    if (arg === '--base') opts.base = String(next()).replace(/\/+$/, '');
    else if (arg === '--api') opts.api = String(next()).replace(/\/+$/, '');
    else if (arg === '--out') opts.out = resolve(String(next()));
    else if (arg === '--screenshots') opts.screenshots = resolve(String(next()));
    else if (arg === '--only') opts.only = String(next()).toLowerCase();
    else if (arg === '--headed') opts.headed = true;
    else if (arg === '--timeout') opts.timeout = Number(next());
    else if (arg === '--help' || arg === '-h') {
      process.stdout.write(
        'usage: node .e2e/mobile/mobile-pwa-check.mjs [--base URL] [--api URL] [--out FILE] [--screenshots DIR] [--only iphone|pixel] [--headed]\n',
      );
      process.exit(0);
    } else {
      process.stderr.write(`unknown arg: ${arg}\n`);
      process.exit(2);
    }
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const DEMO_USER = 'demo';
const DEMO_PASS = 'demo123';
const OFFLINE_BANNER = '[data-testid=offline-banner]';

// ---------------------------------------------------------------- 小工具

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function log(msg) {
  process.stdout.write(`[mobile-pwa] ${msg}\n`);
}

function truncate(text, max = 600) {
  const value = String(text ?? '').replace(/\r/g, '').trim();
  return value.length > max ? `${value.slice(0, max)}…` : value;
}

/** 单项结果；永远不抛，失败只记录 */
function check(id, name, status, detail = '') {
  return { id, name, status, detail: truncate(detail, 800) };
}

async function attempt(fn, fallbackDetail) {
  try {
    return await fn();
  } catch (err) {
    return { error: err?.message ?? String(err), hint: fallbackDetail };
  }
}

/** 横向溢出：取 html 与 body 的 scrollWidth 最大值，和 innerWidth 比，容差 1px（亚像素/滚动条） */
async function measureOverflow(page) {
  return page.evaluate(() => {
    const html = document.documentElement;
    const body = document.body;
    const scrollWidth = Math.max(html?.scrollWidth ?? 0, body?.scrollWidth ?? 0);
    const innerWidth = window.innerWidth;
    return {
      innerWidth,
      scrollWidth,
      overflowPx: scrollWidth - innerWidth,
      pass: scrollWidth <= innerWidth + 1,
      docClientWidth: html?.clientWidth ?? 0,
      horizontalScrollbar: scrollWidth > innerWidth + 1,
    };
  });
}

async function bodyText(page, max = 900) {
  return page.evaluate((limit) => {
    const text = document.body?.innerText ?? '';
    return text.replace(/\s+/g, ' ').slice(0, limit);
  }, max);
}

/** 等 service worker 真的接管当前页面（clientsClaim 生效需要一点时间） */
async function waitForController(page, timeoutMs = 15000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const controlled = await page.evaluate(() => !!navigator.serviceWorker?.controller);
    if (controlled) return true;
    await sleep(300);
  }
  return false;
}

async function readSwState(page) {
  return page.evaluate(async () => {
    const out = {
      supported: 'serviceWorker' in navigator,
      controllerScriptUrl: null,
      controllerState: null,
      registration: null,
      registrationError: null,
      caches: [],
    };
    try {
      const reg = await navigator.serviceWorker.ready;
      out.registration = {
        scope: reg.scope,
        activeState: reg.active?.state ?? null,
        activeScriptURL: reg.active?.scriptURL ?? null,
        waiting: !!reg.waiting,
        installing: !!reg.installing,
      };
      out.controllerScriptUrl = navigator.serviceWorker.controller?.scriptURL ?? null;
      out.controllerState = navigator.serviceWorker.controller?.state ?? null;
    } catch (err) {
      out.registrationError = String(err?.message ?? err);
    }
    try {
      out.caches = await caches.keys();
    } catch (err) {
      out.cachesError = String(err?.message ?? err);
    }
    return out;
  });
}

async function readRuntimeCacheEntries(page, cacheName = 'itinerary-v1') {
  return page.evaluate(async (name) => {
    try {
      const keys = await caches.keys();
      if (!keys.includes(name)) return { exists: false, urls: [] };
      const cache = await caches.open(name);
      const requests = await cache.keys();
      return { exists: true, urls: requests.map((r) => r.url) };
    } catch (err) {
      return { exists: false, urls: [], error: String(err?.message ?? err) };
    }
  }, cacheName);
}

/** 判断浏览器错误页/白屏：标题命中 Chromium/WebKit 错误页文案，或 body 几乎没有可见文本 */
async function readPageState(page) {
  return page.evaluate(() => {
    const title = document.title;
    const text = (document.body?.innerText ?? '').replace(/\s+/g, ' ').trim();
    const root = document.getElementById('root');
    return {
      url: location.href,
      title,
      pathname: location.pathname,
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      onLine: navigator.onLine,
      textLength: text.length,
      textSnippet: text.slice(0, 800),
      rootChildCount: root ? root.childElementCount : 0,
      hasOfflineBanner: !!document.querySelector('[data-testid=offline-banner]'),
      hasSessionRecoveryFailed: !!document.querySelector('[data-testid=session-recovery-failed]'),
      hasLoginForm: !!document.querySelector('input#username'),
      hasAppShell: !!document.querySelector('.app-shell'),
      hasTripHeading: !!document.querySelector('.dash-greeting h1'),
      tripHeading: (document.querySelector('.dash-greeting h1')?.textContent ?? '').trim(),
      timelineRows: document.querySelectorAll('.t-row').length,
      runtimeCacheHint: null,
    };
  });
}

function isBrowserErrorPage(state) {
  const t = `${state.title} ${state.textSnippet}`;
  return /ERR_INTERNET_DISCONNECTED|ERR_CONNECTION_REFUSED|ERR_FAILED|This site can.t be reached|无法访问此网站|网页无法打开|WebKitErrorDomain/i.test(
    t,
  );
}

/** 记录 /auth/me 的请求/响应/失败（离线时观察「是否被踢回登录页」的关键证据） */
function watchAuthMe(page) {
  const record = { requests: [], responses: [], failures: [] };
  const onRequest = (req) => {
    if (req.url().includes('/api/auth/me')) record.requests.push({ method: req.method(), url: req.url() });
  };
  const onResponse = async (res) => {
    if (!res.url().includes('/api/auth/me')) return;
    let bodySnippet = '';
    try {
      bodySnippet = truncate(await res.text(), 200);
    } catch {
      /* ignore */
    }
    record.responses.push({ status: res.status(), url: res.url(), bodySnippet });
  };
  const onFailed = (req) => {
    if (!req.url().includes('/api/auth/me')) return;
    record.failures.push({ url: req.url(), failure: req.failure()?.errorText ?? 'unknown' });
  };
  page.on('request', onRequest);
  page.on('response', (res) => void onResponse(res));
  page.on('requestfailed', onFailed);
  return {
    record,
    detach() {
      page.off('request', onRequest);
      page.off('requestfailed', onFailed);
    },
  };
}

// ---------------------------------------------------------------- 设备矩阵

const DEVICE_MATRIX = [
  {
    id: 'iphone-13',
    label: 'iPhone 13',
    preferWebkit: true,
    descriptor: devices['iPhone 13'],
    slug: 'iphone',
  },
  {
    id: 'pixel-7',
    label: 'Pixel 7',
    preferWebkit: false,
    descriptor: devices['Pixel 7'],
    slug: 'pixel',
  },
];

/** 设备描述符 → newContext 选项（defaultBrowserType 不是 context 选项，必须剔除） */
function contextOptions(descriptor) {
  const { defaultBrowserType, ...rest } = descriptor;
  return { ...rest, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' };
}

// ---------------------------------------------------------------- 单设备验收

async function runDevice(group, browserPool) {
  const result = {
    device: group.label,
    deviceId: group.id,
    requestedEngine: group.preferWebkit ? 'webkit' : 'chromium',
    engine: null,
    engineFallback: null,
    deviceProfile: {
      viewport: group.descriptor.viewport,
      deviceScaleFactor: group.descriptor.deviceScaleFactor,
      isMobile: group.descriptor.isMobile,
      hasTouch: group.descriptor.hasTouch,
    },
    ua: null,
    checks: [],
    evidence: {
      login: null,
      travel: null,
      trip: null,
      manifest: null,
      sw: null,
      offline: null,
      recovery: null,
    },
    screenshots: [],
    deviceErrors: [],
  };

  // 1) 选择引擎：iPhone 优先 webkit（真实 Safari 引擎），不可用则退化 chromium + iPhone 参数
  let browser = null;
  let engine = null;
  if (group.preferWebkit) {
    const wk = browserPool.webkit;
    if (wk.ok) {
      browser = wk.browser;
      engine = 'webkit';
    } else {
      result.engineFallback = {
        reason: wk.reason,
        hint: 'npx playwright install webkit（安装后重跑即用真 Safari 引擎）',
      };
      engine = 'chromium(iphone-emulation)';
      browser = browserPool.chromium.browser;
    }
  } else {
    engine = 'chromium';
    browser = browserPool.chromium.browser;
  }
  if (!browser) {
    result.deviceErrors.push('没有可用浏览器实例，本组跳过');
    result.checks.push(
      check('engine', '浏览器引擎可用', 'BLOCKED', `webkit: ${browserPool.webkit.reason}; chromium: ${browserPool.chromium.reason}`),
    );
    return result;
  }
  result.engine = engine;

  const context = await browser.newContext(contextOptions(group.descriptor));
  const page = await context.newPage();
  page.setDefaultTimeout(opts.timeout);
  const consoleErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(truncate(msg.text(), 200));
  });

  const shot = async (name) => {
    try {
      mkdirSync(opts.screenshots, { recursive: true });
      const file = join(opts.screenshots, `mobile-${group.slug}-${name}.png`);
      await page.screenshot({ path: file, fullPage: false });
      result.screenshots.push(file);
      return file;
    } catch (err) {
      result.deviceErrors.push(`截图 ${name} 失败: ${err?.message ?? err}`);
      return null;
    }
  };

  try {
    // ------------------------------------------------ a) 登录页在移动视口可用 + 真实登录
    const loginEvidence = { loginPageOverflow: null, loggedIn: false, loginUrl: null, postLoginUrl: null };
    const login = await attempt(async () => {
      await page.goto(`${opts.base}/login`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('input#username', { state: 'visible' });
      loginEvidence.loginPageOverflow = await measureOverflow(page);
      loginEvidence.loginUrl = page.url();
      loginEvidence.viewport = await page.evaluate(() => ({
        innerWidth: window.innerWidth,
        innerHeight: window.innerHeight,
        dpr: window.devicePixelRatio,
        ua: navigator.userAgent,
      }));
      await shot('login');

      await page.fill('input#username', DEMO_USER);
      await page.fill('input#password', DEMO_PASS);
      await Promise.all([
        page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: opts.timeout }),
        page.click('button[type=submit]'),
      ]);
      await page.waitForSelector('.app-shell', { state: 'visible', timeout: opts.timeout });
      loginEvidence.postLoginUrl = page.url();
      loginEvidence.loggedIn = !new URL(page.url()).pathname.startsWith('/login');
      return loginEvidence;
    }, '登录失败：检查后端 18090 是否可登录、serve-dist 反代是否正常');
    result.evidence.login = login;
    result.ua = loginEvidence.viewport?.ua ?? null;
    const loginOk =
      !login.error &&
      loginEvidence.loggedIn &&
      loginEvidence.loginPageOverflow?.pass !== false;
    result.checks.push(
      check(
        'a_login',
        '登录页在移动视口可用（能登录 demo/demo123）',
        loginOk ? 'PASS' : 'FAIL',
        login.error
          ? `${login.error} | ${login.hint ?? ''}`
          : `loginUrl=${loginEvidence.loginUrl} postLoginUrl=${loginEvidence.postLoginUrl} innerWidth=${loginEvidence.loginPageOverflow?.innerWidth} overflowPx=${loginEvidence.loginPageOverflow?.overflowPx}`,
      ),
    );
    result.checks.push(
      check(
        'a_login_no_overflow',
        '登录页无横向溢出（scrollWidth <= innerWidth+1）',
        loginEvidence.loginPageOverflow?.pass ? 'PASS' : 'FAIL',
        loginEvidence.loginPageOverflow
          ? `scrollWidth=${loginEvidence.loginPageOverflow.scrollWidth} innerWidth=${loginEvidence.loginPageOverflow.innerWidth} overflowPx=${loginEvidence.loginPageOverflow.overflowPx}`
          : '未测得（登录页未打开）',
      ),
    );

    // ------------------------------------------------ b) 行程列表 / 行程详情：无横向溢出 + 主内容可见
    const travelEvidence = {};
    const travel = await attempt(async () => {
      await page.goto(`${opts.base}/travel`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.app-shell', { state: 'visible' });
      // 列表数据来自 GET /api/trips
      await page.waitForSelector('.trip-row, .empty-trips', { timeout: opts.timeout });
      travelEvidence.overflow = await measureOverflow(page);
      travelEvidence.tripRows = await page.locator('.trip-row').count();
      travelEvidence.mainVisible = await page.locator('main.app-content').isVisible();
      travelEvidence.heading = await page.locator('.dash-greeting h1').first().textContent();
      travelEvidence.text = await bodyText(page, 400);
      return travelEvidence;
    }, '行程列表未渲染：确认已登录且 GET /api/trips 有数据');
    result.evidence.travel = travel;
    const travelOk = !travel.error && travelEvidence.overflow?.pass && travelEvidence.mainVisible && travelEvidence.tripRows > 0;
    result.checks.push(
      check(
        'b_travel_list',
        '行程列表：无横向溢出且主内容可见',
        travelOk ? 'PASS' : 'FAIL',
        travel.error
          ? `${travel.error} | ${travel.hint ?? ''}`
          : `tripRows=${travelEvidence.tripRows} innerWidth=${travelEvidence.overflow?.innerWidth} scrollWidth=${travelEvidence.overflow?.scrollWidth} overflowPx=${travelEvidence.overflow?.overflowPx} mainVisible=${travelEvidence.mainVisible} heading=${travelEvidence.heading}`,
      ),
    );

    const tripEvidence = {};
    const trip = await attempt(async () => {
      // 选一趟真实行程：优先 API 返回的第一条
      const trips = await page.evaluate(async () => {
        const res = await fetch('/api/trips', { credentials: 'include' });
        const json = await res.json();
        return Array.isArray(json?.data) ? json.data : [];
      });
      tripEvidence.tripCount = trips.length;
      const tripId = trips?.[0]?.id;
      if (!tripId) throw new Error('GET /api/trips 返回空列表，无法打开行程详情');
      tripEvidence.tripId = tripId;
      tripEvidence.tripName = trips[0].name;

      await page.goto(`${opts.base}/trip/${tripId}`, { waitUntil: 'domcontentloaded' });
      await page.waitForSelector('.app-shell', { state: 'visible' });
      await page.waitForSelector('.dash-greeting h1', { timeout: opts.timeout });
      // 有时间轴行 = itinerary 已渲染
      await page.waitForFunction(() => document.querySelectorAll('.t-row').length > 0, undefined, {
        timeout: opts.timeout,
      });
      tripEvidence.overflow = await measureOverflow(page);
      tripEvidence.timelineRows = await page.locator('.t-row').count();
      tripEvidence.mainVisible = await page.locator('main.app-content').isVisible();
      tripEvidence.heading = (await page.locator('.dash-greeting h1').first().textContent())?.trim();
      tripEvidence.subtitle = (await page.locator('.dash-greeting .dg-date').first().textContent())?.trim();

      // 打开「预算分析」抽屉，让 GET /api/trips/<id>/budget 也进运行时缓存（否则离线只暖到 itinerary）
      try {
        await page.getByRole('button', { name: /预算分析/ }).click({ timeout: 8000 });
        await page.waitForSelector('.ant-drawer-open', { timeout: 8000 });
        tripEvidence.budgetDrawerOpened = true;
        await sleep(800);
        await page.keyboard.press('Escape');
        await sleep(300);
      } catch (err) {
        tripEvidence.budgetDrawerOpened = false;
        tripEvidence.budgetDrawerError = truncate(String(err?.message ?? err), 200);
      }
      await sleep(500);
      tripEvidence.text = await bodyText(page, 500);
      await shot('trip');
      return tripEvidence;
    }, '行程详情未渲染：确认 GET /api/trips/<id>/itinerary 有数据');
    result.evidence.trip = trip;
    const tripOk = !trip.error && tripEvidence.overflow?.pass && tripEvidence.mainVisible && tripEvidence.timelineRows > 0;
    result.checks.push(
      check(
        'b_trip_detail',
        '行程详情：无横向溢出且主内容（时间轴）可见',
        tripOk ? 'PASS' : 'FAIL',
        trip.error
          ? `${trip.error} | ${trip.hint ?? ''}`
          : `tripId=${tripEvidence.tripId} timelineRows=${tripEvidence.timelineRows} innerWidth=${tripEvidence.overflow?.innerWidth} scrollWidth=${tripEvidence.overflow?.scrollWidth} overflowPx=${tripEvidence.overflow?.overflowPx} budgetDrawerOpened=${tripEvidence.budgetDrawerOpened}`,
      ),
    );

    // ------------------------------------------------ c) PWA 可安装性硬指标
    const manifestEvidence = await attempt(async () => {
      const out = await page.evaluate(async () => {
        const res = await fetch('/manifest.webmanifest', { cache: 'no-store' });
        const text = await res.text();
        let json = null;
        try {
          json = JSON.parse(text);
        } catch {
          json = null;
        }
        const link = document.querySelector('link[rel="manifest"]');
        return {
          status: res.status,
          contentType: res.headers.get('content-type'),
          json,
          linkHref: link?.getAttribute('href') ?? null,
        };
      });
      const icons = Array.isArray(out.json?.icons) ? out.json.icons : [];
      const sizes = icons.map((i) => String(i.sizes ?? ''));
      return {
        ...out,
        iconSizes: sizes,
        has192: sizes.some((s) => s.split(/\s+/).includes('192x192')),
        has512: sizes.some((s) => s.split(/\s+/).includes('512x512')),
      };
    }, 'manifest 拉取失败：确认 dist/manifest.webmanifest 存在且 serve-dist 正常');
    result.evidence.manifest = manifestEvidence;

    const manifestFieldsOk =
      !manifestEvidence.error &&
      manifestEvidence.status === 200 &&
      !!manifestEvidence.json?.name &&
      !!manifestEvidence.json?.short_name &&
      !!manifestEvidence.json?.start_url &&
      manifestEvidence.json?.display === 'standalone' &&
      manifestEvidence.has192 &&
      manifestEvidence.has512;
    result.checks.push(
      check(
        'c_manifest',
        'manifest 200 且 name/short_name/start_url/display=standalone/icons(192,512) 齐全',
        manifestFieldsOk ? 'PASS' : 'FAIL',
        manifestEvidence.error
          ? `${manifestEvidence.error} | ${manifestEvidence.hint ?? ''}`
          : `status=${manifestEvidence.status} type=${manifestEvidence.contentType} name=${manifestEvidence.json?.name} short_name=${manifestEvidence.json?.short_name} start_url=${manifestEvidence.json?.start_url} display=${manifestEvidence.json?.display} sizes=${manifestEvidence.iconSizes.join('/')}`,
      ),
    );
    result.checks.push(
      check(
        'c_manifest_link',
        'index.html 含 <link rel="manifest">',
        manifestEvidence.linkHref ? 'PASS' : 'FAIL',
        manifestEvidence.linkHref ? `href=${manifestEvidence.linkHref}` : 'document 中没有 link[rel=manifest]',
      ),
    );

    const swEvidence = await attempt(async () => {
      const ready = await Promise.race([
        page.evaluate(async () => {
          const reg = await navigator.serviceWorker.ready;
          return { scope: reg.scope, activeState: reg.active?.state ?? null, scriptURL: reg.active?.scriptURL ?? null };
        }),
        sleep(20000).then(() => ({ timeout: true })),
      ]);
      const controlled = await waitForController(page, 15000);
      const state = await readSwState(page);
      return { ready, controlled, state };
    }, 'service worker 未注册：确认 /registerSW.js 与 /sw.js 可访问（需 127.0.0.1 或 https 等安全上下文）');
    result.evidence.sw = swEvidence;
    result.checks.push(
      check(
        'c_sw_active',
        'navigator.serviceWorker.ready 有 active worker',
        !swEvidence.error && swEvidence.state?.registration?.activeState === 'activated' ? 'PASS' : 'FAIL',
        swEvidence.error
          ? `${swEvidence.error} | ${swEvidence.hint ?? ''}`
          : `activeState=${swEvidence.state?.registration?.activeState} scope=${swEvidence.state?.registration?.scope} script=${swEvidence.state?.registration?.activeScriptURL}`,
      ),
    );
    result.checks.push(
      check(
        'c_sw_controller',
        '页面被 service worker 控制（controller 非空）',
        swEvidence.controlled ? 'PASS' : 'FAIL',
        swEvidence.controlled
          ? `controller=${swEvidence.state?.controllerScriptUrl}`
          : `controller=null（等待 15s 仍未被接管）`,
      ),
    );

    // ------------------------------------------------ d) 运行时缓存 + 离线 reload
    const cacheEvidence = await attempt(
      async () => {
        const runtime = await readRuntimeCacheEntries(page, 'itinerary-v1');
        const all = await page.evaluate(() => caches.keys());
        return { runtime, allCacheNames: all };
      },
      '读取 Cache Storage 失败',
    );
    result.evidence.runtimeCache = cacheEvidence;
    const cachedUrls = cacheEvidence.error ? [] : cacheEvidence.runtime?.urls ?? [];
    const cachedItinerary = cachedUrls.some((u) => /\/api\/trips\/\d+\/itinerary/.test(u));
    const cachedBudget = cachedUrls.some((u) => /\/api\/trips\/\d+\/budget/.test(u));
    result.checks.push(
      check(
        'd_runtime_cache_warm',
        'itinerary-v1 运行时缓存已命中 itinerary（打开过的行程）',
        cachedItinerary ? 'PASS' : 'FAIL',
        cacheEvidence.error
          ? cacheEvidence.error
          : `caches=${(cacheEvidence.allCacheNames ?? []).join(',')} itineraryEntries=${cachedUrls.filter((u) => /itinerary/.test(u)).join(' | ') || '(空)'}`,
      ),
    );
    result.checks.push(
      check(
        'd_runtime_cache_budget',
        'itinerary-v1 运行时缓存已命中 budget（打开过预算分析）',
        cachedBudget ? 'PASS' : 'FAIL',
        cachedBudget
          ? cachedUrls.filter((u) => /budget/.test(u)).join(' | ')
          : '缓存里没有 /budget：Trip 页仅在打开「预算分析」抽屉时才请求它',
      ),
    );

    // 离线：setOffline(true) + reload，观察 app shell / 缓存数据 / 被踢回登录页
    const authWatch = watchAuthMe(page);
    const offlineEvidence = {};
    await attempt(async () => {
      offlineEvidence.offlineBeforeReload = await page.evaluate(() => navigator.onLine);
      await context.setOffline(true);
      await sleep(400);
      try {
        await page.reload({ waitUntil: 'domcontentloaded', timeout: 25000 });
      } catch (err) {
        offlineEvidence.reloadError = truncate(String(err?.message ?? err), 300);
      }
      // 给 React 一次渲染与 SW 回退的时间
      await sleep(3500);
      const state = await readPageState(page);
      offlineEvidence.state = state;
      offlineEvidence.browserErrorPage = isBrowserErrorPage(state);
      offlineEvidence.swOffline = await attempt(() => readSwState(page), 'offline SW 读取失败');
      offlineEvidence.offlineAfterReload = await page.evaluate(() => navigator.onLine);
      await shot('offline');
      return offlineEvidence;
    }, '离线 reload 失败');
    authWatch.detach();
    offlineEvidence.authMe = authWatch.record;
    result.evidence.offline = offlineEvidence;

    const off = offlineEvidence.state ?? {};
    const shellOk = !!off && !offlineEvidence.browserErrorPage && (off.rootChildCount ?? 0) > 0 && (off.textLength ?? 0) > 20;
    result.checks.push(
      check(
        'd_offline_shell',
        '离线 reload 后 app shell 能打开（非浏览器错误页/白屏）',
        shellOk ? 'PASS' : 'FAIL',
        shellOk
          ? `url=${off.url} title=${off.title} rootChildren=${off.rootChildCount} textLength=${off.textLength}`
          : `url=${off.url ?? '?'} title=${off.title ?? '?'} rootChildren=${off.rootChildCount ?? '?'} textLength=${off.textLength ?? '?'} browserErrorPage=${offlineEvidence.browserErrorPage} reloadError=${offlineEvidence.reloadError ?? ''}`,
      ),
    );
    result.checks.push(
      check(
        'd_offline_banner',
        '离线提示条 [data-testid=offline-banner] 出现',
        off.hasOfflineBanner ? 'PASS' : 'FAIL',
        off.hasOfflineBanner ? 'offline-banner 可见' : `未出现（navigator.onLine=${off.onLine}）`,
      ),
    );
    const cachedDataRendered =
      (off.timelineRows ?? 0) > 0 &&
      !!off.tripHeading &&
      !off.hasSessionRecoveryFailed &&
      !off.hasLoginForm;
    result.checks.push(
      check(
        'd_offline_cached_data',
        '离线时已看过的行程数据仍渲染',
        cachedDataRendered ? 'PASS' : 'FAIL',
        `pathname=${off.pathname} tripHeading=${off.tripHeading || '(空)'} timelineRows=${off.timelineRows} sessionRecoveryFailed=${off.hasSessionRecoveryFailed} loginForm=${off.hasLoginForm} text=${truncate(off.textSnippet, 260)}`,
      ),
    );
    // 「是否把用户踢回登录页」：踢回 = 离线后 pathname 变成 /login 或出现登录表单
    const kickedToLogin = !!off.hasLoginForm || off.pathname === '/login';
    result.checks.push(
      check(
        'd_offline_auth_me',
        '离线时 /api/auth/me 的行为（不把用户踢回登录页）',
        !kickedToLogin && !offlineEvidence.browserErrorPage ? 'PASS' : 'FAIL',
        `kickedToLogin=${kickedToLogin} pathname=${off.pathname} sessionRecoveryFailed=${off.hasSessionRecoveryFailed} meRequests=${JSON.stringify(offlineEvidence.authMe?.requests ?? [])} meFailures=${JSON.stringify(offlineEvidence.authMe?.failures ?? [])} meResponses=${JSON.stringify(offlineEvidence.authMe?.responses ?? [])}`,
      ),
    );
    result.checks.push(
      check(
        'd_offline_note',
        '离线时页面实际 URL/标题/关键文本（如实记录，不代表断言）',
        'INFO',
        `url=${off.url ?? '?'} title=${off.title ?? '?'} text=${truncate(off.textSnippet, 400)}`,
      ),
    );

    // ------------------------------------------------ e) 恢复在线
    const recoveryEvidence = {};
    const recovery = await attempt(async () => {
      await context.setOffline(false);
      await sleep(500);
      try {
        await page.reload({ waitUntil: 'domcontentloaded', timeout: 25000 });
      } catch (err) {
        recoveryEvidence.reloadError = truncate(String(err?.message ?? err), 300);
      }
      await sleep(1500);
      await page.waitForFunction(
        () => document.querySelectorAll('.t-row').length > 0 || !!document.querySelector('input#username'),
        undefined,
        { timeout: opts.timeout },
      );
      const state = await readPageState(page);
      recoveryEvidence.state = state;
      recoveryEvidence.onLine = await page.evaluate(() => navigator.onLine);
      recoveryEvidence.overflow = await measureOverflow(page);
      return recoveryEvidence;
    }, '恢复在线后页面未恢复');
    result.evidence.recovery = recovery;

    const rec = recoveryEvidence.state ?? {};
    const recovered = !recovery.error && (rec.timelineRows ?? 0) > 0 && !rec.hasLoginForm && (recoveryEvidence.overflow?.pass ?? false);
    result.checks.push(
      check(
        'e_recovery',
        '恢复在线后行程数据重新渲染且无横向溢出',
        recovered ? 'PASS' : 'FAIL',
        recovery.error
          ? `${recovery.error} | ${recovery.hint ?? ''}`
          : `url=${rec.url} timelineRows=${rec.timelineRows} loginForm=${rec.hasLoginForm} onLine=${recoveryEvidence.onLine} overflowPx=${recoveryEvidence.overflow?.overflowPx}`,
      ),
    );

    // 控制台报错只作为证据收集，不计入 PASS/FAIL（地图瓦片/外部资源离线报错是预期）
    result.consoleErrors = consoleErrors.slice(0, 20);
  } finally {
    await context.close().catch(() => {});
  }

  return result;
}

// ---------------------------------------------------------------- 报告输出

function buildSummary(report) {
  const rows = [];
  for (const dev of report.devices) {
    for (const item of dev.checks) {
      rows.push({ device: dev.device, engine: dev.engine, id: item.id, name: item.name, status: item.status, detail: item.detail });
    }
  }
  return rows;
}

function overallStatus(report) {
  if (report.blocked) return 'BLOCKED';
  const rows = buildSummary(report);
  if (rows.some((r) => r.status === 'FAIL')) return 'FAIL';
  if (rows.some((r) => r.status === 'BLOCKED')) return 'BLOCKED';
  return 'PASS';
}

const CHECKLIST = `### 真机自测清单（人工复验，仿真 ≠ 真机）

| 平台 | 步骤 | 预期 |
| --- | --- | --- |
| iPhone Safari | 1. Safari 打开 \`http://<host>:4173/\`，登录 demo/demo123<br>2. 分享 → 添加到主屏幕<br>3. 从主屏图标启动（standalone） | 主屏出现「个人助理」图标；启动后无 Safari 地址栏（display=standalone）；状态栏颜色为主题色 #1677ff |
| iPhone Safari | 4. 打开「旅行规划」→ 点开一趟行程 → 点「预算分析」<br>5. 打开飞行模式<br>6. 下拉刷新或完全杀掉后从主屏图标重开 | 页面不白屏、不出现 Safari 错误页；顶部出现「离线：显示缓存数据」提示条；已看过的行程（时间轴/预算）仍可见；**不应被踢回登录页** |
| iPhone Safari | 7. 关闭飞行模式 | 提示条自动消失；页面数据自动/刷新后恢复；无持续报错 |
| Android Chrome | 1. Chrome 打开 \`http://<host>:4173/\`，登录<br>2. 菜单 →「安装应用」/「添加到主屏幕」<br>3. 从桌面图标启动 | 出现安装横幅/菜单项（manifest + SW 满足可安装性）；standalone 启动；无地址栏 |
| Android Chrome | 4. 浏览行程与预算后打开飞行模式<br>5. 从桌面图标冷启动 | app shell 由 SW 预缓存打开；离线提示条出现；已缓存行程可见；不被踢回登录页 |
| Android Chrome | 6. 关闭飞行模式并下拉刷新 | 恢复正常在线数据；提示条消失 |
| 桌面 Chrome | 1. 打开 \`http://<host>:4173/\`，登录<br>2. 地址栏右侧安装图标 / 菜单 → 安装「AI 个人助理」 | manifest 200、SW 已激活、controller 非空 → 安装入口可用 |
| 桌面 Chrome | 3. DevTools → Application → Service Workers / Cache Storage | \`sw.js\` activated；Cache Storage 有预缓存与 \`itinerary-v1\`；打开过行程后出现 itinerary/budget 条目 |
| 桌面 Chrome | 4. DevTools → Network → Offline，然后刷新 | app shell 打开、离线提示条出现；已看过行程尽量渲染（当前实现受 /auth/me 离线失败影响，见报告结论） |`;

function renderMarkdown(report) {
  const lines = [];
  lines.push('<!-- 本段由 .e2e/mobile/mobile-pwa-check.mjs 自动生成，重跑会覆盖同标记区间 -->');
  lines.push(`- 生成时间：${report.generatedAt}`);
  lines.push(`- 总体结论：**${report.overall}**`);
  lines.push(`- 静态站：\`${report.environment.base}\`；后端：\`${report.environment.api}\``);
  lines.push(`- 浏览器：Playwright ${report.environment.playwright} / Node ${report.environment.node}`);
  if (report.blocked) lines.push(`- 阻塞原因：${report.blocked.reason}`);
  lines.push('');
  lines.push('| 设备 | 引擎 | 检查项 | 结果 | 证据 |');
  lines.push('| --- | --- | --- | --- | --- |');
  for (const row of buildSummary(report)) {
    lines.push(`| ${row.device} | ${row.engine ?? '-'} | ${row.id} | ${row.status} | ${String(row.detail ?? '').replace(/\|/g, '\\|')} |`);
  }
  lines.push('');
  return lines.join('\n');
}

function writeReports(report) {
  mkdirSync(dirname(opts.out), { recursive: true });
  writeFileSync(opts.out, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  log(`JSON 报告 -> ${opts.out}`);

  // 同步刷新 .evidence/reports/mobile-pwa.md 里的自动摘要区块（保留人工维护的真机清单）
  const mdPath = join(dirname(opts.out), 'mobile-pwa.md');
  const BEGIN = '<!-- AUTO:MOBILE-PWA:BEGIN -->';
  const END = '<!-- AUTO:MOBILE-PWA:END -->';
  const block = `${BEGIN}\n${renderMarkdown(report)}\n${END}`;
  let md = existsSync(mdPath) ? readFileSync(mdPath, 'utf8') : '';
  if (md.includes(BEGIN) && md.includes(END)) {
    md = md.replace(new RegExp(`${BEGIN}[\\s\\S]*?${END}`), block);
  } else if (md) {
    md = `${block}\n\n${md}`;
  } else {
    md = `# 移动端 PWA 验收摘要\n\n${block}\n\n${CHECKLIST}\n`;
  }
  if (!md.includes('真机自测清单')) md = `${md}\n\n${CHECKLIST}\n`;
  writeFileSync(mdPath, md, 'utf8');
  log(`Markdown 摘要 -> ${mdPath}`);
}

// ---------------------------------------------------------------- main

async function preflight() {
  const result = { base: { ok: false, detail: '' }, api: { ok: false, detail: '' } };
  try {
    const res = await fetch(`${opts.base}/manifest.webmanifest`, { signal: AbortSignal.timeout(8000) });
    result.base = { ok: res.status === 200, detail: `GET ${opts.base}/manifest.webmanifest -> ${res.status}` };
  } catch (err) {
    result.base = { ok: false, detail: `无法访问 ${opts.base}：${err?.message ?? err}` };
  }
  try {
    const res = await fetch(`${opts.api}/api/auth/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username: DEMO_USER, password: DEMO_PASS }),
      signal: AbortSignal.timeout(8000),
    });
    result.api = { ok: res.status === 200, detail: `POST ${opts.api}/api/auth/login -> ${res.status}` };
  } catch (err) {
    result.api = { ok: false, detail: `无法访问 ${opts.api}：${err?.message ?? err}` };
  }
  return result;
}

async function main() {
  const startedAt = new Date().toISOString();
  const report = {
    schema: 'mobile-pwa/1',
    generatedAt: startedAt,
    environment: {
      base: opts.base,
      api: opts.api,
      node: process.version,
      platform: `${process.platform} ${process.arch}`,
      playwright: PLAYWRIGHT_VERSION,
      cwd: REPO,
      command: `node .e2e/mobile/mobile-pwa-check.mjs ${process.argv.slice(2).join(' ')}`.trim(),
    },
    preflight: null,
    devices: [],
    overall: 'BLOCKED',
    blocked: null,
    notes: [
      'PASS/FAIL 均来自本次真实浏览器运行；未运行的项标注 BLOCKED，不做推断。',
      'iPhone 组优先 webkit（真 Safari 引擎）；webkit 未安装时退化为 chromium + devices["iPhone 13"]，并在 engineFallback 里记录。',
      '离线判定用 context.setOffline(true)（CDP 网络模拟），等价于飞行模式；navigator.onLine 会随之为 false。',
    ],
    checklist: CHECKLIST,
  };

  report.preflight = await preflight();
  log(`preflight base=${report.preflight.base.detail} | api=${report.preflight.api.detail}`);
  if (!report.preflight.base.ok) {
    report.blocked = { kind: 'base-unreachable', reason: report.preflight.base.detail };
    report.devices = DEVICE_MATRIX.map((g) => ({
      device: g.label,
      deviceId: g.id,
      engine: null,
      checks: [check('engine', '浏览器验收', 'BLOCKED', `静态站不可用：${report.preflight.base.detail}`)],
      screenshots: [],
    }));
    report.overall = overallStatus(report);
    writeReports(report);
    process.exitCode = 2;
    return;
  }

  // 浏览器池：chromium 必须可用；webkit 尽力而为
  const browserPool = { chromium: { ok: false, browser: null, reason: null }, webkit: { ok: false, browser: null, reason: null } };
  try {
    browserPool.chromium.browser = await chromium.launch({ headless: !opts.headed });
    browserPool.chromium.ok = true;
  } catch (err) {
    browserPool.chromium.reason = truncate(String(err?.message ?? err), 400);
    log(`chromium 启动失败：${browserPool.chromium.reason}`);
  }
  try {
    browserPool.webkit.browser = await webkit.launch({ headless: !opts.headed });
    browserPool.webkit.ok = true;
  } catch (err) {
    browserPool.webkit.reason = truncate(String(err?.message ?? err), 400);
    log(`webkit 启动失败（将退化 chromium + iPhone 参数）：${browserPool.webkit.reason}`);
  }

  const groups = DEVICE_MATRIX.filter((g) => !opts.only || g.id.startsWith(opts.only) || g.slug === opts.only);
  for (const group of groups) {
    log(`=== ${group.label}（${group.preferWebkit ? 'webkit 优先' : 'chromium'}）===`);
    const devResult = await runDevice(group, browserPool);
    report.devices.push(devResult);
    for (const c of devResult.checks) log(`  ${c.status.padEnd(4)} ${c.id}: ${c.name}`);
  }

  await browserPool.chromium.browser?.close().catch(() => {});
  await browserPool.webkit.browser?.close().catch(() => {});

  report.overall = overallStatus(report);
  writeReports(report);

  const rows = buildSummary(report);
  const counts = rows.reduce((acc, r) => ({ ...acc, [r.status]: (acc[r.status] ?? 0) + 1 }), {});
  log(`总体：${report.overall}  ${JSON.stringify(counts)}`);
  log(`截图：${report.devices.flatMap((d) => d.screenshots ?? []).join(', ') || '(无)'}`);
  process.exitCode = report.overall === 'PASS' ? 0 : report.overall === 'FAIL' ? 1 : 2;
}

main().catch((err) => {
  process.stderr.write(`[mobile-pwa] 未捕获异常：${err?.stack ?? err}\n`);
  process.exit(2);
});
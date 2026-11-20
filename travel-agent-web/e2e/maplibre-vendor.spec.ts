import { readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from '@playwright/test';
import { expect, expectOk } from './support';

/**
 * 地图引擎的运行时按需加载（包体预算的守门用例）。
 *
 * 背景：maplibre-gl 已被移出 Rollup 的 import 图，改为真正渲染地图时再从
 * `/vendor/maplibre/<version>/maplibre-gl.mjs` 动态加载（见 docs/guide/frontend-performance.md）。
 * 这条链路有两个断点，现有用例都覆盖不到：
 *   1) vendor 文件是否被正确发布（构建产物 / nginx MIME）；
 *   2) 浏览器能否真的把该 URL 当 ES module 执行 —— `.mjs` 被发成 application/octet-stream 时
 *      静态资源照样 200，但 import() 失败，线上表现就是地图静默降级成提示文案。
 * 因此这里在同一站点上下文里真的 import 一次，断言导出与版本。
 *
 * 版本号不硬编码：直接读构建产物 dist/vendor/maplibre/<version>。
 * 不依赖演示数据：没有"已打卡"行程也能验证加载器本身。
 */

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** 从 dist 里发现当前地图引擎版本（构建产物是唯一事实来源） */
function discoverVendorVersion(): string {
  const vendorRoot = path.join(webRoot, 'dist', 'vendor', 'maplibre');
  const versions = readdirSync(vendorRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
  if (versions.length === 0) {
    throw new Error(`dist/vendor/maplibre 下没有版本目录，先执行 npm run build：${vendorRoot}`);
  }
  return versions[versions.length - 1];
}

test('地图引擎 vendor 文件可发布且按 JavaScript 返回', async ({ page }) => {
  const version = discoverVendorVersion();
  const url = `/vendor/maplibre/${version}/maplibre-gl.mjs`;

  await page.goto('/login');
  const response = await page.request.get(url);
  await expectOk(response, `地图引擎主模块 ${url}`);

  const contentType = response.headers()['content-type'] ?? '';
  // 关键断言：必须是 JS。nginx 未声明 .mjs 时会返回 application/octet-stream，
  // 浏览器拒绝执行模块（travel-agent-web/nginx.conf 里对应的 location 就是为此存在）。
  expect(contentType, '地图引擎必须按 JavaScript 返回，否则浏览器拒绝执行 ES module').toMatch(
    /javascript/i,
  );
});

test('浏览器可 import 地图引擎模块并拿到核心导出', async ({ page }) => {
  const version = discoverVendorVersion();
  await page.goto('/login');

  // 在页面上下文里真的 import 一次：覆盖「文件存在但无法作为模块执行」的所有情况。
  const summary = await page.evaluate(async (ver) => {
    const moduleUrl = new URL(`/vendor/maplibre/${ver}/maplibre-gl.mjs`, window.location.origin).href;
    const mod = (await import(/* @vite-ignore */ moduleUrl)) as Record<string, unknown>;
    const required = ['Map', 'Marker', 'Popup', 'LngLatBounds', 'AttributionControl'];
    const missing = required.filter((name) => typeof mod[name] !== 'function');
    const getVersion = mod.getVersion as undefined | (() => string);
    return {
      missing,
      version: typeof getVersion === 'function' ? getVersion() : null,
      exports: Object.keys(mod).length,
    };
  }, version);

  expect(summary.missing, '地图引擎缺少必要的导出（maplibreLoader 的 REQUIRED_EXPORTS）').toEqual(
    [],
  );
  expect(summary.version, '运行时版本必须与 vendor 目录版本一致').toBe(version);
  expect(summary.exports).toBeGreaterThan(10);
});

test('worker 与样式同目录可解析（maplibre 用 import.meta.url 推导相对路径）', async ({
  page,
}) => {
  const version = discoverVendorVersion();
  await page.goto('/login');

  // worker 必须与主模块同目录同名：maplibre 内部按 `new URL('./maplibre-gl-worker.mjs', import.meta.url)`
  // 解析，改名或换目录都会让瓦片解析静默失效。
  for (const file of ['maplibre-gl-worker.mjs', 'maplibre-gl.css']) {
    const response = await page.request.get(`/vendor/maplibre/${version}/${file}`);
    await expectOk(response, `地图引擎同目录资源 ${file}`);
  }
});
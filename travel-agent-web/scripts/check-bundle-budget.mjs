#!/usr/bin/env node
/**
 * 前端包体预算检查（读 dist/，不重新构建）。
 *
 * 用法：
 *   npm run build:check        # 构建后自动检查
 *   node scripts/check-bundle-budget.mjs
 *
 * 设计要点：
 * - 地图引擎（maplibre）已移出 Rollup 的 import 图，单独放在 dist/vendor/maplibre/<version>/，
 *   所以「assets/*.js 单文件」预算不把 vendor 算进去，vendor 用 gzip 预算单独卡。
 * - gzip 体积用 zlib level 9（与 rollup-plugin-visualizer 的 gzipSize 口径一致），
 *   表示「静态预压缩/最高压缩」下的理论传输体积；nginx 默认压缩级别会略大一点，但差值稳定。
 * - 任何一项超限都 exit 1，并打印超限项 + 调整建议，便于 CI 直接当门禁。
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DIST = join(ROOT, 'dist');
const VENDOR_ROOT = join(DIST, 'vendor', 'maplibre');
const LOADER_PATH = join(ROOT, 'src', 'components', 'FootprintMap', 'maplibreLoader.ts');

const BUDGET = {
  assetJs: 800_000,
  entryJs: 600_000,
  // 首屏 CSS（index.html 里 rel=stylesheet 的部分，含 antd reset + global.css 合并产物）。
  // 用途：守住「样式按职责拆分、页面私有规则随路由 chunk 加载」这条结构，而不是比字节。
  // 2026-11-23 实测 57,156 B；拆分前 global.css 直接 import 的源文件合计 104,669 B。
  entryCss: 65_536,
  echarts: 600_000,
  maplibreMainGzip: 300_000,
  maplibreWorkerGzip: 160_000,
  precache: 2.3 * 1024 * 1024,
};

const gzipSize = (buffer) => gzipSync(buffer, { level: 9 }).length;
const bytes = (n) => `${n.toLocaleString('en-US')} B`;
const mib = (n) => `${(n / 1024 / 1024).toFixed(2)} MiB`;
const kib = (n) => `${(n / 1024).toFixed(1)} KiB`;

const failures = [];
const notes = [];
const pass = (label, detail) => console.log(`  ✓ ${label}${detail ? `：${detail}` : ''}`);
const fail = (label, detail, suggestion) => {
  console.log(`  ✗ ${label}${detail ? `：${detail}` : ''}`);
  failures.push({ label, detail, suggestion });
};

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walk(full));
    } else if (entry.isFile()) {
      out.push(full);
    }
  }
  return out;
}

/** 解析 index.html 里首屏直接加载的 JS（module script + modulepreload），去重后返回本地路径 */
function entryScriptUrls(html) {
  const urls = new Set();
  for (const match of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
    urls.add(match[1]);
  }
  for (const match of html.matchAll(/<link\b[^>]*>/g)) {
    const tag = match[0];
    if (!/\brel="modulepreload"/.test(tag)) continue;
    const href = /\bhref="([^"]+)"/.exec(tag);
    if (href) urls.add(href[1]);
  }
  return [...urls]
    .map((url) => url.split('?')[0].split('#')[0])
    .filter((url) => url.startsWith('/') && ['.js', '.mjs'].includes(extname(url)));
}

/** 解析 index.html 里首屏直接加载的样式（rel=stylesheet），去重后返回本地路径 */
function entryStyleUrls(html) {
  const urls = new Set();
  for (const match of html.matchAll(/<link\b[^>]*>/g)) {
    const tag = match[0];
    if (!/\brel="stylesheet"/.test(tag)) continue;
    const href = /\bhref="([^"]+)"/.exec(tag);
    if (href) urls.add(href[1]);
  }
  return [...urls]
    .map((url) => url.split('?')[0].split('#')[0])
    .filter((url) => url.startsWith('/') && extname(url) === '.css');
}

/** 从 workbox 生成的 sw.js 里取出 precache 清单（precacheAndRoute([...], {})） */
function readPrecacheUrls(swSource) {
  const marker = 'precacheAndRoute(';
  const callIndex = swSource.indexOf(marker);
  if (callIndex < 0) {
    throw new Error('dist/sw.js 里找不到 precacheAndRoute(...)，无法统计 precache 体积');
  }
  const arrayStart = swSource.indexOf('[', callIndex);
  if (arrayStart < 0) {
    throw new Error('dist/sw.js 的 precacheAndRoute 调用里找不到清单数组');
  }
  let depth = 0;
  let arrayEnd = -1;
  for (let i = arrayStart; i < swSource.length; i += 1) {
    if (swSource[i] === '[') depth += 1;
    if (swSource[i] === ']') {
      depth -= 1;
      if (depth === 0) {
        arrayEnd = i;
        break;
      }
    }
  }
  if (arrayEnd < 0) {
    throw new Error('dist/sw.js 的 precache 清单数组没有闭合');
  }
  const arraySource = swSource.slice(arrayStart, arrayEnd + 1);
  const urls = [];
  for (const match of arraySource.matchAll(/\{url:"((?:[^"\\]|\\.)*)"/g)) {
    try {
      urls.push(JSON.parse(`"${match[1]}"`));
    } catch {
      urls.push(match[1]);
    }
  }
  return urls;
}

/** 校验 src 里的版本常量与 node_modules / vendor 目录三方一致，避免升级依赖时漏改 */
function checkVersionConsistency(vendorVersion) {
  const pkgPath = join(ROOT, 'node_modules', 'maplibre-gl', 'package.json');
  if (!existsSync(pkgPath)) {
    fail(
      'maplibre 版本一致',
      'node_modules/maplibre-gl/package.json 不存在',
      '先执行 npm ci / npm install',
    );
    return;
  }
  const installed = JSON.parse(readFileSync(pkgPath, 'utf8')).version;
  const loaderSource = existsSync(LOADER_PATH) ? readFileSync(LOADER_PATH, 'utf8') : '';
  const declared = /export const MAPLIBRE_VERSION = '([^']+)'/.exec(loaderSource)?.[1];

  if (declared !== installed) {
    fail(
      'loader 版本与 node_modules 一致',
      `maplibreLoader.ts=${declared ?? '缺失'}，node_modules=${installed}`,
      '升级 maplibre 时同步修改 MAPLIBRE_VERSION（maplibreLoader.test.ts 也会断言）',
    );
  } else {
    pass('loader 版本与 node_modules 一致', installed);
  }

  if (declared !== vendorVersion) {
    fail(
      'vendor 目录版本与 loader 一致',
      `dist/vendor/maplibre/${vendorVersion}/ 与 MAPLIBRE_VERSION=${declared} 不一致`,
      '重新执行 npm run build（插件按 node_modules 版本生成目录）',
    );
  } else {
    pass('vendor 目录版本与 loader 一致', vendorVersion);
  }
}

function main() {
  console.log('\n前端包体预算检查（dist/）');
  console.log('─'.repeat(78));

  if (!existsSync(DIST)) {
    console.error('✗ dist/ 不存在，请先运行 npm run build');
    process.exit(1);
  }

  // ---- 1. assets JS 单文件 ≤ 800,000 B（vendor 单独预算）----
  const assetsDir = join(DIST, 'assets');
  const assetJs = existsSync(assetsDir)
    ? walk(assetsDir).filter((file) => extname(file) === '.js')
    : [];
  const oversized = assetJs.filter((file) => statSync(file).size > BUDGET.assetJs);
  const largest = [...assetJs].sort((a, b) => statSync(b).size - statSync(a).size).slice(0, 5);
  if (assetJs.length === 0) {
    fail('assets JS 单文件 ≤ 800,000 B', 'dist/assets 下没有 JS 文件', '确认 npm run build 成功');
  }
  console.log('\n最大的 assets JS（原始 / gzip9）：');
  for (const file of largest) {
    const raw = readFileSync(file);
    console.log(
      `  ${(statSync(file).size / 1000).toFixed(2).padStart(9)} kB  ${kib(gzipSize(raw)).padStart(10)}  ${file.split(sep).slice(-1)[0]}`,
    );
  }
  if (oversized.length === 0 && assetJs.length > 0) {
    pass(
      `assets JS 单文件 ≤ ${bytes(BUDGET.assetJs)}`,
      `${assetJs.length} 个文件，最大 ${bytes(statSync(largest[0]).size)}`,
    );
  } else if (oversized.length > 0) {
    for (const file of oversized) {
      fail(
        `assets JS 单文件 ≤ ${bytes(BUDGET.assetJs)}`,
        `${file.split(sep).slice(-1)[0]} = ${bytes(statSync(file).size)}`,
        '把该依赖改为懒加载 / 运行时加载，或为它定义单独的预算与理由（不要直接调高全局阈值）',
      );
    }
  }

  // ---- 2. index.html 首屏 JS ≤ 600,000 B ----
  const htmlPath = join(DIST, 'index.html');
  if (!existsSync(htmlPath)) {
    fail('首屏 JS ≤ 600,000 B', 'dist/index.html 不存在', '重新执行 npm run build');
  } else {
    const html = readFileSync(htmlPath, 'utf8');
    const entryFiles = entryScriptUrls(html).map((url) => join(DIST, url));
    const missing = entryFiles.filter((file) => !existsSync(file));
    const entryTotal = entryFiles
      .filter((file) => existsSync(file))
      .reduce((sum, file) => sum + statSync(file).size, 0);
    console.log('\nindex.html 首屏直接加载的 JS：');
    for (const file of entryFiles) {
      console.log(
        `  ${existsSync(file) ? bytes(statSync(file).size).padStart(12) : '缺 失'.padStart(12)}  ${file.split(sep).slice(-1)[0]}`,
      );
    }
    if (missing.length > 0) {
      fail('首屏 JS 引用可解析', `${missing.length} 个文件缺失`, '确认 dist 构建完整');
    }
    if (entryTotal <= BUDGET.entryJs) {
      pass(`首屏 JS ≤ ${bytes(BUDGET.entryJs)}`, `合计 ${bytes(entryTotal)}`);
    } else {
      fail(
        `首屏 JS ≤ ${bytes(BUDGET.entryJs)}`,
        `合计 ${bytes(entryTotal)}`,
        '把非首屏必需的模块改为 lazy()/动态 import；检查 App/AppShell 是否静态引用了图表或地图组件',
      );
    }

    // ---- 6. index.html 不引用地图 vendor ----
    if (html.includes('vendor/maplibre')) {
      fail(
        'index.html 不引用 vendor/maplibre',
        '首屏 HTML 出现了地图 vendor 路径，地图引擎会重新变成首屏下载',
        '确保 maplibre 只通过运行时 import() 加载，且 PWA 不把它写进 HTML',
      );
    } else {
      pass('index.html 不引用 vendor/maplibre', '地图引擎只在渲染地图时按需加载');
    }

    // ---- 7. index.html 首屏 CSS ≤ 65,536 B ----
    const styleFiles = entryStyleUrls(html).map((url) => join(DIST, url));
    const styleMissing = styleFiles.filter((file) => !existsSync(file));
    const styleTotal = styleFiles
      .filter((file) => existsSync(file))
      .reduce((sum, file) => sum + statSync(file).size, 0);
    console.log('\nindex.html 首屏直接加载的 CSS：');
    for (const file of styleFiles) {
      console.log(
        `  ${existsSync(file) ? bytes(statSync(file).size).padStart(12) : '缺 失'.padStart(12)}  ${file.split(sep).slice(-1)[0]}`,
      );
    }
    if (styleMissing.length > 0) {
      fail('首屏 CSS 引用可解析', `${styleMissing.length} 个文件缺失`, '确认 dist 构建完整');
    }
    if (styleTotal <= BUDGET.entryCss) {
      pass(`首屏 CSS ≤ ${bytes(BUDGET.entryCss)}`, `合计 ${bytes(styleTotal)}`);
    } else {
      fail(
        `首屏 CSS ≤ ${bytes(BUDGET.entryCss)}`,
        `合计 ${bytes(styleTotal)}`,
        '把页面私有规则移回 src/pages/<Page>/<page>.css（随路由 chunk 加载），并确认 global.css 只保留跨页共享规则',
      );
    }
  }

  // ---- 3. echarts chunk ≤ 600,000 B ----
  const echartsFiles = assetJs.filter((file) => /(^|[\\/])echarts-.*\.js$/.test(file));
  if (echartsFiles.length === 0) {
    fail(
      `echarts chunk ≤ ${bytes(BUDGET.echarts)}`,
      '没有找到 assets/echarts-*.js',
      '图表应保持在独立 chunk（manualChunks 把 echarts/zrender 归入 echarts），否则无法单独设预算',
    );
  } else {
    for (const file of echartsFiles) {
      const size = statSync(file).size;
      if (size <= BUDGET.echarts) {
        pass(
          `echarts chunk ≤ ${bytes(BUDGET.echarts)}`,
          `${file.split(sep).slice(-1)[0]} = ${bytes(size)}`,
        );
      } else {
        fail(
          `echarts chunk ≤ ${bytes(BUDGET.echarts)}`,
          `${file.split(sep).slice(-1)[0]} = ${bytes(size)}`,
          '检查是否又 import 了 echarts 全量入口；新图表类型只能在 src/lib/echarts.ts 里按需 use()',
        );
      }
    }
  }

  // ---- 4. 自托管地图引擎（vendor）gzip 预算 ----
  console.log('\n自托管地图引擎（dist/vendor/maplibre，不计入 precache）：');
  const versions = existsSync(VENDOR_ROOT)
    ? readdirSync(VENDOR_ROOT, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => entry.name)
    : [];
  if (versions.length !== 1) {
    fail(
      '地图 vendor 目录存在且只保留当前版本',
      versions.length === 0
        ? 'dist/vendor/maplibre/<version>/ 不存在'
        : `发现多个版本：${versions.join(', ')}`,
      '运行 npm run build（maplibreVendor 插件会从 node_modules 拷贝当前版本）',
    );
  } else {
    const version = versions[0];
    const vendorDir = join(VENDOR_ROOT, version);
    checkVersionConsistency(version);

    const mainFile = join(vendorDir, 'maplibre-gl.mjs');
    const workerFile = join(vendorDir, 'maplibre-gl-worker.mjs');
    const cssFile = join(vendorDir, 'maplibre-gl.css');
    const requiredFiles = [
      [mainFile, '主模块'],
      [workerFile, 'worker'],
      [cssFile, '样式'],
    ];
    for (const [file, label] of requiredFiles) {
      if (!existsSync(file)) {
        fail(`vendor ${label} 存在`, file, '运行 npm run build 让插件重新拷贝');
      }
    }
    if (existsSync(mainFile)) {
      const raw = readFileSync(mainFile);
      const gz = gzipSize(raw);
      const detail = `${bytes(raw.length)} / gzip9 ${bytes(gz)}`;
      if (gz <= BUDGET.maplibreMainGzip) {
        pass(`地图主模块 gzip ≤ ${bytes(BUDGET.maplibreMainGzip)}`, detail);
      } else {
        fail(
          `地图主模块 gzip ≤ ${bytes(BUDGET.maplibreMainGzip)}`,
          detail,
          '不要降级到有已知漏洞的旧版本；先确认是否加了新的 maplibre 插件/样式，再评估调整预算并记录理由',
        );
      }
    }
    if (existsSync(workerFile)) {
      const raw = readFileSync(workerFile);
      const gz = gzipSize(raw);
      const detail = `${bytes(raw.length)} / gzip9 ${bytes(gz)}`;
      if (gz <= BUDGET.maplibreWorkerGzip) {
        pass(`地图 worker gzip ≤ ${bytes(BUDGET.maplibreWorkerGzip)}`, detail);
      } else {
        fail(
          `地图 worker gzip ≤ ${bytes(BUDGET.maplibreWorkerGzip)}`,
          detail,
          '确认 worker 是否被重复打包；升级 maplibre 后按实际体积评估并记录新阈值',
        );
      }
    }
    if (existsSync(cssFile)) {
      notes.push(`地图样式：${bytes(statSync(cssFile).size)}（懒加载 CSS，不计入首屏 JS 预算）`);
    }
  }

  // ---- 5. PWA precache 总量 ≤ 2.3 MiB ----
  const swPath = join(DIST, 'sw.js');
  if (!existsSync(swPath)) {
    fail(
      'PWA precache ≤ 2.3 MiB',
      'dist/sw.js 不存在',
      '确认 vite-plugin-pwa 生成成功（npm run build）',
    );
  } else {
    let precacheUrls = [];
    try {
      precacheUrls = readPrecacheUrls(readFileSync(swPath, 'utf8'));
    } catch (error) {
      fail('PWA precache ≤ 2.3 MiB', error.message, '检查 vite-plugin-pwa / workbox 配置');
    }
    if (precacheUrls.length > 0) {
      let total = 0;
      const missing = [];
      for (const url of precacheUrls) {
        const file = join(DIST, url);
        if (existsSync(file)) {
          total += statSync(file).size;
        } else {
          missing.push(url);
        }
      }
      if (missing.length > 0) {
        fail(
          'precache 清单文件都存在',
          `${missing.length} 个缺失：${missing.slice(0, 3).join(', ')}`,
          '重新构建',
        );
      }
      if (precacheUrls.some((url) => url.includes('vendor/maplibre'))) {
        fail(
          'precache 不包含地图引擎',
          'workbox 清单里出现了 vendor/maplibre',
          "检查 VitePWA workbox.globIgnores 是否包含 '**/vendor/maplibre/**'",
        );
      } else {
        pass('precache 不包含地图引擎', `${precacheUrls.length} 项，地图引擎按需下载`);
      }
      if (total <= BUDGET.precache) {
        pass(
          `PWA precache ≤ ${mib(BUDGET.precache)}`,
          `${precacheUrls.length} 项，合计 ${mib(total)}`,
        );
      } else {
        fail(
          `PWA precache ≤ ${mib(BUDGET.precache)}`,
          `${precacheUrls.length} 项，合计 ${mib(total)}`,
          '检查是否有大文件被默认 glob 收进 precache；把非首屏必需资源加入 globIgnores 或改为运行时缓存',
        );
      }
    }
  }

  if (notes.length > 0) {
    console.log('\n说明：');
    for (const note of notes) console.log(`  · ${note}`);
  }

  console.log('\n' + '─'.repeat(78));
  if (failures.length === 0) {
    console.log('✓ 全部包体预算通过\n');
    return;
  }

  console.log(`✗ ${failures.length} 项超出预算：`);
  for (const item of failures) {
    console.log(`  - ${item.label}${item.detail ? `（${item.detail}）` : ''}`);
    if (item.suggestion) console.log(`    建议：${item.suggestion}`);
  }
  console.log('');
  process.exitCode = 1;
}

main();

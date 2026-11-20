#!/usr/bin/env node
/**
 * 契约漂移检查（`npm run api:check`）。
 *
 * 两步，任何一步失败都以非零码退出，CI 直接可用：
 *  1. 用**仓库内的** openapi-typescript 重新生成一次到临时文件，与提交的
 *     `src/api/generated/schema.d.ts` 逐行比较（忽略 CRLF 与 banner 时间戳行）。
 *     不一致 = 有人手改了生成文件，或改了 `docs/api/openapi.json` 却没重新生成类型。
 *  2. 校验快照 `docs/api/openapi.json` 里仍存在前端核心流程依赖的 paths / schemas。
 *     快照与后端代码是否一致由后端 `OpenApiSnapshotTest` 负责（先跑 mvn，再跑本脚本）。
 *
 * 设计说明：
 * - 不调用 `npx` 联网，也不依赖 shell：直接解析本地包的 `bin` 入口，用当前 node 进程执行，
 *   这样 Windows（.cmd 无法被 spawn）与 POSIX 行为一致，且不会意外下载别的版本。
 * - 比较时只忽略「行尾空白 / 换行符 / 生成时间戳」三类噪声；任何类型内容变化都必须暴露。
 */
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const snapshotPath = resolve(webRoot, '..', 'docs', 'api', 'openapi.json');
const committedTypes = resolve(webRoot, 'src', 'api', 'generated', 'schema.d.ts');

/** 前端核心流程依赖的机器契约；少一条就说明快照或后端路由被破坏。 */
const CORE_PATHS = [
  '/api/trips',
  '/api/trips/{id}',
  '/api/trips/{id}/itinerary',
  '/api/itinerary/{id}',
  '/api/agent/chat',
  '/api/agent/stream',
  '/api/auth/login',
];
const CORE_SCHEMAS = ['TripResponse', 'ItineraryItemDTO', 'ChatRequest', 'AgentResult'];

function fail(message) {
  console.error(`\n[api:check] ✗ ${message}`);
  process.exit(1);
}

function info(message) {
  console.log(`[api:check] ${message}`);
}

/** 解析本地安装的 openapi-typescript 的 CLI 入口（跨平台，不经过 shell）。 */
function resolveOpenapiTypescriptBin() {
  const require = createRequire(join(webRoot, 'package.json'));
  try {
    const pkgPath = require.resolve('openapi-typescript/package.json');
    const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
    const bin = typeof pkg.bin === 'string' ? pkg.bin : pkg.bin?.['openapi-typescript'];
    if (bin) {
      const binPath = resolve(dirname(pkgPath), bin);
      if (existsSync(binPath)) {
        return binPath;
      }
    }
  } catch {
    // 落到下面的显式路径兜底（例如包被提升到上层 node_modules 的特殊场景）
  }
  const fallback = join(webRoot, 'node_modules', 'openapi-typescript', 'bin', 'cli.js');
  if (existsSync(fallback)) {
    return fallback;
  }
  fail(
    '找不到本地 openapi-typescript。请先在 travel-agent-web 执行 npm install（本仓库已把它列为 devDependency）。',
  );
  return null;
}

/** 归一化：统一换行、去掉行尾空白与生成时间戳行，避免无意义 diff。 */
function normalize(source) {
  return source
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .filter((line) => !/^\s*(\*|\/\/)\s*Generated (at|on)\b/i.test(line))
    .map((line) => line.replace(/[ \t]+$/, ''))
    .join('\n')
    .replace(/\n+$/, '');
}

/** 逐行对比，打印第一处差异附近的内容（限制条数，避免刷屏）。 */
function printDiff(expectedFile, expected, actualFile, actual) {
  const expectedLines = expected.split('\n');
  const actualLines = actual.split('\n');
  const max = Math.max(expectedLines.length, actualLines.length);
  const hunks = [];
  let skipped = 0;
  for (let i = 0; i < max && hunks.length < 40; i++) {
    if (expectedLines[i] !== actualLines[i]) {
      if (skipped > 0) {
        hunks.push(`  ... 跳过 ${skipped} 行相同内容 ...`);
        skipped = 0;
      }
      hunks.push(
        `  第 ${i + 1} 行\n` +
          `    - ${expectedFile}: ${JSON.stringify(expectedLines[i] ?? '<文件结束>')}\n` +
          `    + 重新生成:        ${JSON.stringify(actualLines[i] ?? '<文件结束>')}`,
      );
    } else {
      skipped++;
    }
  }
  if (hunks.length === 0) {
    return;
  }
  console.error(hunks.join('\n'));
}

function checkSnapshotContract() {
  if (!existsSync(snapshotPath)) {
    fail(
      `契约快照不存在：${snapshotPath}\n  先在后端生成：cd travel-agent-server && mvn -B -Dtest=OpenApiSnapshotTest -Dopenapi.update=true test`,
    );
  }
  let document;
  try {
    document = JSON.parse(readFileSync(snapshotPath, 'utf8'));
  } catch (error) {
    fail(`契约快照不是合法 JSON：${snapshotPath}\n  ${error.message}`);
  }
  const paths = document?.paths ?? {};
  const schemas = document?.components?.schemas ?? {};
  const missingPaths = CORE_PATHS.filter((path) => !(path in paths));
  const missingSchemas = CORE_SCHEMAS.filter((name) => !(name in schemas));
  if (missingPaths.length > 0 || missingSchemas.length > 0) {
    fail(
      '快照缺少前端核心契约：\n' +
        (missingPaths.length ? `  缺失 paths: ${missingPaths.join(', ')}\n` : '') +
        (missingSchemas.length ? `  缺失 schemas: ${missingSchemas.join(', ')}\n` : '') +
        '  若这是有意的接口调整，请同步更新本脚本的 CORE_* 清单与前端类型。',
    );
  }
  info(`快照契约完整：paths=${Object.keys(paths).length}, schemas=${Object.keys(schemas).length}`);
}

function main() {
  if (!existsSync(committedTypes)) {
    fail(`生成的类型文件不存在：${committedTypes}\n  先执行：npm run api:generate`);
  }
  checkSnapshotContract();

  const binPath = resolveOpenapiTypescriptBin();
  const tempDir = mkdtempSync(join(tmpdir(), 'openapi-types-'));
  const tempFile = join(tempDir, 'schema.d.ts');
  try {
    info(`重新生成类型（${binPath}）...`);
    const result = spawnSync(process.execPath, [binPath, snapshotPath, '-o', tempFile], {
      cwd: webRoot,
      encoding: 'utf8',
      // 大快照生成慢时给足时间；超时视为失败而不是挂住 CI
      timeout: 120_000,
    });
    if (result.error) {
      fail(`执行 openapi-typescript 失败：${result.error.message}`);
    }
    if (result.status !== 0) {
      fail(
        `openapi-typescript 退出码 ${result.status}\n${(result.stderr || '').trim()}\n${(result.stdout || '').trim()}`,
      );
    }
    if (!existsSync(tempFile)) {
      fail('openapi-typescript 未产出类型文件（命令成功但文件缺失）。');
    }

    const expected = normalize(readFileSync(committedTypes, 'utf8'));
    const actual = normalize(readFileSync(tempFile, 'utf8'));
    if (expected !== actual) {
      console.error(`\n[api:check] ✗ 生成类型与提交文件不一致：${committedTypes}`);
      printDiff('已提交', expected, '重新生成', actual);
      console.error(
        '\n[api:check] 修复方式：确认 docs/api/openapi.json 是最新快照后，在 travel-agent-web 执行 npm run api:generate，并提交生成文件。',
      );
      process.exit(1);
    }
    info('生成类型与契约快照一致，无漂移。');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

main();

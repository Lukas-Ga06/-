#!/usr/bin/env node
/**
 * 启动**隔离的** e2e 后端（发布门禁用）。
 *
 * 为什么不能直接 `mvn spring-boot:run`：
 * `travel-agent-server/src/main/resources/application.yml` 里有
 * `spring.config.import: optional:file:./.env.local`，它按**进程工作目录**解析。
 * 开发者在 travel-agent-server 里启动时，`.env.local` 的真实 LLM/高德 Key 会被读进来，
 * 于是 `npm run e2e` 实际在打真实外部服务：结果不可重复、会烧配额，而且测试通过也
 * 不能证明“无外部依赖也能跑”。2026-11-22 的 device 套件就是因为真实 LLM 污染出现 19/21。
 *
 * 本脚本的做法（三重隔离，任何一层单独失效都还有兜底）：
 * 1) **工作目录隔离**：java 进程的 cwd 是 `.e2e/isolated/`，这里没有 `.env.local`，
 *    可选的 import 自然落空；
 * 2) **环境变量清洗**：删除继承来的 SPRING_* / LLM_API_KEY / AMAP_* / JWT_SECRET 等，
 *    防止开发 shell 里 export 过的值覆盖配置；
 * 3) **命令行覆盖**：LLM/Embedding/MCP/AMAP/Redis/限流全部显式关闭，H2 用内存库，
 *    所有外部 provider 的“配置态”都是 false，`/api/system/status` 可自证。
 *
 * 用法：
 *   node scripts/start-e2e-backend.mjs --port 18081
 *   node scripts/start-e2e-backend.mjs --port 18081 --force-build
 *
 * 由 Playwright 的 webServer 拉起时不需要手工调用；本地也可以单独启动后用
 * `E2E_API_TARGET=http://127.0.0.1:18081 npm run e2e` 复用。
 *
 * 退出：SIGINT/SIGTERM 会转发给 java 子进程；Playwright 关闭 webServer 时依赖这一点。
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SERVER_DIR = path.join(ROOT, 'travel-agent-server');
const ISOLATED_DIR = path.join(ROOT, '.e2e', 'isolated');
const LOG_FILE = path.join(ISOLATED_DIR, 'backend.log');
const PORT = Number(readArg('--port') ?? process.env.E2E_API_PORT ?? 18081);
const READY_TIMEOUT_MS = Number(readArg('--ready-timeout') ?? 300_000);
const FORCE_BUILD = process.argv.includes('--force-build');
const SKIP_BUILD = process.argv.includes('--skip-build');

function readArg(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function log(message) {
  // 只打简短进度：Playwright 的 webServer 输出会进 CI 日志，太长会淹没失败原因
  console.log(`[e2e-backend] ${message}`);
}

function fail(message) {
  console.error(`[e2e-backend] ${message}`);
  process.exit(1);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 递归取目录下最新 mtime（只用于判断 jar 是否过期，跳过 target/node_modules） */
function latestMtime(dir) {
  let latest = 0;
  const stack = [dir];
  while (stack.length) {
    const current = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'target' || entry.name === 'node_modules' || entry.name === '.git') continue;
        stack.push(full);
      } else {
        try {
          latest = Math.max(latest, fs.statSync(full).mtimeMs);
        } catch {
          // 文件在遍历期间被删除：忽略
        }
      }
    }
  }
  return latest;
}

function findJar() {
  const targetDir = path.join(SERVER_DIR, 'target');
  if (!fs.existsSync(targetDir)) return null;
  const jars = fs
    .readdirSync(targetDir)
    .filter((name) => name.endsWith('.jar') && !name.endsWith('.original'))
    .map((name) => path.join(targetDir, name))
    .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
  return jars[0] ?? null;
}

function runMavenPackage() {
  const mvn = process.platform === 'win32' ? 'mvn.cmd' : 'mvn';
  log('构建后端 jar（mvn -Dmaven.test.skip=true package）…');
  const build = spawn(
    mvn,
    ['-B', '-q', '-Dmaven.test.skip=true', '-DskipTests', 'package'],
    { cwd: SERVER_DIR, stdio: 'inherit', shell: process.platform === 'win32' },
  );
  return new Promise((resolve, reject) => {
    build.on('exit', (exitCode) => (exitCode === 0 ? resolve() : reject(new Error(`mvn package 退出码 ${exitCode}`))));
    build.on('error', reject);
  });
}

async function ensureJar() {
  if (SKIP_BUILD) {
    const jar = findJar();
    if (!jar) fail('--skip-build 指定了跳过构建，但 target 下没有可执行 jar');
    log(`跳过构建，使用 ${path.relative(ROOT, jar)}`);
    return jar;
  }
  let jar = findJar();
  const sourcesMtime = Math.max(
    latestMtime(path.join(SERVER_DIR, 'src', 'main')),
    fs.statSync(path.join(SERVER_DIR, 'pom.xml')).mtimeMs,
  );
  const stale = !jar || fs.statSync(jar).size < 1024 || fs.statSync(jar).mtimeMs < sourcesMtime;
  if (FORCE_BUILD || stale) {
    if (jar && !FORCE_BUILD) log(`jar 早于源码（${new Date(fs.statSync(jar).mtimeMs).toISOString()}），重新构建`);
    await runMavenPackage();
    jar = findJar();
  }
  if (!jar) fail('构建结束但 target 下仍没有 jar');
  log(`使用 jar：${path.relative(ROOT, jar)}`);
  return jar;
}

/** 子进程环境清洗：删掉一切可能把隔离实例接回真实外部服务的变量 */
function isolatedEnv() {
  const env = { ...process.env };
  for (const key of Object.keys(env)) {
    if (
      key.startsWith('SPRING_') ||
      key === 'LLM_API_KEY' ||
      key === 'EMBEDDING_API_KEY' ||
      key === 'AMAP_KEY' ||
      key === 'AMAP_MAPS_API_KEY' ||
      key === 'JWT_SECRET' ||
      key === 'MYSQL_HOST' ||
      key === 'MYSQL_PORT' ||
      key === 'MYSQL_USERNAME' ||
      key === 'MYSQL_PASSWORD' ||
      key === 'MYSQL_DATABASE' ||
      key === 'AWS_ACCESS_KEY_ID' ||
      key === 'AWS_SECRET_ACCESS_KEY' ||
      key === 'S3_ACCESS_KEY' ||
      key === 'S3_SECRET_KEY'
    ) {
      delete env[key];
    }
  }
  // 时区与编码由 java 命令行参数钉住（见 spawn 处），不在环境里叠加 JAVA_TOOL_OPTIONS，
  // 否则 JVM 每次启动都会先打印一行 "Picked up JAVA_TOOL_OPTIONS" 干扰日志。
  env.TZ = 'Asia/Shanghai';
  return env;
}

async function isHealthy() {
  try {
    const response = await fetch(`http://127.0.0.1:${PORT}/actuator/health`, {
      signal: AbortSignal.timeout(2000),
    });
    if (!response.ok) return false;
    const body = await response.json();
    return body?.status === 'UP';
  } catch {
    return false;
  }
}

function tailLog(lines = 40) {
  try {
    return fs
      .readFileSync(LOG_FILE, 'utf8')
      .split(/\r?\n/)
      .filter(Boolean)
      .slice(-lines)
      .join('\n');
  } catch {
    return '<日志不可读>';
  }
}

async function main() {
  if (!Number.isInteger(PORT) || PORT <= 0 || PORT > 65535) fail(`端口不合法：${PORT}`);
  fs.mkdirSync(path.join(ISOLATED_DIR, 'photos'), { recursive: true });
  fs.mkdirSync(path.join(ISOLATED_DIR, 'data'), { recursive: true });

  // 防线 1：隔离目录绝不能有 .env.local（有就说明有人手工复制过，直接拒绝启动）
  if (fs.existsSync(path.join(ISOLATED_DIR, '.env.local'))) {
    fail(`隔离目录里出现了 .env.local：${path.join(ISOLATED_DIR, '.env.local')}，拒绝启动`);
  }

  if (await isHealthy()) {
    fail(
      `端口 ${PORT} 上已经有一个健康的后端。为了不把开发者实例当隔离门禁，本脚本拒绝复用；` +
        '请先停止它（或换 --port）。',
    );
  }

  const jar = await ensureJar();
  const dbSuffix = Date.now();
  const overrides = [
    `--server.port=${PORT}`,
    `--spring.datasource.url=jdbc:h2:mem:e2e_${dbSuffix};MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`,
    '--spring.datasource.driver-class-name=org.h2.Driver',
    '--spring.datasource.username=sa',
    '--spring.datasource.password=',
    '--spring.h2.console.enabled=false',
    '--spring.jpa.hibernate.ddl-auto=validate',
    '--spring.main.banner-mode=off',
    '--server.error.include-message=never',
    '--travel.demo-account.enabled=true',
    '--travel.jwt.cookie-secure=false',
    '--travel.rate-limit.enabled=false',
    '--travel.rate-limit.store=memory',
    '--travel.llm.enabled=false',
    '--travel.embedding.enabled=false',
    '--travel.weather.real=false',
    '--travel.mcp.enabled=false',
    '--travel.amap.key=',
    '--travel.photos.store=local',
    `--travel.photos.dir=${path.join(ISOLATED_DIR, 'photos')}`,
    '--travel.timezone=Asia/Shanghai',
    '--spring.jackson.time-zone=Asia/Shanghai',
  ];

  const logStream = fs.createWriteStream(LOG_FILE, { flags: 'w' });
  logStream.write(
    `# 隔离 e2e 后端\n# 时间：${new Date().toISOString()}\n# 端口：${PORT}\n` +
      '# 外部 provider：LLM/Embedding/MCP/AMAP 全部显式关闭\n' +
      '# 数据库：H2 内存库（每次启动全新，不读 server/data）\n\n',
  );

  const child = spawn(
    'java',
    ['-Duser.timezone=Asia/Shanghai', '-Dfile.encoding=UTF-8', '-jar', jar, ...overrides],
    {
      cwd: ISOLATED_DIR, // 防线 1：cwd 没有 .env.local
      env: isolatedEnv(), // 防线 2：继承环境清洗
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    },
  );

  for (const stream of [child.stdout, child.stderr]) {
    stream?.on('data', (chunk) => {
      logStream.write(chunk);
      // 只在交互终端回显：Playwright 会把我们的 stdout 收进 CI 日志，全部回显会太大
      if (process.stdout.isTTY) process.stdout.write(chunk);
    });
  }

  let stopped = false;
  const stop = (signal) => {
    if (stopped) return;
    stopped = true;
    log(`收到 ${signal}，停止后端 …`);
    try {
      logStream.end();
    } catch {
      // 忽略
    }
    if (process.platform === 'win32') {
      spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    } else {
      child.kill('SIGTERM');
      setTimeout(() => child.kill('SIGKILL'), 3000).unref();
    }
    process.exit(0);
  };
  process.on('SIGINT', () => stop('SIGINT'));
  process.on('SIGTERM', () => stop('SIGTERM'));

  child.on('exit', (code) => {
    if (!stopped) {
      console.error(`[e2e-backend] java 提前退出（code=${code}）。最后 40 行日志：\n${tailLog()}`);
      process.exit(code ?? 1);
    }
  });

  const startedAt = Date.now();
  while (Date.now() - startedAt < READY_TIMEOUT_MS) {
    if (child.exitCode !== null) break;
    if (await isHealthy()) {
      fs.writeFileSync(path.join(ISOLATED_DIR, 'backend.pid'), String(child.pid));
      log(`READY http://127.0.0.1:${PORT}（日志 ${path.relative(ROOT, LOG_FILE)}）`);
      log('外部依赖：LLM=off Embedding=off MCP=off AMAP=off H2=memory RateLimit=off');
      return;
    }
    await sleep(1000);
  }

  console.error(`[e2e-backend] ${READY_TIMEOUT_MS}ms 内未就绪。最后 40 行日志：\n${tailLog()}`);
  stop('timeout');
}

main().catch((error) => {
  console.error(`[e2e-backend] 启动失败：${error?.stack ?? error}`);
  process.exit(1);
});
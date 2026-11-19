#!/usr/bin/env node
/**
 * 暗色地图/图表验收专用后端实例：固定端口 18096 + 独立 H2 目录（不碰 18090/18091/18100）。
 *
 *   node .e2e/dark/start-instance.mjs serve    # 前台常驻（用后台作业跑）
 *   node .e2e/dark/start-instance.mjs stop     # 落 stop.flag，让 serve 收尾并杀 java
 *   node .e2e/dark/start-instance.mjs status
 *
 * 口径：LLM 关闭（--travel.llm.enabled=false）；AMAP_KEY 为空（route-legs 全 estimated 是预期，
 * 前端地图仍用 .env.local 的 JS API Key 真实渲染）；demo 账号开启以便验收登录。
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const PROJECT = resolve(HERE, '..', '..');
const RUN_DIR = join(PROJECT, '.evidence', 'dark', 'run');
const JAR = join(PROJECT, 'travel-agent-server', 'target', 'travel-agent-server-0.1.0.jar');
const JAVA = process.env.JAVA_EXE || 'C:\\Program Files\\Java\\jdk-17.0.1\\bin\\java.exe';
const PID_FILE = join(RUN_DIR, 'instance.pid');
const STOP_FLAG = join(RUN_DIR, 'stop.flag');
const LOG_FILE = join(RUN_DIR, 'instance.log');
const PORT = 18096;
const JWT_SECRET = 'ZGFyay1tb2RlLXZlcmlmeS1qd3Qtc2VjcmV0LWxvY2FsLW9ubHktMDE=';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function arg(name, fallback = '') {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
}

function readPid() {
  if (!existsSync(PID_FILE)) return null;
  const pid = Number(readFileSync(PID_FILE, 'utf8').trim());
  return Number.isFinite(pid) && pid > 0 ? pid : null;
}

function alive(pid) {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

async function waitHealth(timeoutMs = 180_000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/actuator/health`, { signal: AbortSignal.timeout(2000) });
      const j = await r.json();
      if (j.status === 'UP') return Math.round((Date.now() - started) / 1000);
    } catch {
      /* 还在启动 */
    }
    await sleep(1000);
  }
  return null;
}

function killTree(pid) {
  return new Promise((res) => {
    if (!pid) return res();
    const p = spawn('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    p.on('exit', () => res());
  });
}

async function serve() {
  // 默认不配 AMAP_KEY（route-legs 全 estimated 是预期）；验收自检可见性时可传无效 Key，
  // 得到真实的 routeConfigured=true / amapVerified=false / routeLastError。
  const amapKey = arg('amap-key', '');
  if (existsSync(join(RUN_DIR, 'data'))) rmSync(join(RUN_DIR, 'data'), { recursive: true, force: true });
  mkdirSync(RUN_DIR, { recursive: true });
  rmSync(STOP_FLAG, { force: true });
  const args = [
    '-jar', JAR,
    `--server.port=${PORT}`,
    // 独立 H2 文件库（单进程模式；每次启动清空，避免被强杀后的锁文件干扰）
    '--spring.datasource.url=jdbc:h2:file:./data/dark;MODE=MySQL',
    `--travel.jwt.secret=${JWT_SECRET}`,
    '--travel.llm.enabled=false',
    '--travel.rate-limit.enabled=false',
    '--travel.demo-account.enabled=true',
    '--travel.amap.key=' + amapKey,
    '--logging.file.name=instance.log',
  ];
  writeFileSync(join(RUN_DIR, 'instance.args.json'), JSON.stringify({ args, port: PORT, amapKey }, null, 2));
  const child = spawn(JAVA, args, { cwd: RUN_DIR, detached: true, stdio: 'ignore', windowsHide: true });
  child.unref();
  writeFileSync(PID_FILE, String(child.pid), 'utf8');
  console.log(`[dark-instance] spawned pid=${child.pid} port=${PORT}`);
  const bootSec = await waitHealth();
  if (bootSec == null) {
    console.error(`[dark-instance] FAILED to become healthy; see ${LOG_FILE}`);
    await killTree(child.pid);
    process.exit(1);
  }
  console.log(`[dark-instance] health=UP after ${bootSec}s pid=${child.pid} port=${PORT}`);

  while (!existsSync(STOP_FLAG)) {
    if (!alive(child.pid)) {
      console.error(`[dark-instance] java pid=${child.pid} exited unexpectedly`);
      rmSync(PID_FILE, { force: true });
      process.exit(3);
    }
    await sleep(1000);
  }
  await killTree(child.pid);
  rmSync(STOP_FLAG, { force: true });
  rmSync(PID_FILE, { force: true });
  console.log(`[dark-instance] java pid=${child.pid} stopped; port ${PORT} released`);
  process.exit(0);
}

async function stop() {
  const pid = readPid();
  if (!pid || !alive(pid)) {
    console.log('[dark-instance] no running instance');
    rmSync(PID_FILE, { force: true });
    return;
  }
  writeFileSync(STOP_FLAG, 'stop');
  for (let i = 0; i < 60; i += 1) {
    if (!alive(pid)) {
      console.log(`[dark-instance] pid=${pid} stopped`);
      return;
    }
    await sleep(250);
  }
  await killTree(pid);
}

async function status() {
  const pid = readPid();
  let health = 'unreachable';
  try {
    const r = await fetch(`http://127.0.0.1:${PORT}/actuator/health`, { signal: AbortSignal.timeout(3000) });
    health = (await r.json()).status;
  } catch (e) {
    health = `ERR ${e.message}`;
  }
  const dbFile = join(RUN_DIR, 'data', 'dark.mv.db');
  console.log(
    JSON.stringify({
      port: PORT,
      pid,
      alive: alive(pid),
      health,
      dbBytes: existsSync(dbFile) ? statSync(dbFile).size : null,
      log: LOG_FILE,
    }, null, 2),
  );
}

const cmd = process.argv[2];
if (cmd === 'serve') await serve();
else if (cmd === 'stop') await stop();
else if (cmd === 'status') await status();
else {
  console.error('usage: node .e2e/dark/start-instance.mjs serve|stop|status');
  process.exit(1);
}
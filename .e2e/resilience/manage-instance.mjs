#!/usr/bin/env node
/**
 * 自建后端实例的生命周期管理（韧性测试专用，不碰 18090/18091/18092/4173）。
 *
 *   node manage-instance.mjs serve [--amap-key=KEY] [--llm-base=URL]   # 前台常驻（用后台作业跑）
 *   node manage-instance.mjs stop    # 落 stop.flag，让 serve 收尾并杀 java
 *   node manage-instance.mjs status
 *
 * 为什么用 serve 常驻：沙箱里前台命令结束时其子进程会被清理，因此 java 必须挂在
 * 一个后台作业里常驻；serve 负责 spawn → 等健康 → 等 stop.flag → taskkill。
 *
 * 实例特征：独立端口 18100、独立 H2 文件库（.evidence/resilience-run/data）、固定 JWT_SECRET、
 * 限流关闭、LLM 指向本地替身。日志由 Spring Boot 直接写文件（--logging.file.name），
 * 这样即使子进程 stdio 被 ignore（沙箱下捕获管道不可用）也不丢日志。
 */
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const HERE = resolve(new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
const PROJECT = resolve(HERE, '..', '..');
const RUN_DIR = join(PROJECT, '.evidence', 'resilience-run');
const JAR = join(PROJECT, 'travel-agent-server', 'target', 'travel-agent-server-0.1.0.jar');
const JAVA = process.env.JAVA_EXE || 'C:\\Program Files\\Java\\jdk-17.0.1\\bin\\java.exe';
const PID_FILE = join(RUN_DIR, 'instance.pid');
const STOP_FLAG = join(RUN_DIR, 'stop.flag');
const LOG_FILE = join(RUN_DIR, 'instance.log');
const PORT = 18100;
/** 固定密钥（Base64，解码 48 字节）：只为保证同一实例内 token 稳定，不是生产密钥 */
const JWT_SECRET = 'cmVzaWxpZW5jZS10ZXN0LWp3dC1zZWNyZXQtZm9yLWxvY2FsLW9ubHktMDE=';
const STUB_BASE = 'http://127.0.0.1:18110/v1';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function arg(name, fallback = null) {
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

async function waitHealth(timeoutMs = 150_000) {
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
  mkdirSync(RUN_DIR, { recursive: true });
  rmSync(STOP_FLAG, { force: true });
  const amapKey = arg('amap-key', '');
  const llmBase = arg('llm-base', STUB_BASE);
  /** 并发/长跑沿用旧基线的「LLM 关闭（规则引擎）」口径：--llm-enabled=false 起实例 */
  const llmEnabled = arg('llm-enabled', 'true') !== 'false';
  
  // 独立 scratch 库：每次启动清空，避免上一次被强杀留下的 .lock.db 干扰
  // （首次尝试沿用产品默认的 AUTO_SERVER=TRUE，被强杀后残留锁会让 H2 报 50100，
  //   这里改成单进程 file 模式 + 每次全新库，测试口径不受影响：仍然是 H2 文件库）
  const dataDir = join(RUN_DIR, 'data');
  if (existsSync(dataDir)) rmSync(dataDir, { recursive: true, force: true });
  mkdirSync(dataDir, { recursive: true });
  const dbFile = join(dataDir, 'travel-agent.mv.db');
  const dbBefore = 0;
  const args = [
    '-jar', JAR,
    `--server.port=${PORT}`,
    '--spring.datasource.url=jdbc:h2:file:./data/travel-agent;MODE=MySQL',
    `--travel.jwt.secret=${JWT_SECRET}`,
    `--travel.llm.enabled=${llmEnabled}`,
    `--travel.llm.base-url=${llmBase}`,
    '--travel.llm.api-key=resilience-stub-key',
    '--travel.llm.read-timeout-ms=30000',
    '--travel.llm.connect-timeout-ms=5000',
    '--travel.rate-limit.enabled=false',
    '--travel.demo-account.enabled=true',
    `--travel.amap.key=${amapKey}`,
    '--logging.file.name=instance.log',
  ];
  writeFileSync(
    join(RUN_DIR, 'instance.args.json'),
    JSON.stringify({ args, amapKey, llmBase, dbBefore, startedAt: new Date().toISOString() }, null, 2),
  );
  const child = spawn(JAVA, args, { cwd: RUN_DIR, detached: true, stdio: 'ignore', windowsHide: true });
  child.unref();
  writeFileSync(PID_FILE, String(child.pid), 'utf8');
  console.log(`[instance] spawned pid=${child.pid} llm=${llmBase} amap=${amapKey || '(none)'} dbBefore=${dbBefore}`);
  const bootSec = await waitHealth();
  if (bootSec == null) {
    console.error(`[instance] FAILED to become healthy; see ${LOG_FILE}`);
    await killTree(child.pid);
    process.exit(1);
  }
  console.log(`[instance] health=UP after ${bootSec}s pid=${child.pid}`);

  // 常驻：等 stop.flag 或进程意外退出
  while (!existsSync(STOP_FLAG)) {
    if (!alive(child.pid)) {
      console.error(`[instance] java pid=${child.pid} exited unexpectedly`);
      rmSync(PID_FILE, { force: true });
      process.exit(3);
    }
    await sleep(1000);
  }
  console.log('[instance] stop.flag detected, killing java');
  await killTree(child.pid);
  for (let i = 0; i < 40 && alive(child.pid); i++) await sleep(250);
  rmSync(STOP_FLAG, { force: true });
  rmSync(PID_FILE, { force: true });
  console.log(`[instance] java pid=${child.pid} stopped; port ${PORT} released`);
  process.exit(0);
}

async function stop() {
  const pid = readPid();
  if (!pid || !alive(pid)) {
    console.log('[instance] no running instance');
    rmSync(PID_FILE, { force: true });
    return;
  }
  writeFileSync(STOP_FLAG, 'stop');
  for (let i = 0; i < 60; i++) {
    if (!alive(pid)) {
      console.log(`[instance] pid=${pid} stopped`);
      return;
    }
    await sleep(250);
  }
  console.error(`[instance] pid=${pid} still alive after 15s, taskkill directly`);
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
  const dbFile = join(RUN_DIR, 'data', 'travel-agent.mv.db');
  console.log(
    JSON.stringify(
      { port: PORT, pid, alive: alive(pid), health, dbBytes: existsSync(dbFile) ? statSync(dbFile).size : null, log: LOG_FILE },
      null,
      2,
    ),
  );
}

const cmd = process.argv[2];
if (cmd === 'serve') await serve();
else if (cmd === 'stop') await stop();
else if (cmd === 'status') await status();
else {
  console.error('usage: node manage-instance.mjs serve|stop|status [--amap-key=KEY] [--llm-base=URL]');
  process.exit(1);
}
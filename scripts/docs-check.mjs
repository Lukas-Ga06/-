#!/usr/bin/env node
/**
 * docs-check.mjs — 文档相对链接与可执行命令校验（零依赖，Node 18+/20+）
 *
 * 用法：
 *   node scripts/docs-check.mjs          # 人类可读报告；有问题时 exit 1
 *   node scripts/docs-check.mjs --json   # 供 CI 消费的 JSON（stdout 只有 JSON）
 *
 * 校验范围：README.md + docs/**\/*.md
 *   1) 相对链接 / 图片目标必须存在（忽略 http(s)、mailto、tel、data、纯锚点、模板占位）。
 *   2) `npm run <script>` / `npm --prefix <dir> run <script>` / `npm test` 的 script
 *      必须存在于对应 package.json（无 --prefix 时默认 travel-agent-web/package.json）。
 *   3) `mvn <goal>` 的 goal 必须在白名单内；未知 goal 只记 warning，不让进程失败。
 *
 * 退出码：0 = 无断链且无失效脚本；1 = 存在断链或失效脚本引用。
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const JSON_MODE = process.argv.includes('--json');

const DEFAULT_NPM_PACKAGE = join(ROOT, 'travel-agent-web', 'package.json');

/** 已知的 Maven lifecycle phase 与常用 plugin goal。未知 goal 只警告。 */
const KNOWN_GOALS = new Set([
  // lifecycle phases
  'validate', 'initialize', 'generate-sources', 'process-sources', 'generate-resources',
  'process-resources', 'compile', 'process-classes', 'generate-test-sources',
  'process-test-sources', 'generate-test-resources', 'process-test-resources',
  'test-compile', 'process-test-classes', 'test', 'prepare-package', 'package',
  'pre-integration-test', 'integration-test', 'post-integration-test', 'verify',
  'install', 'deploy', 'clean', 'site', 'site-deploy',
  // plugin goals
  'spring-boot:run', 'spring-boot:start', 'spring-boot:stop', 'spring-boot:repackage',
  'spring-boot:build-image', 'spring-boot:help',
  'dependency:tree', 'dependency:list', 'dependency:analyze', 'dependency:resolve',
  'dependency:go-offline',
  'flyway:migrate', 'flyway:info', 'flyway:validate', 'flyway:clean', 'flyway:repair',
  'flyway:baseline', 'flyway:undo',
  'surefire:test', 'failsafe:integration-test', 'failsafe:verify',
  'jacoco:report', 'jar:jar', 'war:war', 'compiler:compile', 'resources:resources',
  // 发布门禁插件（docs/guide/quality-gates.md 的本地等价命令）
  'jacoco:prepare-agent', 'jacoco:check',
  'checkstyle:check', 'checkstyle:checkstyle',
  'spotbugs:check', 'spotbugs:spotbugs',
  'versions:display-dependency-updates', 'versions:display-plugin-updates',
  'exec:java', 'exec:exec', 'help:effective-pom', 'help:help',
]);

/** mvn 中需要「吃掉下一个 token」的选项（其值不是 goal）。 */
const MVN_OPTIONS_WITH_VALUE = new Set([
  '-f', '--file', '-pl', '--projects', '-P', '--activate-profiles', '-s', '--settings',
  '-gs', '--global-settings', '-t', '--toolchains', '-T', '--threads', '-b', '--builder',
  '-l', '--log-file', '-rf', '--resume-from', '-D', '--define', '-o', '--offline',
]);

const MD_LINK_RE = /!?\[[^\]]*\]\(\s*(<[^>]+>|[^)\s]+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)/g;
const INLINE_CODE_RE = /`([^`\n]+)`/g;
const FENCE_RE = /^\s*(```|~~~)/;

const filesScanned = [];
const linksChecked = [];
const commandsChecked = [];
const brokenLinks = [];
const invalidCommands = [];
const warnings = [];

function toPosix(p) {
  return p.split(sep).join('/');
}

function walkDir(dir, out) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules' || entry.name === '.git') continue;
      walkDir(full, out);
    } else if (entry.isFile() && entry.name.endsWith('.md')) {
      out.push(full);
    }
  }
}

function collectMarkdownFiles() {
  const out = [];
  const rootReadme = join(ROOT, 'README.md');
  if (existsSync(rootReadme)) out.push(rootReadme);
  const docsDir = join(ROOT, 'docs');
  if (existsSync(docsDir)) walkDir(docsDir, out);
  return out;
}

function stripAngle(target) {
  if (target.startsWith('<') && target.endsWith('>')) return target.slice(1, -1);
  return target;
}

function isExternal(target) {
  return (
    /^(https?:|mailto:|tel:|data:|javascript:|ftp:)/i.test(target) ||
    target.startsWith('//') ||
    target.startsWith('#') ||
    target === ''
  );
}

function looksLikePlaceholder(target) {
  return (
    /[{}]/.test(target) ||
    /^<.*>$/.test(target) ||
    /^\.\.\.$/.test(target) ||
    /[⟨⟩…]/.test(target)
  );
}

function decodeTarget(raw) {
  const withoutAngle = stripAngle(raw);
  const withoutFragment = withoutAngle.split('#')[0].split('?')[0];
  try {
    return decodeURIComponent(withoutFragment);
  } catch {
    return withoutFragment;
  }
}

function checkLink(file, line, rawTarget) {
  const target = stripAngle(rawTarget);
  if (isExternal(target) || looksLikePlaceholder(target)) return;
  let pathPart = decodeTarget(target);
  if (!pathPart || /^\{\{/.test(pathPart)) return;

  // 站点绝对路径按仓库根解析；其余按当前文件所在目录解析
  const baseDir = pathPart.startsWith('/') ? ROOT : dirname(file);
  const resolved = resolve(baseDir, pathPart.replace(/^\//, ''));
  const record = {
    file: toPosix(relative(ROOT, file)),
    line,
    target,
    resolved: toPosix(relative(ROOT, resolved)),
  };
  linksChecked.push(record);
  if (!existsSync(resolved)) {
    brokenLinks.push(record);
  }
}

function checkLinksInFile(file) {
  const content = readFileSync(file, 'utf8');
  const lines = content.split(/\r?\n/);
  let inFence = false;
  lines.forEach((text, idx) => {
    if (FENCE_RE.test(text)) {
      inFence = !inFence;
      return; // 围栏标记行本身不参与链接扫描
    }
    if (inFence) return; // 代码块里的 [x](y) 不是链接（避免把伪代码当断链）
    // 行内代码里的 [x](y) 是示例而非链接：先等长遮蔽再匹配
    const masked = text.replace(/`[^`]*`/g, (span) => ' '.repeat(span.length));
    MD_LINK_RE.lastIndex = 0;
    let m;
    while ((m = MD_LINK_RE.exec(masked)) !== null) {
      checkLink(file, idx + 1, m[1]);
    }
  });
}

function loadScripts(packagePath) {
  if (!existsSync(packagePath)) return null;
  try {
    const pkg = JSON.parse(readFileSync(packagePath, 'utf8'));
    return pkg.scripts && typeof pkg.scripts === 'object' ? pkg.scripts : {};
  } catch (err) {
    warnings.push({ kind: 'package-json', file: toPosix(relative(ROOT, packagePath)), message: `无法解析：${err.message}` });
    return null;
  }
}

/** 从一行里切出片段（&&、||、; 分隔），并清掉前缀噪音。 */
function commandSegments(line) {
  return line
    .split(/&&|\|\||;/)
    .map((s) =>
      s
        .trim()
        .replace(/^[$#>]\s*/, '')
        .replace(/^(nohup|time|env)\s+/, '')
        .replace(/(^|\s)#.*$/, '')
        .trim(),
    )
    .filter(Boolean);
}

function checkNpmSegment(segment, file, line) {
  if (!/^npm\s/.test(segment)) return;
  const tokens = segment.split(/\s+/);
  let i = 1;
  let packagePath = DEFAULT_NPM_PACKAGE;
  let explicitPrefix = null;
  if (tokens[i] === '--prefix' || tokens[i] === '-C') {
    const value = tokens[i + 1];
    if (value) {
      explicitPrefix = value;
      packagePath = resolve(ROOT, value.replace(/^\.\//, ''), 'package.json');
      i += 2;
    } else {
      i += 1;
    }
  } else if (tokens[i] && tokens[i].startsWith('--prefix=')) {
    explicitPrefix = tokens[i].slice('--prefix='.length);
    packagePath = resolve(ROOT, explicitPrefix.replace(/^\.\//, ''), 'package.json');
    i += 1;
  }

  const action = tokens[i];
  const scriptName =
    action === 'run' || action === 'run-script'
      ? tokens[i + 1]
      : action === 'test' || action === 'start' || action === 'stop' || action === 'restart'
        ? action
        : null;
  if (!scriptName) return;
  if (/[${}<>「」【】]/.test(scriptName)) return; // 模板占位不校验

  const scripts = loadScripts(packagePath);
  const record = {
    file: toPosix(relative(ROOT, file)),
    line,
    command: segment,
    package: toPosix(relative(ROOT, packagePath)),
    script: scriptName,
  };
  commandsChecked.push({ type: 'npm', ...record });
  if (scripts === null) {
    invalidCommands.push({ ...record, reason: `package.json 不存在：${record.package}` });
    return;
  }
  if (!Object.prototype.hasOwnProperty.call(scripts, scriptName)) {
    const available = Object.keys(scripts).sort().join(', ');
    invalidCommands.push({ ...record, reason: `script "${scriptName}" 不存在（可用：${available}）` });
  }
  void explicitPrefix;
}

function checkMvnSegment(segment, file, line) {
  const cleaned = segment.replace(/^\((?:.*?)\)\s*/, '');
  if (!/^(?:\.\/)?mvnw?\b/.test(cleaned)) return;
  const tokens = cleaned.split(/\s+/).slice(1);
  const goals = [];
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i];
    if (!token) continue;
    if (token.startsWith('-')) {
      if (MVN_OPTIONS_WITH_VALUE.has(token)) i += 1; // 吃掉选项值
      continue;
    }
    if (/[|><&`$]/.test(token)) break; // 进入重定向 / 管道
    if (/[\u4e00-\u9fff]/.test(token)) break; // 混入中文说明
    if (/^[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?$/.test(token)) goals.push(token);
  }
  if (goals.length === 0) return;
  const record = {
    file: toPosix(relative(ROOT, file)),
    line,
    command: segment,
    goals,
  };
  commandsChecked.push({ type: 'mvn', ...record });
  const unknown = goals.filter((g) => !KNOWN_GOALS.has(g));
  if (unknown.length > 0) {
    warnings.push({
      kind: 'mvn-goal',
      file: record.file,
      line,
      command: segment,
      message: `未知 Maven goal：${unknown.join(', ')}（白名单外，仅警告）`,
    });
  }
}

function checkCommandsInFile(file) {
  const content = readFileSync(file, 'utf8');
  const lines = content.split(/\r?\n/);
  let inFence = false;
  lines.forEach((text, idx) => {
    if (FENCE_RE.test(text)) {
      inFence = !inFence;
      return;
    }
    const candidates = [];
    if (inFence) {
      candidates.push(text);
    } else {
      INLINE_CODE_RE.lastIndex = 0;
      let m;
      while ((m = INLINE_CODE_RE.exec(text)) !== null) candidates.push(m[1]);
      // 非围栏的裸命令行（如 CI 文档里的示例）
      if (/^\s*(?:\$|nohup\s+)?(?:mvn|npm)\s/.test(text)) candidates.push(text);
    }
    for (const candidate of candidates) {
      for (const segment of commandSegments(candidate)) {
        checkNpmSegment(segment, file, idx + 1);
        checkMvnSegment(segment, file, idx + 1);
      }
    }
  });
}

function summarize() {
  const pkgScripts = loadScripts(DEFAULT_NPM_PACKAGE);
  return {
    generatedAt: new Date().toISOString(),
    root: toPosix(ROOT),
    filesScanned: filesScanned.map((f) => toPosix(relative(ROOT, f))),
    summary: {
      files: filesScanned.length,
      links: linksChecked.length,
      commands: commandsChecked.length,
      npmCommands: commandsChecked.filter((c) => c.type === 'npm').length,
      mvnCommands: commandsChecked.filter((c) => c.type === 'mvn').length,
      brokenLinks: brokenLinks.length,
      invalidCommands: invalidCommands.length,
      warnings: warnings.length,
    },
    packageJson: {
      path: toPosix(relative(ROOT, DEFAULT_NPM_PACKAGE)),
      scripts: pkgScripts ? Object.keys(pkgScripts).sort() : [],
    },
    brokenLinks,
    invalidCommands,
    warnings,
    linksChecked,
    commandsChecked,
  };
}

function printHuman(report) {
  const out = [];
  out.push(`docs-check · ${report.root}`);
  out.push(`扫描 ${report.summary.files} 个 Markdown 文件 · ${report.summary.links} 条相对链接 · ${report.summary.commands} 条命令（npm ${report.summary.npmCommands} / mvn ${report.summary.mvnCommands}）`);
  out.push('');

  if (report.brokenLinks.length > 0) {
    out.push(`✗ 断链 ${report.brokenLinks.length} 条`);
    for (const item of report.brokenLinks) {
      out.push(`  ${item.file}:${item.line}  →  ${item.target}`);
      out.push(`      解析为 ${item.resolved}（不存在）`);
    }
    out.push('');
  }

  if (report.invalidCommands.length > 0) {
    out.push(`✗ 失效命令引用 ${report.invalidCommands.length} 条`);
    for (const item of report.invalidCommands) {
      out.push(`  ${item.file}:${item.line}  ${item.command}`);
      out.push(`      ${item.reason}`);
    }
    out.push('');
  }

  if (report.warnings.length > 0) {
    out.push(`! 警告 ${report.warnings.length} 条（不导致失败）`);
    for (const item of report.warnings) {
      out.push(`  ${item.file}${item.line ? `:${item.line}` : ''}  ${item.message}`);
    }
    out.push('');
  }

  if (report.brokenLinks.length === 0 && report.invalidCommands.length === 0) {
    out.push('✓ 所有相对链接与命令引用均有效');
  } else {
    out.push('修复方式：改链接指向真实文件，或把命令改成 package.json / pom.xml 中真实存在的目标。');
  }
  out.push('文档生命周期规范见 docs/guide/documentation.md。');
  process.stdout.write(`${out.join('\n')}\n`);
}

function main() {
  for (const file of collectMarkdownFiles()) filesScanned.push(file);
  for (const file of filesScanned) {
    checkLinksInFile(file);
    checkCommandsInFile(file);
  }
  const report = summarize();
  if (JSON_MODE) {
    process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  } else {
    printHuman(report);
  }
  const failed = report.brokenLinks.length > 0 || report.invalidCommands.length > 0;
  process.exit(failed ? 1 : 0);
}

main();
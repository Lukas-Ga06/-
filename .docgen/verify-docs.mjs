// Verify internal markdown links in docs/guide/*.md.
// Usage: node .docgen/verify-docs.mjs
import fs from 'node:fs';
import path from 'node:path';

const GUIDE = path.join(process.cwd(), 'docs', 'guide');
const files = fs.readdirSync(GUIDE).filter((f) => f.endsWith('.md'));

let problems = 0;
const report = [];

// Strip fenced code blocks so sample links are ignored.
const stripCodeFences = (text) => text.replace(/```[\s\S]*?```/g, (m) => m.split('\n').map(() => '').join('\n'));

for (const file of files) {
  const text = fs.readFileSync(path.join(GUIDE, file), 'utf8');
  const body = stripCodeFences(text);
  const links = [...body.matchAll(/\[([^\]]*)\]\(([^)\s]+)\)/g)].map((m) => ({ href: m[2], index: m.index }));
  for (const link of links) {
    if (/^(https?:|mailto:|#)/.test(link.href)) continue;
    const [p] = link.href.split('#');
    if (!p) continue;
    if (!fs.existsSync(path.resolve(GUIDE, p))) {
      const lineNo = body.slice(0, link.index).split('\n').length;
      report.push(`BROKEN LINK ${file}:${lineNo} -> ${link.href}`);
      problems++;
    }
  }
  report.push(`STAT ${file}: ${text.split('\n').length} lines, ${links.length} links`);
}

console.log(report.join('\n'));
console.log(problems === 0 ? '\nOK: no broken internal links.' : `\nFAILED: ${problems} problem(s).`);
process.exitCode = problems === 0 ? 0 : 1;

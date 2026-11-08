// Count Spring MVC endpoint mappings across controller + channel packages.
// Counts annotations directly (robust to single-line/one-arg mappings and missing `value =`).
// Usage: node .docgen/count-endpoints.mjs
import fs from 'node:fs';
import path from 'node:path';

const BASE = 'travel-agent-server/src/main/java/com/travel/agent';
const packages = ['controller', 'channel'];
const annRe = /@(Get|Post|Put|Delete|Patch)Mapping\b/g;

let grandTotal = 0;
const all = [];

for (const pkg of packages) {
  const dir = path.join(BASE, pkg);
  if (!fs.existsSync(dir)) continue;
  let pkgTotal = 0;
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.java')).sort();
  for (const f of files) {
    const src = fs.readFileSync(path.join(dir, f), 'utf8');
    const cls = src.match(/@RequestMapping\s*\(([^)]*)\)[\s\S]{0,400}?public class/);
    const prefix = cls ? ((cls[1].match(/"([^"]*)"/) || [])[1] ?? '') : '';
    const count = (src.match(annRe) || []).length;
    if (!count) continue;
    all.push({ pkg, file: f.replace('.java', ''), prefix, count });
    pkgTotal += count;
  }
  console.log(`[${pkg}]`);
  for (const r of all.filter((x) => x.pkg === pkg)) {
    console.log(`  ${r.file.padEnd(32)} ${String(r.count).padStart(2)}   prefix="${r.prefix}"`);
  }
  console.log(`  subtotal: ${pkgTotal} across ${files.length} files\n`);
  grandTotal += pkgTotal;
}
console.log(`GRAND TOTAL endpoint mappings: ${grandTotal}`);

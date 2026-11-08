// Cross-check frontend route table against App.tsx.
// Usage: node .docgen/check-routes.mjs
import fs from 'node:fs';

const src = fs.readFileSync('travel-agent-web/src/App.tsx', 'utf8');
const paths = [...src.matchAll(/<Route\s+path="([^"]*)"/g)].map((m) => m[1]);
console.log(`Route path= count: ${paths.length}`);
for (const p of paths) console.log(`  ${p}`);
console.log(`Navigate elements: ${(src.match(/<Navigate/g) || []).length}`);

const pagesDir = 'travel-agent-web/src/pages';
const pages = fs
  .readdirSync(pagesDir, { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name)
  .sort();
console.log(`\nPage directories: ${pages.length}`);
console.log(`  ${pages.join(', ')}`);

// Enumerate ${ENV_VAR:default} placeholders across backend config files.
// Usage: node .docgen/list-env.mjs
import fs from 'node:fs';

const files = [
  'travel-agent-server/src/main/resources/application.yml',
  'travel-agent-server/src/main/resources/application-mysql.yml',
  'travel-agent-server/src/test/resources/application-test.yml',
];

const names = new Map();
for (const f of files) {
  if (!fs.existsSync(f)) {
    console.log(`(missing) ${f}`);
    continue;
  }
  const src = fs.readFileSync(f, 'utf8');
  const re = /\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}/g;
  let m;
  let count = 0;
  while ((m = re.exec(src)) !== null) {
    count++;
    if (!names.has(m[1])) names.set(m[1], { file: [], def: m[2] ?? '' });
    names.get(m[1]).file.push(`${f.split('/').pop()}:${src.slice(0, m.index).split('\n').length}`);
  }
  console.log(`${f.split('/').pop()}: ${count} placeholders`);
}

console.log(`\nDistinct env vars in yml: ${names.size}`);
for (const n of [...names.keys()].sort()) {
  const info = names.get(n);
  console.log(`${n.padEnd(30)} default=${JSON.stringify(info.def).padEnd(38)} ${info.file.join(', ')}`);
}

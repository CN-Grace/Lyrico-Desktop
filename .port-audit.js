// Temporary port-audit script: classifies Kotlin files by Android-platform coupling.
const fs = require('fs'), path = require('path');
const root = 'lyrico-app/src/main/java';
const OKP = ['androidx.compose.', 'androidx.annotation.', 'androidx.collection.', 'androidx.navigation.'];
const SEP = path.sep;
function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out); else if (e.name.endsWith('.kt')) out.push(p);
  }
  return out;
}
const rows = [];
for (const p of walk(root)) {
  const t = fs.readFileSync(p, 'utf8');
  const bad = [];
  for (const m of t.matchAll(/^import\s+(\S+)/gm)) {
    const i = m[1];
    if (i.startsWith('android.') || i.startsWith('androidx.')) {
      if (OKP.some(k => i.startsWith(k))) continue;
      bad.push(i);
    }
  }
  if (bad.length) rows.push({ file: p.split(SEP).join('/'), imports: [...new Set(bad)] });
}
const g = {};
for (const r of rows) {
  const rel = r.file.replace(root + '/com/lonx/lyrico/', '');
  const k = rel.includes('/') ? rel.split('/')[0] : '(root)';
  g[k] = (g[k] || 0) + 1;
}
console.log('--- Android-bound files by package ---');
for (const [k, v] of Object.entries(g).sort((a, b) => b[1] - a[1])) console.log(String(v).padStart(4), k);
fs.writeFileSync('.port-audit.json', JSON.stringify(rows, null, 1));
console.log('written .port-audit.json', rows.length);
const only = process.argv[2];
if (only) {
  console.log('--- files under ' + only + ' ---');
  for (const r of rows.filter(r => r.file.includes(only))) {
    console.log(r.file.replace(root + '/com/lonx/lyrico/', ''), '  <=', r.imports.join(', '));
  }
}

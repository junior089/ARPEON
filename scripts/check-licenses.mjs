import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const lock = JSON.parse(await readFile(resolve(root, 'frontend/package-lock.json'), 'utf8'));
const allowed = new Set(['MIT', 'ISC', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', '0BSD',
  'CC0-1.0', 'OFL-1.1', 'Python-2.0', 'BlueOak-1.0.0', '(MIT OR CC0-1.0)', '(MIT OR Apache-2.0)',
  'MIT-0', '(Apache-2.0 AND BSD-3-Clause)',
  '(MIT OR WTFPL)', '(MIT OR GPL-3.0-or-later)', '(MIT AND Zlib)',
  '(BSD-2-Clause OR MIT OR Apache-2.0)']);
const buildOnly = new Set(['MPL-2.0', 'CC-BY-4.0']);
const inventory = [];
const failures = [];
for (const [path, entry] of Object.entries(lock.packages)) {
  if (!path) continue;
  // Lock metadata also covers platform-specific optional packages absent on this OS.
  const license = entry.license;
  if (!allowed.has(license) && !(entry.dev && buildOnly.has(license))) failures.push(`${path}@${entry.version}: unreviewed license ${license}`);
  if (!entry.integrity || !entry.resolved?.startsWith('https://registry.npmjs.org/')) failures.push(`${path}: missing integrity or non-registry source`);
  try {
    const installed = JSON.parse(await readFile(resolve(root, 'frontend', path, 'package.json'), 'utf8'));
    if (installed.version !== entry.version || installed.license !== license) failures.push(`${path}: installed metadata differs from the lockfile`);
  } catch (error) {
    if (!(entry.optional && error.code === 'ENOENT')) throw error;
  }
  inventory.push({ package: path.replace(/^node_modules\//, ''), version: entry.version, license, development: entry.dev ?? false });
}
inventory.sort((a, b) => a.package.localeCompare(b.package, 'en'));
if (failures.length) throw new Error(failures.join('\n'));
const serialized = JSON.stringify(inventory, null, 2) + '\n';
const inventoryPath = resolve(root, 'docs/legal/npm-dependencies.json');
if (process.argv.includes('--write')) await writeFile(inventoryPath, serialized);
else if (await readFile(inventoryPath, 'utf8') !== serialized) throw new Error('License inventory differs from the lockfile; review and regenerate it.');
console.log(`Verified ${inventory.length} locked npm licenses and integrity entries.`);

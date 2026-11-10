#!/usr/bin/env node
// Verify the Fabric 1.21.11 mapping table (mappings/fabric/v1_21_11.json).
//
// The fabric counterpart of verify_mappings.mjs (which guards the Forge 1.8.9
// SRG table). The fabric table binds semantic keys to INTERMEDIARY names — the
// runtime naming layer of every production Fabric launch (the middle layer
// between Mojang official names and Yarn dev names).
//
// What it checks
// --------------
//  1. every entry's class/member exists in mappings/verify/v1_21_11-members.txt
//     (ground truth extracted from FabricMC/intermediary @ 1.21.11 and
//     Yarn 1.21.11+build.6 — see scripts/generate_fabric_1_21_11.py);
//  2. the entry's readable name (`mcp`) agrees with the Yarn name for that
//     intermediary member, and `official` (when present) agrees with the
//     Mojang obfuscated name — catches "the id exists but is a different
//     member", which bare existence checks cannot see;
//  3. every `fabric:*` key referenced by MappingContext call sites in the
//     Kotlin/Java sources resolves through the table (key or alias) with the
//     right accessor kind (field vs method) — the forge script's call-site
//     discipline, applied to the fabric table.
//
// Keys listed in mappings/verify/v1_21_11-unmapped.txt are known to have no
// faithful 1.21.11 binding (DataTracker/flag/Vec3d-only members); they are
// reported as documented-unmapped, not failures.
//
// Run from the repository root:  node Doppel/scripts/verify_mappings_fabric.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const mappingPath = path.join(root, 'Doppel', 'mappings', 'fabric', 'v1_21_11.json');
const membersPath = path.join(root, 'Doppel', 'mappings', 'verify', 'v1_21_11-members.txt');
const unmappedPath = path.join(root, 'Doppel', 'mappings', 'verify', 'v1_21_11-unmapped.txt');

function die(msg) { console.error(`FAIL  ${msg}`); process.exit(1); }

if (!fs.existsSync(mappingPath)) die(`mapping table not found: ${mappingPath}`);
if (!fs.existsSync(membersPath)) die(`member ground truth not found: ${membersPath}`);

const mapping = JSON.parse(fs.readFileSync(mappingPath, 'utf8'));

// member ground truth: class -> kind -> Map(member_intermediary -> {yarn, official, desc})
const classes = new Map();
for (const line of fs.readFileSync(membersPath, 'utf8').split('\n')) {
  if (!line || line.startsWith('#')) continue;
  const [cls, kind, member, yarn, official, desc] = line.split('\t');
  if (!classes.has(cls)) classes.set(cls, { field: new Map(), method: new Map() });
  classes.get(cls)[kind].set(member, { yarn, official, desc });
}

// documented-unmapped keys (warn only)
const unmapped = new Set();
if (fs.existsSync(unmappedPath)) {
  for (const line of fs.readFileSync(unmappedPath, 'utf8').split('\n')) {
    const name = line.split('#')[0].trim();
    if (name) unmapped.add(name);
  }
}

// ─── 1 + 2. entry checks ─────────────────────────────────────────────────────
const invalid = [];
const mismatched = [];
let checked = 0;

for (const [key, entry] of Object.entries(mapping)) {
  if (key === '_meta') continue;
  if (!entry || typeof entry !== 'object') { invalid.push(`${key}: not an object`); continue; }
  const cls = entry.class;
  const kind = entry.method ? 'method' : entry.field ? 'field' : null;
  const member = entry.method || entry.field;
  if (!cls || !kind || !member) {
    invalid.push(`${key}: entry has neither method nor field, or no class`);
    continue;
  }
  checked++;

  const bucket = classes.get(cls);
  if (!bucket) { invalid.push(`${key}: class ${cls} is not in the ground truth`); continue; }
  const row = bucket[kind].get(member);
  if (!row) {
    invalid.push(`${key}: ${cls}/${member} — no such ${kind} in 1.21.11 ground truth`);
    continue;
  }

  if (row.yarn && entry.mcp && row.yarn !== entry.mcp) {
    mismatched.push(`${key}: table says mcp="${entry.mcp}" but ${member} is Yarn "${row.yarn}"`);
  }
  if (entry.official && row.official && row.official !== '-' && row.official !== entry.official) {
    mismatched.push(`${key}: table says official="${entry.official}" but ${member} is "${row.official}"`);
  }
}

// ─── 3. call-site scan (mirrors verify_mappings.mjs) ────────────────────────
const byAlias = new Map();
for (const [key, entry] of Object.entries(mapping)) {
  if (!entry || typeof entry !== 'object') continue;
  byAlias.set(key, { key, entry });
  for (const alias of entry.aliases || []) {
    byAlias.set(alias, { key, entry });
    byAlias.set(`fabric:${alias}`, { key, entry });
  }
  const bare = key.replace(/^fabric:/, '');
  if (!byAlias.has(bare)) byAlias.set(bare, { key, entry });
}
function lookupEntry(rawKey) {
  return byAlias.get(rawKey) || byAlias.get(`fabric:${rawKey}`) || null;
}

const ACCESSORS = {
  invokeMethod: 'method',
  getMethod: 'method',
  getFieldValue: 'field',
  getField: 'field',
};

const SRC_ROOT = path.join(root, 'Doppel');
const SOURCE_EXT = new Set(['.kt', '.java']);
function walk(dir, out = []) {
  for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
    if (item.isDirectory()) {
      if (['build', 'target', '.git', 'node_modules', '.gradle'].includes(item.name)) continue;
      walk(path.join(dir, item.name), out);
    } else if (SOURCE_EXT.has(path.extname(item.name))) {
      out.push(path.join(dir, item.name));
    }
  }
  return out;
}

const badKind = [];
const unknownKey = [];
let callSites = 0;

const CALL_RE = /MappingContext\s*\.\s*(invokeMethod|getMethod|getFieldValue|getField)\s*\(/g;
const FABRIC_KEY_RE = /^fabric:/;
for (const file of walk(SRC_ROOT)) {
  const text = fs.readFileSync(file, 'utf8');
  const rel = path.relative(root, file);
  let m;
  CALL_RE.lastIndex = 0;
  while ((m = CALL_RE.exec(text)) !== null) {
    const accessor = m[1];
    let depth = 1;
    let i = CALL_RE.lastIndex;
    for (; i < text.length && depth > 0; i++) {
      if (text[i] === '(') depth++;
      else if (text[i] === ')') depth--;
    }
    const args = text.slice(CALL_RE.lastIndex, i - 1);
    const parts = [];
    let d = 0;
    let cur = '';
    for (const ch of args) {
      if (ch === '(' || ch === '[') d++;
      else if (ch === ')' || ch === ']') d--;
      if (ch === ',' && d === 0) { parts.push(cur); cur = ''; continue; }
      cur += ch;
    }
    parts.push(cur);
    const keyPart = accessor === 'invokeMethod' || accessor === 'getFieldValue' ? parts[1] : parts[0];
    const lit = keyPart && keyPart.trim().match(/^"([^"\\]*)"$/);
    if (!lit) continue;
    const rawKey = lit[1];
    if (!FABRIC_KEY_RE.test(rawKey) && !byAlias.has(rawKey)) continue; // not a fabric key (forge table's job)
    const line = text.slice(0, m.index).split('\n').length;
    const hit = lookupEntry(rawKey);
    if (!hit) {
      if (unmapped.has(rawKey)) {
        unknownKey.push(`${rel}:${line} ${accessor}("${rawKey}") — DOCUMENTED unmappable on 1.21.11`);
      } else {
        unknownKey.push(`${rel}:${line} ${accessor}("${rawKey}") — no such mapping key`);
      }
      continue;
    }
    callSites++;
    const want = ACCESSORS[accessor];
    const has = hit.entry.method ? 'method' : hit.entry.field ? 'field' : 'neither';
    if (has !== want) {
      badKind.push(
        `${rel}:${line} ${accessor}("${rawKey}") reads a ${has.toUpperCase()} mapping (${hit.key}); ` +
          `must use ${want === 'field' ? 'getFieldValue/getField' : 'invokeMethod/getMethod'}`,
      );
    }
  }
}

// ─── report ──────────────────────────────────────────────────────────────────
console.log(`mapping entries checked     : ${checked}`);
console.log(`entries valid               : ${checked - invalid.length}`);
console.log(`entry mismatches            : ${mismatched.length}`);
console.log(`fabric call sites resolved  : ${callSites}`);
console.log(`accessor/kind mismatches    : ${badKind.length}`);
console.log(`unresolved keys             : ${unknownKey.length} (${unknownKey.filter((l) => l.includes('DOCUMENTED')).length} documented-unmapped)`);

for (const line of invalid) console.log(`  INVALID   ${line}`);
for (const line of mismatched) console.log(`  MISMATCH  ${line}`);
for (const line of badKind) console.log(`  KIND      ${line}`);
for (const line of unknownKey) console.log(`  KEY       ${line}`);

const hardUnknown = unknownKey.filter((l) => !l.includes('DOCUMENTED'));
if (invalid.length || mismatched.length || badKind.length || hardUnknown.length) {
  console.log('\nFAIL — fix the fabric mapping table (or the call sites) before merging.');
  process.exit(1);
}
console.log('\nOK — every fabric entry matches the 1.21.11 intermediary ground truth and every call site reads it correctly.');

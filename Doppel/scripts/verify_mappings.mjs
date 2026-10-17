#!/usr/bin/env node
// Verify the Forge 1.8.9 mapping table against the real MCP/SRG names.
//
// Why this exists
// ---------------
// The mapping table silently rotted. Seven entries were wrong and every one of
// them failed closed: MappingContext logged a line, returned null, and the
// caller read that as "no" — so features died with nothing visible. Two of the
// seven could not be spotted by reading the file either: func_72945_a and
// func_72839_b have identical signatures on World, and the table had picked
// getCollidingBoundingBoxes (a list of bounding boxes) for
// getEntitiesWithinAABBExcludingEntity — so the reach raycast iterated bounding
// boxes and used them as entities.
//
// What it checks
// --------------
//  1. every `method` / `field` in the table is a member name that exists in
//     1.8.9 (mappings/verify/v1_8_9-members.txt, extracted from MCP's
//     joined.srg for 1.8.9 — the table MCP itself is built from);
//  2. where the MCP name tables know the SRG name, the table's `mcp` field must
//     agree with it. This is the check that catches "the name exists but is a
//     different member", which check 1 cannot see.
//
// Run from the repository root:  node Doppel/scripts/verify_mappings.mjs

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const mappingPath = path.join(root, 'Doppel', 'mappings', 'forge', 'v1_8_9.json');
const membersPath = path.join(root, 'Doppel', 'mappings', 'verify', 'v1_8_9-members.txt');
const methodsCsv = path.join(root, 'Doppel', 'mappings', 'verify', 'methods.csv');
const fieldsCsv = path.join(root, 'Doppel', 'mappings', 'verify', 'fields.csv');

function die(msg) { console.error(`FAIL  ${msg}`); process.exit(1); }

if (!fs.existsSync(mappingPath)) die(`mapping table not found: ${mappingPath}`);
if (!fs.existsSync(membersPath)) die(`member list not found: ${membersPath}`);

// searge -> readable MCP name
function loadMcpTable(file) {
  const table = new Map();
  if (!fs.existsSync(file)) return table;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (!line.startsWith('func_') && !line.startsWith('field_')) continue;
    const parts = line.split(',');
    if (parts.length >= 2 && !table.has(parts[0])) table.set(parts[0], parts[1]);
  }
  return table;
}

const mapping = JSON.parse(fs.readFileSync(mappingPath, 'utf8'));
const members = new Set(fs.readFileSync(membersPath, 'utf8').split(/\s+/).filter(Boolean));
const methods = loadMcpTable(methodsCsv);
const fields = loadMcpTable(fieldsCsv);

// Keys whose MCP name legitimately differs from the CSV, because the CSV is
// from an earlier MCP version. Each entry needs a reason.
const dfferencesPath = path.join(root, 'Doppel', 'mappings', 'verify', 'known-name-differences.txt');
const allowed = new Set();
if (fs.existsSync(dfferencesPath)) {
  for (const line of fs.readFileSync(dfferencesPath, 'utf8').split('\n')) {
    const name = line.split('#')[0].trim();
    if (name) allowed.add(name);
  }
}

const invalid = [];
const mismatched = [];
const fromCsvFallback = [];
let checked = 0;

for (const [key, entry] of Object.entries(mapping)) {
  if (!entry || typeof entry !== 'object') continue;
  const cls = entry.class;
  const member = entry.method || entry.field;
  if (!cls || !member) {
    invalid.push(`${key}: entry has neither method nor field, or no class`);
    continue;
  }
  checked++;

  // 1. the name must exist in 1.8.9
  if (!members.has(member)) {
    // The member list was extracted from joined.srg and is incomplete for some classes —
    // Minecraft.renderViewEntity (field_71451_h) is absent from it while fields.csv lists it.
    // Fall back to the MCP tables: a name those tables know is a real 1.8.9 member even when the
    // extraction missed it. A name neither source knows is still a failure.
    const knownByName = entry.method ? methods.has(member) : fields.has(member);
    if (!knownByName) {
      invalid.push(`${key}: ${cls}/${member} — no such member in the 1.8.9 SRG`);
      continue;
    }
    fromCsvFallback.push(`${key}: ${cls}/${member}`);
  }

  // 2. where the MCP tables know this SRG name, the readable name must agree
  const known = entry.method ? methods.get(member) : fields.get(member);
  const declared = entry.mcp;
  if (known && declared && known.toLowerCase() !== declared.toLowerCase() && !allowed.has(key)) {
    mismatched.push(`${key}: table says mcp="${declared}" but ${member} is "${known}"`);
  }
}

const ok = checked - invalid.length;
console.log(`mapping entries checked : ${checked}`);
console.log(`SRG names valid         : ${ok}`);
console.log(`SRG names INVALID       : ${invalid.length}`);
console.log(`mcp/SRG disagreements   : ${mismatched.length}`);
console.log(`confirmed via MCP tables: ${fromCsvFallback.length} (absent from the member list)`);
if (fromCsvFallback.length) {
  for (const line of fromCsvFallback) console.log(`  ${line}`);
}

if (invalid.length) {
  console.log('\ninvalid entries:');
  for (const line of invalid) console.log(`  ${line}`);
}
if (mismatched.length) {
  console.log('\nname disagreements (the name exists, but is a different member):');
  for (const line of mismatched) console.log(`  ${line}`);
}

if (invalid.length || mismatched.length) {
  console.log('\nFAIL — fix the mapping table before merging.');
  process.exit(1);
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Call-site kind check
//
// A table can be perfectly valid and still be read wrong. MappingContext resolves
// fields and methods through different entry keys ("field" vs "method"), and a
// mismatch fails closed: invokeMethod() on a FIELD entry logs one line, returns
// null, and the caller's `?: 0` turns that into a plausible-looking value. The
// feature dies with no visible symptom.
//
// That is not hypothetical. forge:keybinding_keyCode is a FIELD (field_74512_d)
// and every physical-key check read it with invokeMethod(), so the key code was
// always 0 and each synthetic-tap module (WTap/STap/BlockTap) believed no key was
// ever held. forge:entity_isInWeb had the same shape. None of it shows up in the
// name checks above, which is exactly why this pass exists.
// ─────────────────────────────────────────────────────────────────────────────

const ACCESSORS = {
  invokeMethod: 'method',
  getMethod: 'method',
  getFieldValue: 'field',
  getField: 'field',
};

// alias / bare-key resolution, mirroring MappingContext.resolveKey
const byAlias = new Map();
for (const [key, entry] of Object.entries(mapping)) {
  if (!entry || typeof entry !== 'object') continue;
  byAlias.set(key, { key, entry });
  for (const alias of entry.aliases || []) {
    byAlias.set(alias, { key, entry });
    byAlias.set(`forge:${alias}`, { key, entry });
  }
  const bare = key.replace(/^forge:/, '');
  if (!byAlias.has(bare)) byAlias.set(bare, { key, entry });
}
function lookupEntry(rawKey) {
  return byAlias.get(rawKey) || byAlias.get(`forge:${rawKey}`) || null;
}

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
// adapter/fabric is dormant and carries its own table (mappings/fabric/*), so its
// call sites cannot be checked against the 1.8.9 table.
const SKIP_DIRS = ['adapter/fabric'];
for (const file of walk(SRC_ROOT)) {
  const text = fs.readFileSync(file, 'utf8');
  const rel = path.relative(root, file);
  if (SKIP_DIRS.some((d) => rel.replace(/\\/g, '/').includes(d))) continue;
  let m;
  CALL_RE.lastIndex = 0;
  while ((m = CALL_RE.exec(text)) !== null) {
    const accessor = m[1];
    // find the matching close paren of this call
    let depth = 1;
    let i = CALL_RE.lastIndex;
    for (; i < text.length && depth > 0; i++) {
      if (text[i] === '(') depth++;
      else if (text[i] === ')') depth--;
    }
    const args = text.slice(CALL_RE.lastIndex, i - 1);
    // Split the argument list on top-level commas. The key is argument 2 for
    // (target, key, ...extra) accessors and argument 1 for single-argument ones, and it
    // is only statically checkable when that argument is exactly a string literal.
    // Taking "the last literal in the call" instead produced false positives on
    // invokeMethod(x, "forge:nbtBase_hasKey", "display", 10) — extra literal arguments
    // are normal (NBT key names, equipment slots).
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
    if (!lit) continue; // dynamic key — not statically checkable
    const rawKey = lit[1];
    const line = text.slice(0, m.index).split('\n').length;
    const hit = lookupEntry(rawKey);
    if (!hit) {
      unknownKey.push(`${rel}:${line} ${accessor}("${rawKey}") — no such mapping key`);
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

console.log(`\ncall sites checked        : ${callSites}`);
console.log(`accessor/kind mismatches  : ${badKind.length}`);
console.log(`unknown mapping keys      : ${unknownKey.length}`);
if (badKind.length) {
  console.log('\nmismatches (the entry exists but is read the wrong way → always null):');
  for (const line of badKind) console.log(`  ${line}`);
}
if (unknownKey.length) {
  console.log('\nunknown keys:');
  for (const line of unknownKey) console.log(`  ${line}`);
}
if (badKind.length) {
  console.log('\nFAIL — fix the accessor or the mapping table before merging.');
  process.exit(1);
}

console.log('\nOK — every mapping entry resolves to a real 1.8.9 member and every call site reads it correctly.');

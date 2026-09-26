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
    invalid.push(`${key}: ${cls}/${member} — no such member in the 1.8.9 SRG`);
    continue;
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
console.log('\nOK — every mapping entry resolves to a real 1.8.9 member.');

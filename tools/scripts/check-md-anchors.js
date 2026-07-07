#!/usr/bin/env node
// Validate internal anchor links in a markdown file.
// Usage: node tools/scripts/check-md-anchors.js <markdown-file> [...]

const fs = require('fs');
const path = require('path');

function githubSlug(text) {
  // GitHub's anchor algorithm:
  // 1. Lowercase
  // 2. Remove everything that is not a letter, digit, hyphen, or underscore
  //    (whitespace becomes hyphen; em-dash, period, colon, etc. are removed)
  let s = text.toLowerCase();
  // Replace whitespace with hyphen
  s = s.replace(/\s+/g, '-');
  // Remove non-alphanumeric/non-hyphen/non-underscore (keep CJK characters)
  // Note: GitHub keeps CJK characters in anchors as-is (URL-encoded)
  s = s.replace(/[^\p{L}\p{N}\-_]/gu, '');
  return s;
}

function extractHeaders(content) {
  const headers = [];
  const lines = content.split(/\r?\n/);
  let inFence = false;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^```/.test(line)) { inFence = !inFence; continue; }
    if (inFence) continue;
    const m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (m) {
      headers.push({ level: m[1].length, text: m[2], slug: githubSlug(m[2]), line: i + 1 });
    }
  }
  return headers;
}

function extractReferences(content) {
  const refs = [];
  const re = /\]\(#([^)]+)\)/g;
  const lines = content.split(/\r?\n/);
  let inFence = false;
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^```/.test(line)) { inFence = !inFence; continue; }
    if (inFence) continue;
    let m;
    while ((m = re.exec(line)) !== null) {
      refs.push({ anchor: m[1], line: i + 1, context: line.trim().slice(0, 80) });
    }
  }
  return refs;
}

function checkFile(filepath) {
  const content = fs.readFileSync(filepath, 'utf-8');
  const headers = extractHeaders(content);
  const refs = extractReferences(content);
  const headerSlugs = new Set(headers.map(h => h.slug));
  const duplicates = [];
  const seen = new Map();
  for (const h of headers) {
    if (seen.has(h.slug)) {
      duplicates.push({ slug: h.slug, firstLine: seen.get(h.slug), secondLine: h.line });
    } else {
      seen.set(h.slug, h.line);
    }
  }
  const broken = refs.filter(r => !headerSlugs.has(r.anchor));
  return { headers, refs, broken, duplicates };
}

function main() {
  const files = process.argv.slice(2);
  if (files.length === 0) {
    console.error('Usage: node tools/scripts/check-md-anchors.js <file.md> [...]');
    process.exit(2);
  }
  let totalBroken = 0;
  let totalDuplicates = 0;
  for (const f of files) {
    if (!fs.existsSync(f)) {
      console.error(`File not found: ${f}`);
      continue;
    }
    const { headers, refs, broken, duplicates } = checkFile(f);
    console.log(`\n=== ${f} ===`);
    console.log(`  headers: ${headers.length}, references: ${refs.length}`);
    if (duplicates.length > 0) {
      console.log(`  DUPLICATE ANCHORS: ${duplicates.length}`);
      for (const d of duplicates) {
        console.log(`    - "${d.slug}" (lines ${d.firstLine} & ${d.secondLine})`);
      }
      totalDuplicates += duplicates.length;
    }
    if (broken.length > 0) {
      console.log(`  BROKEN ANCHORS: ${broken.length}`);
      for (const b of broken) {
        console.log(`    line ${b.line}: #${b.anchor}  in: ${b.context}`);
      }
      totalBroken += broken.length;
    } else {
      console.log(`  OK — all ${refs.length} anchor references resolve.`);
    }
  }
  if (totalBroken > 0 || totalDuplicates > 0) {
    console.log(`\nSUMMARY: ${totalBroken} broken, ${totalDuplicates} duplicate`);
    process.exit(1);
  } else {
    console.log('\nAll anchor links valid.');
  }
}

main();

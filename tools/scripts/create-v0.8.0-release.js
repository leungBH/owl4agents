#!/usr/bin/env node
// Create the v0.8.0 GitHub Release using a PAT supplied via the GH_TOKEN env var.
// Token is read from process.env.GH_TOKEN and never written to disk or logged.

const fs = require('fs');
const path = require('path');
const https = require('https');

const REPO = 'leungBH/owl4agents';
const TAG = 'v0.8.0';
const NOTES_PATH = path.resolve(__dirname, '..', '..', 'reports', 'acceptance', 'v0.8.0-release-notes.md');
const COMMIT_SHA = 'ff1ec876b74ebb63744b0f620557c25b654b9c69';

function die(msg) {
  console.error('ERROR: ' + msg);
  process.exit(1);
}

const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN;
if (!token) {
  die('GH_TOKEN env var not set. Run:  $env:GH_TOKEN = "<your_pat>"');
  return;
}

if (!fs.existsSync(NOTES_PATH)) {
  die('Release notes file not found: ' + NOTES_PATH);
}

const notes = fs.readFileSync(NOTES_PATH, 'utf-8');

const payload = JSON.stringify({
  tag_name: TAG,
  target_commitish: 'main',
  name: 'v0.8.0 - MCP Streamable HTTP transport (SSE)',
  body: notes,
  draft: false,
  prerelease: false,
  generate_release_notes: false
});

const url = new URL(`https://api.github.com/repos/${REPO}/releases`);

const headers = {
  'User-Agent': 'owl4agents-release-script',
  'Accept': 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
  'Authorization': 'Bearer ' + token,
  'Content-Type': 'application/json',
  'Content-Length': Buffer.byteLength(payload)
};

console.log('POSTing release for ' + TAG + ' to ' + url.href);
console.log('  tag:        ' + TAG);
console.log('  commitish:  main');
console.log('  notes file: ' + NOTES_PATH + ' (' + notes.length + ' bytes)');
console.log('  token:      [redacted, ' + token.length + ' chars]');

const req = https.request({
  method: 'POST',
  hostname: url.hostname,
  path: url.pathname,
  port: 443,
  headers: headers
}, (res) => {
  let body = '';
  res.on('data', (chunk) => { body += chunk; });
  res.on('end', () => {
    const status = res.statusCode;
    console.log('  HTTP ' + status);
    if (status >= 200 && status < 300) {
      try {
        const obj = JSON.parse(body);
        console.log('  Created release: ' + obj.html_url);
        console.log('  ID:              ' + obj.id);
        console.log('  tag:             ' + obj.tag_name);
        console.log('  target_commitish:' + obj.target_commitish);
        console.log('  assets:          ' + (obj.assets || []).length);
        console.log('  body length:     ' + (obj.body || '').length);
        console.log('\nRelease created successfully.');
      } catch (e) {
        console.log('  body: ' + body);
      }
    } else {
      console.log('  body: ' + body);
      process.exit(2);
    }
  });
});

req.on('error', (e) => {
  die('Request failed: ' + e.message);
});

req.write(payload);
req.end();

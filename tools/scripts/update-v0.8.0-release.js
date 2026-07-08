#!/usr/bin/env node
// Update the body of the existing v0.8.0 GitHub Release using a PAT supplied via the GH_TOKEN env var.
// Token is read from process.env.GH_TOKEN and never written to disk or logged.

const fs = require('fs');
const path = require('path');
const https = require('https');

const REPO = 'leungBH/owl4agents';
const RELEASE_ID = 349974309; // v0.8.0 release id from the GitHub API
const NOTES_PATH = path.resolve(__dirname, '..', '..', 'reports', 'acceptance', 'v0.8.0-release-notes-v2.md');

function die(msg) {
  console.error('ERROR: ' + msg);
  process.exit(1);
}

const token = process.env.GH_TOKEN || process.env.GITHUB_TOKEN;
if (!token) {
  die('GH_TOKEN env var not set. Run:  $env:GH_TOKEN = "<your_pat>"');
}

if (!fs.existsSync(NOTES_PATH)) {
  die('Release notes file not found: ' + NOTES_PATH);
}

const body = fs.readFileSync(NOTES_PATH, 'utf-8');

const payload = JSON.stringify({ body });

const url = new URL(`https://api.github.com/repos/${REPO}/releases/${RELEASE_ID}`);

const headers = {
  'User-Agent': 'owl4agents-release-script',
  'Accept': 'application/vnd.github+json',
  'X-GitHub-Api-Version': '2022-11-28',
  'Authorization': 'Bearer ' + token,
  'Content-Type': 'application/json',
  'Content-Length': Buffer.byteLength(payload)
};

console.log('PATCHing release ' + RELEASE_ID + ' (' + REPO + ')');
console.log('  body file: ' + NOTES_PATH + ' (' + body.length + ' bytes)');
console.log('  token:     [redacted, ' + token.length + ' chars]');

const req = https.request({
  method: 'PATCH',
  hostname: url.hostname,
  path: url.pathname,
  port: 443,
  headers: headers
}, (res) => {
  let raw = '';
  res.on('data', (chunk) => { raw += chunk; });
  res.on('end', () => {
    const status = res.statusCode;
    console.log('  HTTP ' + status);
    if (status >= 200 && status < 300) {
      try {
        const obj = JSON.parse(raw);
        console.log('  Updated release: ' + obj.html_url);
        console.log('  body length:     ' + (obj.body || '').length);
        console.log('  published_at:    ' + obj.published_at);
        console.log('  updated_at:      ' + obj.updated_at);
        console.log('\nRelease body updated successfully.');
      } catch (e) {
        console.log('  body: ' + raw);
      }
    } else {
      console.log('  body: ' + raw);
      process.exit(2);
    }
  });
});

req.on('error', (e) => {
  die('Request failed: ' + e.message);
});

req.write(payload);
req.end();

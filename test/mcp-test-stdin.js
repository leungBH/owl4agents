#!/usr/bin/env node

/**
 * Test MCP server stdin forwarding with shadow jar.
 */

const { spawnSync } = require('child_process');
const path = require('path');

const PROJECT_ROOT = path.resolve(__dirname, '..');
const JAR_PATH = path.join(PROJECT_ROOT, 'modules', 'ontology-cli', 'build', 'libs', 'owl4agents.jar');

// Test requests
const requests = [
  JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'test', version: '1.0' }
    }
  }),
  JSON.stringify({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/list',
    params: {}
  }),
  JSON.stringify({
    jsonrpc: '2.0',
    id: 3,
    method: 'tools/call',
    params: {
      name: 'ontology_list',
      arguments: {}
    }
  })
];

const input = requests.join('\n') + '\n';

console.log('Testing MCP server with shadow jar...');
console.log('Jar:', JAR_PATH);
console.log('='.repeat(60));

const result = spawnSync('java', ['-jar', JAR_PATH, 'mcp'], {
  input: input,
  encoding: 'utf-8',
  timeout: 30000,
  cwd: PROJECT_ROOT,
  stdio: ['pipe', 'pipe', 'pipe']
});

console.log('\nSTDOUT:');
if (result.stdout) {
  // Filter out Gradle-like output, show only JSON
  const lines = result.stdout.split('\n');
  lines.forEach(line => {
    if (line.trim().startsWith('{') || line.trim().startsWith('[')) {
      console.log(line);
    }
  });
} else {
  console.log('(empty)');
}

console.log('\nSTDERR:');
console.log(result.stderr || '(empty)');

console.log('\nExit code:', result.status);

// Parse JSON responses
if (result.stdout) {
  const jsonLines = result.stdout.split('\n').filter(l => l.trim().startsWith('{'));
  if (jsonLines.length > 0) {
    console.log('\n' + '='.repeat(60));
    console.log('Parsed JSON responses:');
    jsonLines.forEach((line, i) => {
      try {
        const obj = JSON.parse(line);
        console.log(`\nResponse ${i + 1}:`, JSON.stringify(obj, null, 2));
      } catch (e) {
        console.log(`\nLine ${i + 1} (not JSON):`, line);
      }
    });
  }
}

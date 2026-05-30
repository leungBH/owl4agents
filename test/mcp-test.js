#!/usr/bin/env node

/**
 * Simple MCP server test - sends JSON-RPC requests and checks responses.
 */

const { spawnSync } = require('child_process');
const path = require('path');

const PROJECT_ROOT = path.resolve(__dirname, '..');

// Build the project first
console.log('Building project...');

// Test requests
const requests = [
  // 1. Initialize
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
  // 2. Initialized notification
  JSON.stringify({
    jsonrpc: '2.0',
    method: 'notifications/initialized'
  }),
  // 3. List tools
  JSON.stringify({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/list',
    params: {}
  }),
  // 4. Call ontology_list
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

console.log('\nSending MCP requests...');
console.log('='.repeat(60));

const result = spawnSync('.\\gradlew.bat', [
  ':modules:ontology-cli:run',
  `--args=mcp`,
  '--console=plain'
], {
  input: input,
  encoding: 'utf-8',
  timeout: 30000,
  cwd: PROJECT_ROOT,
  shell: true,
  stdio: ['pipe', 'pipe', 'pipe']
});

console.log('\nSTDOUT:');
console.log(result.stdout || '(empty)');

console.log('\nSTDERR:');
console.log(result.stderr || '(empty)');

console.log('\nExit code:', result.status);

// Parse responses
if (result.stdout) {
  const lines = result.stdout.split('\n').filter(l => l.trim().startsWith('{'));
  console.log('\nParsed responses:');
  lines.forEach((line, i) => {
    try {
      const response = JSON.parse(line);
      console.log(`\nResponse ${i + 1}:`, JSON.stringify(response, null, 2));
    } catch (e) {
      // Not JSON, skip
    }
  });
}

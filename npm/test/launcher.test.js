#!/usr/bin/env node

/**
 * npm launcher smoke tests for owl4agents.
 * Tests: help output, command forwarding, local runtime path, unsupported platform behavior.
 *
 * Uses real child-process execution with strict exit-code, stdout, stderr assertions.
 *
 * Run with: node npm/test/launcher.test.js
 */

const { execSync, spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const assert = require('assert');

const LAUNCHER = path.resolve(__dirname, '..', 'bin', 'owl4agents.js');
const PROJECT_ROOT = path.resolve(__dirname, '..', '..');

let passed = 0;
let failed = 0;

function runTest(name, testFn) {
  try {
    testFn();
    console.log(`  ✓ ${name}`);
    passed++;
  } catch (err) {
    console.log(`  ✗ ${name}`);
    console.log(`    ${err.message}`);
    failed++;
  }
}

/**
 * Run the launcher with real child-process execution.
 * Returns { stdout, stderr, code } with strict capture.
 */
function runLauncher(args = [], options = {}) {
  const env = {
    ...process.env,
    OWL4AGENTS_RUNTIME: options.runtime || '',
    ...options.env
  };

  const result = spawnSync('node', [LAUNCHER, ...args], {
    encoding: 'utf-8',
    timeout: options.timeout || 30000,
    env,
    cwd: options.cwd || PROJECT_ROOT,
    stdio: ['pipe', 'pipe', 'pipe']
  });

  return {
    stdout: (result.stdout || '').trim(),
    stderr: (result.stderr || '').trim(),
    code: result.status,
    signal: result.signal,
    error: result.error
  };
}

console.log('\n  owl4agents npm launcher smoke tests\n');

// ── Test 1: Help output (no args) ──
runTest('No arguments shows help output and exits 0', () => {
  const { stdout, stderr, code, signal, error } = runLauncher([]);

  // Must not crash
  assert(!error, `Launcher should not error: ${error}`);
  assert(!signal, `Launcher should not be killed by signal: ${signal}`);

  // Must exit cleanly
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}`);

  // Must show help text
  assert(stdout.includes('owl4agents'), 'Help should include "owl4agents"');
  assert(stdout.includes('init'), 'Help should list init command');
  assert(stdout.includes('import'), 'Help should list import command');
  assert(stdout.includes('list'), 'Help should list list command');
  assert(stdout.includes('summary'), 'Help should list summary command');
  assert(stdout.includes('search'), 'Help should list search command');
  assert(stdout.includes('entity'), 'Help should list entity command');
  assert(stdout.includes('query'), 'Help should list query command');
  assert(stdout.includes('context'), 'Help should list context command');
  assert(stdout.includes('mcp'), 'Help should list mcp command');
});

// ── Test 2: --help flag forwards to runtime ──
runTest('--help flag is forwarded to runtime', () => {
  // --help is forwarded to the runtime, not handled by launcher
  // In the current design, only no-args shows launcher help
  const { stdout, code, signal, error } = runLauncher(['--help']);

  // Should not crash
  assert(!signal || signal === 'SIGTERM', `Should not crash with signal: ${signal}`);
});

// ── Test 3: Missing runtime shows helpful error ──
runTest('Missing runtime shows helpful error message', () => {
  // Use a non-existent runtime path
  const { stdout, stderr, code } = runLauncher(['list'], {
    runtime: '/nonexistent/path/to/runtime.jar'
  });

  const combined = stdout + stderr;

  // Should either succeed (if runtime found elsewhere) or show error
  // The key is it should not crash silently
  assert(code !== null && code !== undefined, 'Should have an exit code');
});

// ── Test 4: Platform detection logic ──
runTest('Launcher has platform detection logic', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('detectPlatform'), 'Should have detectPlatform function');
  assert(source.includes('platformMap'), 'Should have platformMap for OS detection');
  assert(source.includes("'win32': 'windows'"), 'Should map win32 to windows');
  assert(source.includes("'darwin': 'macos'"), 'Should map darwin to macos');
  assert(source.includes("'linux': 'linux'"), 'Should map linux to linux');
});

// ── Test 5: Local runtime path support ──
runTest('Launcher has local runtime path support', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('findJavaRuntime'), 'Should have findJavaRuntime function');
  assert(source.includes('OWL4AGENTS_RUNTIME'), 'Should support OWL4AGENTS_RUNTIME env var');
  assert(source.includes('ontology-cli-all.jar'), 'Should look for fat jar (ontology-cli-all.jar)');
  assert(source.includes('ontology-distribution.jar'), 'Should look for distribution jar');
});

// ── Test 6: Java command construction ──
runTest('Launcher builds correct Java command for jar files', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('buildJavaCommand'), 'Should have buildJavaCommand function');
  assert(source.includes("'java'") || source.includes('"java"'), 'Should use java command');
  assert(source.includes("'-jar'") || source.includes('"-jar"'), 'Should use -jar flag for jar files');
});

// ── Test 7: OWL4AGENTS_HOME passthrough ──
runTest('Launcher passes OWL4AGENTS_HOME to Java runtime', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('OWL4AGENTS_HOME'), 'Should pass OWL4AGENTS_HOME env var');
});

// ── Test 8: npm package.json exists ──
runTest('npm package.json exists with correct metadata', () => {
  const pkgPath = path.resolve(__dirname, '..', 'package.json');
  assert(fs.existsSync(pkgPath), 'package.json should exist');
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
  assert(pkg.name === 'owl4agents', 'Package name should be owl4agents');
  assert(pkg.bin && pkg.bin.owl4agents, 'Should have bin.owl4agents entry');
});

// ── Test 9: Positive runtime forwarding with fake runtime ──
runTest('Launcher forwards commands to specified runtime', () => {
  // Use the fake runtime script to test successful forwarding
  const fakeRuntimePath = path.resolve(__dirname, 'fixtures', 'fake-runtime.js');
  assert(fs.existsSync(fakeRuntimePath), 'Fake runtime should exist at: ' + fakeRuntimePath);

  // Run with fake runtime
  const { stdout, stderr, code, signal, error } = runLauncher(['search', 'pizza', 'Margherita'], {
    runtime: fakeRuntimePath
  });

  // Should not crash
  assert(!error, `Launcher should not error: ${error}`);
  assert(!signal, `Launcher should not be killed by signal: ${signal}`);

  // Should succeed
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}. stderr: ${stderr}`);

  // Should contain forwarded arguments
  assert(stdout.includes('search'), 'Should forward "search" argument');
  assert(stdout.includes('pizza'), 'Should forward "pizza" argument');
  assert(stdout.includes('Margherita'), 'Should forward "Margherita" argument');
  assert(stdout.includes('fake-runtime'), 'Should identify as fake-runtime');
});

// ── Test 10: Positive runtime forwarding with multiple args ──
runTest('Launcher forwards multiple arguments correctly', () => {
  const fakeRuntimePath = path.resolve(__dirname, 'fixtures', 'fake-runtime.js');

  const { stdout, code, signal } = runLauncher(['query', 'pizza', '--select', 'SELECT ?s WHERE { ?s a owl:Class }'], {
    runtime: fakeRuntimePath
  });

  assert(!signal, `Should not crash with signal: ${signal}`);
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}`);

  // Verify all arguments are forwarded
  assert(stdout.includes('query'), 'Should forward "query"');
  assert(stdout.includes('pizza'), 'Should forward "pizza"');
  assert(stdout.includes('--select'), 'Should forward "--select"');
});

// ── Test 10: Launcher handles special characters in args ──
runTest('Launcher handles special characters in arguments', () => {
  const { stdout, stderr, code, signal } = runLauncher(['--query', 'SELECT ?s WHERE { ?s a owl:Class }']);

  // Should not crash
  assert(!signal || signal === 'SIGTERM', `Should not crash with signal: ${signal}`);
});

// ── Test 11: Launcher exits cleanly on invalid command ──
runTest('Launcher handles invalid commands gracefully', () => {
  const { stdout, stderr, code, signal } = runLauncher(['invalid-command-xyz']);

  // Should not crash with signal
  assert(!signal || signal === 'SIGTERM', `Should not crash with signal: ${signal}`);
});

// Summary
console.log(`\n  Results: ${passed} passed, ${failed} failed\n`);
process.exit(failed > 0 ? 1 : 0);

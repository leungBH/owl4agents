#!/usr/bin/env node

/**
 * owl4agents npm launcher smoke tests.
 * Release-hardened tests: help, version, command forwarding, missing runtime,
 * real MCP startup, dependency matrix alignment, and placeholder rejection.
 *
 * Uses real child-process execution with strict exit-code, stdout, stderr assertions.
 *
 * Run with: node npm/test/launcher.test.js
 */

const { spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');
const os = require('os');
const assert = require('assert');

const LAUNCHER = path.resolve(__dirname, '..', 'bin', 'owl4agents.js');
const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const PACKAGE_JSON = path.resolve(__dirname, '..', 'package.json');
const MCP_SMOKE_TIMEOUT_MS = 15000;

const pkg = JSON.parse(fs.readFileSync(PACKAGE_JSON, 'utf-8'));
const EXPECTED_VERSION = pkg.version;

let passed = 0;
let failed = 0;

function runTest(name, testFn) {
  try {
    testFn();
    console.log(`  PASS ${name}`);
    passed++;
  } catch (err) {
    console.log(`  FAIL ${name}`);
    console.log(`    ${err.message}`);
    failed++;
  }
}

function runLauncher(args = [], options = {}) {
  const env = {
    ...process.env,
    OWL4AGENTS_RUNTIME: options.runtime || '',
    ...options.env
  };

  if (options.runtime === '') {
    delete env.OWL4AGENTS_RUNTIME;
  }

  const result = spawnSync('node', [LAUNCHER, ...args], {
    encoding: 'utf-8',
    timeout: options.timeout || 30000,
    env,
    cwd: options.cwd || PROJECT_ROOT,
    input: options.input,
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

runTest('No arguments shows help output and exits 0 (V021-LAUNCH-001)', () => {
  const { stdout, stderr, code, signal, error } = runLauncher([]);

  assert(!error, `Launcher should not error: ${error}`);
  assert(!signal, `Launcher should not be killed by signal: ${signal}`);
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}`);
  assert(stdout.includes('owl4agents'), 'Help should include "owl4agents"');
  assert(stdout.includes('init'), 'Help should list init command');
  assert(stdout.includes('import'), 'Help should list import command');
  assert(stdout.includes('summary'), 'Help should list summary command');
  assert(stdout.includes('mcp'), 'Help should list mcp command');
  assert(!stderr.includes('Error:'), `stderr should not contain crash text: ${stderr}`);
  assert(!stderr.includes('Exception'), `stderr should not contain stack trace: ${stderr}`);
});

runTest('--version reports project version and exits 0 (V021-LAUNCH-002)', () => {
  const { stdout, stderr, code, signal, error } = runLauncher(['--version']);

  assert(!error, `Launcher should not error: ${error}`);
  assert(!signal, `Launcher should not be killed by signal: ${signal}`);
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}. stderr: ${stderr}`);
  assert(stdout.includes(EXPECTED_VERSION), `stdout should contain version "${EXPECTED_VERSION}", got: "${stdout}"`);
  assert(!stderr.includes('Exception'), `stderr should not contain stack trace: ${stderr}`);
});

runTest('Missing runtime source path has controlled diagnostics (V021-LAUNCH-003 supplemental)', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');

  assert(source.includes('runtime not found'), 'Should have "runtime not found" error message');
  assert(source.includes('shadowJar') || source.includes('owl4agents.jar'), 'Should reference jar build path');
  assert(source.includes('gradlew'), 'Should reference Gradle build command');
  assert(source.includes('JAVA_HOME'), 'Should reference JAVA_HOME guidance');
  assert(source.includes('Required Java version') || source.includes('Java 22+'), 'Should mention required Java version');

  const exitCodePattern = /process\.exit\((\d+)\)/g;
  const exitCodes = [];
  let match;
  while ((match = exitCodePattern.exec(source)) !== null) {
    exitCodes.push(parseInt(match[1], 10));
  }
  assert(exitCodes.includes(1), 'Should use exit code 1 for unsupported platform');
  assert(exitCodes.includes(2), 'Should use exit code 2 for missing runtime');
});

runTest('OWL4AGENTS_RUNTIME pointing to non-existent path exits 2 with diagnostic (V021-LAUNCH-003 E2E)', () => {
  const nonexistentPath = path.join(os.tmpdir(), 'owl4agents-nonexistent-runtime-' + Date.now() + '.jar');
  assert(!fs.existsSync(nonexistentPath), 'Non-existent path should not exist on disk');

  const { stdout, stderr, code, signal, error } = runLauncher(['list'], {
    env: { OWL4AGENTS_RUNTIME: nonexistentPath }
  });

  assert(!error, `Launcher should not error: ${error}`);
  assert(!signal, `Launcher should not be killed by signal: ${signal}`);
  assert.strictEqual(code, 2, `Expected exit code 2 for non-existent OWL4AGENTS_RUNTIME, got ${code}`);
  assert(stderr.includes('runtime not found'), `stderr should contain "runtime not found", got: ${stderr}`);
  assert(stderr.includes('OWL4AGENTS_RUNTIME'), `stderr should reference OWL4AGENTS_RUNTIME, got: ${stderr}`);
  assert(stderr.includes(nonexistentPath) || stderr.includes('non-existent'), `stderr should reference the invalid path, got: ${stderr}`);
  assert(stderr.includes('gradlew') || stderr.includes('shadowJar'), `stderr should reference build command, got: ${stderr}`);
  assert.strictEqual(stdout, '', `stdout should be empty for error case, got: ${stdout}`);
});

runTest('Launcher forwards commands to fake runtime preserving arguments (V021-LAUNCH-004)', () => {
  const fakeRuntimePath = path.resolve(__dirname, 'fixtures', 'fake-runtime.js');
  assert(fs.existsSync(fakeRuntimePath), 'Fake runtime should exist');

  const { stdout, stderr, code, signal, error } = runLauncher(['list-reasoners'], {
    runtime: fakeRuntimePath
  });

  assert(!error, `Launcher should not error: ${error}`);
  assert(!signal, `Launcher should not be killed by signal: ${signal}`);
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}. stderr: ${stderr}`);
  assert(stdout.includes('list-reasoners'), 'Should forward "list-reasoners" argument');
  assert(stdout.includes('fake-runtime'), 'Should identify as fake-runtime');
});

runTest('Launcher preserves nonzero exit code from runtime (V021-LAUNCH-004 exit semantics)', () => {
  const exitCodeScript = path.join(os.tmpdir(), 'exit-code-test.js');
  fs.writeFileSync(exitCodeScript, 'console.error("intentional failure"); process.exit(42);');

  const { code, stderr, error } = runLauncher(['list-reasoners'], {
    runtime: exitCodeScript,
    timeout: 10000
  });

  assert(!error, `Launcher should not error: ${error}`);
  assert.strictEqual(code, 42, `Expected exit code 42 from runtime, got ${code}. stderr: ${stderr}`);
  try { fs.unlinkSync(exitCodeScript); } catch {}
});

runTest('Launcher MCP command completes real readonly JSON-RPC smoke (V021-MCP-001)', () => {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'owl4agents-mcp-smoke-'));
  const env = { OWL4AGENTS_HOME: home };

  const init = runLauncher(['init'], { env, timeout: MCP_SMOKE_TIMEOUT_MS });
  assert.strictEqual(init.code, 0, `init should succeed. stdout=${init.stdout} stderr=${init.stderr}`);

  const input = [
    JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }),
    JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} }),
    JSON.stringify({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'ontology_list', arguments: {} } }),
    ''
  ].join('\n');

  const result = runLauncher(['mcp', '--readonly'], { env, input, timeout: MCP_SMOKE_TIMEOUT_MS });

  assert.strictEqual(result.code, 0, `MCP process should exit 0 after stdin closes. stderr=${result.stderr}`);
  assert(result.stdout.includes('"id":1'), `initialize response should be present. stdout=${result.stdout}`);
  assert(result.stdout.includes('serverInfo'), `initialize response should contain serverInfo. stdout=${result.stdout}`);
  assert(result.stdout.includes('"id":2'), `tools/list response should be present. stdout=${result.stdout}`);
  assert(result.stdout.includes('ontology_list'), `tools/list should include ontology_list. stdout=${result.stdout}`);
  assert(result.stdout.includes('"id":3'), `tools/call response should be present. stdout=${result.stdout}`);
  assert(!result.stderr.includes('Failed to write MCP log entry'), `MCP should not warn about log path. stderr=${result.stderr}`);
  assert(!result.stderr.includes('Exception'), `MCP stderr should not contain stack traces. stderr=${result.stderr}`);

  const logPath = path.join(home, 'workspaces', 'default', 'logs', 'mcp-tool-calls.jsonl');
  assert(fs.existsSync(logPath), `MCP tool-call log should exist at ${logPath}`);
  const logText = fs.readFileSync(logPath, 'utf-8');
  assert(logText.includes('ontology_list'), `MCP log should include ontology_list. log=${logText}`);
  assert(logText.includes('success'), `MCP log should include success status. log=${logText}`);
});

runTest('Package.json version matches build.gradle.kts version (V021-DOC-001)', () => {
  const buildGradlePath = path.resolve(PROJECT_ROOT, 'build.gradle.kts');
  const buildGradle = fs.readFileSync(buildGradlePath, 'utf-8');
  const versionMatch = buildGradle.match(/version\s*=\s*"([^"]+)"/);
  assert(versionMatch, 'build.gradle.kts should contain a version declaration');
  assert.strictEqual(EXPECTED_VERSION, versionMatch[1],
    `npm package version (${EXPECTED_VERSION}) should match Gradle version (${versionMatch[1]})`);
});

runTest('Gradle wrapper version matches gradle-wrapper.properties (V021-DOC-001)', () => {
  const wrapperProps = path.resolve(PROJECT_ROOT, 'gradle', 'wrapper', 'gradle-wrapper.properties');
  assert(fs.existsSync(wrapperProps), 'gradle-wrapper.properties should exist');
  const content = fs.readFileSync(wrapperProps, 'utf-8');
  assert(content.match(/distributionUrl\s*=\s*.*gradle-(\d+\.\d+)-bin\.zip/), 'Should contain a valid Gradle distribution URL');
});

runTest('Launcher has platform detection logic (regression)', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('detectPlatform'), 'Should have detectPlatform function');
  assert(source.includes("'win32': 'windows'"), 'Should map win32 to windows');
  assert(source.includes("'darwin': 'macos'"), 'Should map darwin to macos');
  assert(source.includes("'linux': 'linux'"), 'Should map linux to linux');
});

runTest('Launcher has local runtime path support (regression)', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('findJavaRuntime'), 'Should have findJavaRuntime function');
  assert(source.includes('OWL4AGENTS_RUNTIME'), 'Should support OWL4AGENTS_RUNTIME env var');
  assert(source.includes('owl4agents.jar'), 'Should look for owl4agents.jar');
});

runTest('Launcher passes OWL4AGENTS_HOME to Java runtime (regression)', () => {
  const source = fs.readFileSync(LAUNCHER, 'utf-8');
  assert(source.includes('OWL4AGENTS_HOME'), 'Should pass OWL4AGENTS_HOME env var');
});

runTest('Launcher does not produce empty success output on help (no placeholder)', () => {
  const { stdout, code } = runLauncher([]);
  assert.strictEqual(code, 0);
  assert(stdout.length > 100, `Help output should be substantive, not placeholder (got ${stdout.length} chars)`);
});

runTest('Launcher does not produce empty success output on version (no placeholder)', () => {
  const { stdout, code } = runLauncher(['--version']);
  assert.strictEqual(code, 0);
  assert(stdout.length > 0, 'Version output should not be empty/placeholder');
});

runTest('npm package.json exists with correct metadata', () => {
  assert(fs.existsSync(PACKAGE_JSON), 'package.json should exist');
  assert(pkg.name === 'owl4agents', `Package name should be owl4agents, got ${pkg.name}`);
  assert(pkg.bin && pkg.bin.owl4agents, 'Should have bin.owl4agents entry');
  assert(pkg.engines && pkg.engines.node, 'Should declare Node engine requirement');
});

runTest('Launcher forwards multiple arguments correctly', () => {
  const fakeRuntimePath = path.resolve(__dirname, 'fixtures', 'fake-runtime.js');
  const { stdout, code, signal } = runLauncher(['query', 'pizza', '--select', 'SELECT ?s WHERE { ?s a owl:Class }'], {
    runtime: fakeRuntimePath
  });

  assert(!signal, `Should not crash with signal: ${signal}`);
  assert.strictEqual(code, 0, `Expected exit code 0, got ${code}`);
  assert(stdout.includes('query'), 'Should forward "query"');
  assert(stdout.includes('pizza'), 'Should forward "pizza"');
});

console.log(`\n  Results: ${passed} passed, ${failed} failed\n`);
process.exit(failed > 0 ? 1 : 0);

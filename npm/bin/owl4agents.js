#!/usr/bin/env node

/**
 * owl4agents npm launcher - forwards command-line arguments to the Java runtime.
 * Does NOT implement ontology logic in Node.js.
 * Java owns all semantic behavior (OWL API, reasoners, Jena, Picocli, MCP).
 */

const { execSync } = require('child_process');
const path = require('path');
const os = require('os');
const fs = require('fs');

// Platform and architecture detection

function detectPlatform() {
  const platform = os.platform();
  const arch = os.arch();

  const platformMap = {
    'win32': 'windows',
    'darwin': 'macos',
    'linux': 'linux'
  };

  const archMap = {
    'x64': 'x64',
    'arm64': 'arm64',
    'aarch64': 'arm64'
  };

  const detectedPlatform = platformMap[platform];
  const detectedArch = archMap[arch];

  if (!detectedPlatform) {
    console.error(`Error: Unsupported platform '${platform}'. owl4agents supports windows, macos, and linux.`);
    process.exit(1);
  }

  return { platform: detectedPlatform, arch: detectedArch || 'x64' };
}

// Local development runtime path support

function findJavaRuntime() {
  const runtimeOverride = process.env.OWL4AGENTS_RUNTIME;
  if (runtimeOverride) {
    if (fs.existsSync(runtimeOverride)) {
      if (runtimeOverride.endsWith('.js')) {
        return { type: 'script', path: runtimeOverride };
      }
      return { type: 'jar', path: runtimeOverride };
    }
    console.error('Error: owl4agents runtime not found.');
    console.error('');
    console.error('The OWL4AGENTS_RUNTIME environment variable points to a non-existent path:');
    console.error(`  ${runtimeOverride}`);
    console.error('');
    console.error('To resolve this, try one of the following:');
    console.error('  1. Build the fat jar:    .\\gradlew.bat :modules:ontology-cli:shadowJar');
    console.error('  2. Set OWL4AGENTS_RUNTIME to point to an existing jar');
    console.error('  3. Remove OWL4AGENTS_RUNTIME to use automatic runtime discovery');
    console.error('');
    console.error('Required Java version: 22+ (as configured in Gradle toolchain)');
    process.exit(2);
  }

  const shadowJarPath = path.resolve(__dirname, '..', '..', 'modules', 'ontology-cli', 'build', 'libs', 'owl4agents.jar');
  if (fs.existsSync(shadowJarPath)) {
    return { type: 'jar', path: shadowJarPath };
  }

  const fatJarPath = path.resolve(__dirname, '..', '..', 'modules', 'ontology-cli', 'build', 'libs', 'ontology-cli-all.jar');
  if (fs.existsSync(fatJarPath)) {
    return { type: 'jar', path: fatJarPath };
  }

  const distJarPath = path.resolve(__dirname, '..', '..', 'modules', 'ontology-distribution', 'build', 'libs', 'ontology-distribution.jar');
  if (fs.existsSync(distJarPath)) {
    return { type: 'jar', path: distJarPath };
  }

  const platformRuntimePath = path.resolve(__dirname, '..', '..', 'build', 'runtime', `owl4agents-${detectPlatform().platform}-${detectPlatform().arch}`);
  if (fs.existsSync(platformRuntimePath)) {
    return { type: 'native', path: platformRuntimePath };
  }

  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaBin = os.platform() === 'win32'
      ? path.join(javaHome, 'bin', 'java.exe')
      : path.join(javaHome, 'bin', 'java');
    if (fs.existsSync(javaBin)) {
      return { type: 'classpath' };
    }
  }

  try {
    execSync('java -version', { stdio: 'pipe' });
    return { type: 'classpath' };
  } catch {
    const gradlewPath = path.resolve(__dirname, '..', '..', os.platform() === 'win32' ? 'gradlew.bat' : 'gradlew');
    if (fs.existsSync(gradlewPath)) {
      return { type: 'gradle', path: gradlewPath };
    }
    console.error('Error: owl4agents runtime not found.');
    console.error('');
    console.error('To resolve this, try one of the following:');
    console.error('  1. Build the fat jar:    .\\gradlew.bat :modules:ontology-cli:shadowJar');
    console.error('  2. Set OWL4AGENTS_RUNTIME env var to point to an existing jar');
    console.error('  3. Install Java 22+ and ensure JAVA_HOME is set or java is on PATH');
    console.error('');
    console.error('Required Java version: 22+ (as configured in Gradle toolchain)');
    process.exit(2);
  }
}

function javaExecutable() {
  const javaHome = process.env.JAVA_HOME;
  if (javaHome) {
    const javaBin = os.platform() === 'win32'
      ? path.join(javaHome, 'bin', 'java.exe')
      : path.join(javaHome, 'bin', 'java');
    if (fs.existsSync(javaBin)) {
      return javaBin;
    }
  }
  return 'java';
}

function buildJavaCommand(runtime, args) {
  const { platform: detectedPlatform } = detectPlatform();

  // On Windows, use java -cp instead of java -jar for the MCP command.
  // This avoids two problems:
  // 1. ACCESS_VIOLATION crash that java -jar produces on some Windows environments
  // 2. Recursive call if we forwarded to owl4agents-mcp.cmd which then calls npm launcher again
  // java -cp works with the shadow jar because it contains all merged classes without relocation.
  if (os.platform() === 'win32' && args[0] === 'mcp' && runtime.type === 'jar') {
    return [javaExecutable(), '-cp', runtime.path, 'org.owl4agents.cli.Owl4AgentsCli', ...args];
  }

  if (runtime.type === 'jar') {
    return [javaExecutable(), '-jar', runtime.path, ...args];
  }
  if (runtime.type === 'script') {
    return ['node', runtime.path, ...args];
  }
  if (runtime.type === 'native') {
    return [runtime.path, ...args];
  }
  if (runtime.type === 'classpath') {
    const modulesDir = path.resolve(__dirname, '..', '..', 'modules');
    const classpath = [
      path.join(modulesDir, 'ontology-cli', 'build', 'libs', '*'),
      path.join(modulesDir, 'ontology-core', 'build', 'libs', '*'),
      path.join(modulesDir, 'ontology-storage', 'build', 'libs', '*'),
      path.join(modulesDir, 'ontology-owlapi', 'build', 'libs', '*'),
      path.join(modulesDir, 'ontology-query', 'build', 'libs', '*'),
      path.join(modulesDir, 'ontology-retrieval', 'build', 'libs', '*'),
      path.join(modulesDir, 'ontology-mcp', 'build', 'libs', '*'),
    ].join(os.platform() === 'win32' ? ';' : ':');
    return [javaExecutable(), '-cp', classpath, 'org.owl4agents.cli.Owl4AgentsCli', ...args];
  }

  const gradlewPath = path.resolve(__dirname, '..', '..', detectedPlatform === 'windows' ? 'gradlew.bat' : 'gradlew');
  if (fs.existsSync(gradlewPath)) {
    return [gradlewPath, '--console=plain', ':modules:ontology-cli:run', `--args=${args.join(' ')}`];
  }
  console.error('Error: No Java runtime or Gradle wrapper found. Install Java 22+ or run `gradle build` first.');
  process.exit(1);
}

// Forward command arguments to the Java runtime

function main() {
  const args = process.argv.slice(2);

  if (args.length === 1 && (args[0] === '--version' || args[0] === '-V')) {
    const pkgPath = path.resolve(__dirname, '..', 'package.json');
    try {
      const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf-8'));
      console.log(pkg.version || '0.3.1');
    } catch {
      console.log('0.3.1');
    }
    process.exit(0);
  }

  if (args.length === 0) {
    console.log('owl4agents - Local OWL ontology reasoning and MCP server for LLM agents');
    console.log('Usage: owl4agents <command> [options]');
    console.log('');
    console.log('Commands:');
    console.log('  init       Initialize a local workspace');
    console.log('  import     Import an OWL/RDF ontology file');
    console.log('  list       List imported ontologies');
    console.log('  summary    Get ontology summary');
    console.log('  search     Search ontology entities');
    console.log('  entity     Get entity context');
    console.log('  query      Validate or execute SPARQL queries');
    console.log('  context    Generate QA context for a question');
    console.log('  mcp        Start the MCP server');
    console.log('  reason     Run reasoner tasks');
    console.log('  classify   Compute inferred class hierarchy');
    console.log('  realize    Infer individual class memberships');
    console.log('  consistency  Check ontology consistency');
    console.log('  list-reasoners List available reasoner adapters');
    console.log('  setup      Check and prepare source checkout environment');
    console.log('  smoke      Run onboarding smoke test');
    console.log('  mcp-config Generate MCP client configuration');
    // v0.3 claim verification commands
    console.log('  verify-claim   Verify a structured claim');
    console.log('  evidence       Get evidence path for a claim');
    console.log('  counterexamples Find counterexamples for a claim');
    console.log('  explain-unknown Explain unknown verdict for a claim');
    console.log('  missing-entities Detect missing entities for a claim');
    process.exit(0);
  }

  const runtime = findJavaRuntime();
  const command = buildJavaCommand(runtime, args);

  const cmdStr = command.map(arg => {
    if (arg.includes(' ') || arg.includes('=')) {
      return `"${arg}"`;
    }
    return arg;
  }).join(' ');

  try {
    execSync(cmdStr, {
      stdio: 'inherit',
      env: {
        ...process.env,
        OWL4AGENTS_HOME: process.env.OWL4AGENTS_HOME || ''
      },
      timeout: 60000
    });
    process.exit(0);
  } catch (err) {
    process.exit(err.status || 1);
  }
}

main();

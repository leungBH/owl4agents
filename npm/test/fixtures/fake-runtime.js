#!/usr/bin/env node

/**
 * Fake runtime for testing launcher forwarding.
 * Echoes back the arguments received, simulating a successful Java runtime execution.
 *
 * Usage: node fake-runtime.js <args...>
 * Exit code: 0 on success
 */

const args = process.argv.slice(2);

// Echo the arguments back as JSON
const result = {
  status: 'success',
  runtime: 'fake-runtime',
  arguments: args,
  cwd: process.cwd()
};

console.log(JSON.stringify(result, null, 2));
process.exit(0);

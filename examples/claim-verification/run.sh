#!/bin/bash
# Claim verification example runner for Linux/macOS
# Uses npm launcher as entry point

set -e

WS_HOME="claim-demo"

echo "=== Step 1: Import golden ontology ==="
node npm/bin/owl4agents.js import test/corpus/golden/v0.3-claim-verification.owl v0.3-claim-verification --workspace "$WS_HOME"

echo "=== Step 2: Verify supported claim ==="
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json --workspace "$WS_HOME" --json

echo "=== Step 3: Run reasoner ==="
node npm/bin/owl4agents.js reason v0.3-claim-verification --workspace "$WS_HOME"

echo "=== Step 4: Verify contradicted claim ==="
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-contradicted.json --workspace "$WS_HOME" --json

echo "=== Step 5: Verify unknown claim ==="
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-unknown.json --workspace "$WS_HOME" --json

echo "=== Step 6: Verify out_of_scope claim ==="
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-real-out-of-scope.json --workspace "$WS_HOME" --json

echo "=== All steps passed ==="
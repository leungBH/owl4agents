#!/bin/bash
# Biomedical grounding example runner for Linux/macOS
# Uses npm launcher as entry point

set -e

WS_HOME="bio-demo"

echo "=== Step 1: Import biomedical ontology ==="
node npm/bin/owl4agents.js import test/corpus/golden/v0.4-biomedical-grounding.owl v0.4-biomedical-grounding --workspace "$WS_HOME"

echo "=== Step 2: Search for Hypertension ==="
node npm/bin/owl4agents.js search v0.4-biomedical-grounding Hypertension --workspace "$WS_HOME"

echo "=== Step 3: Entity context for Hypertension ==="
node npm/bin/owl4agents.js entity v0.4-biomedical-grounding http://example.org/v0.4-biomedical#Hypertension --workspace "$WS_HOME"

echo "=== Step 4: Run reasoner ==="
node npm/bin/owl4agents.js reason v0.4-biomedical-grounding --workspace "$WS_HOME"

echo "=== Step 5: Verify supported claim ==="
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-supported.json --workspace "$WS_HOME" --json

echo "=== Step 6: Verify unknown claim ==="
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-unknown.json --workspace "$WS_HOME" --json

echo "=== Step 7: Verify out_of_scope claim ==="
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-out-of-scope.json --workspace "$WS_HOME" --json

echo "=== All steps passed ==="
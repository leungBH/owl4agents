# Claim Verification Example

This example demonstrates all four claim verification verdicts: **supported**, **contradicted**, **unknown**, and **out_of_scope**.

## What it demonstrates

owl4agents can verify structured claims against an OWL ontology and return a verdict with evidence:

- **Supported**: The ontology entails the claim (Dog is a subclass of Animal)
- **Contradicted**: The ontology contradicts the claim (Dog is compatible with Cat, but they are disjoint)
- **Unknown**: The ontology does not entail or contradict the claim (Goldfish → Fish has no connecting axioms)
- **Out of scope**: The claim references an entity not in the ontology (DeliveryPrice is not in the Animal ontology)

## Prerequisites

- Java 22+ installed
- Shadow jar built: `.\gradlew.bat :modules:ontology-cli:shadowJar`
- Node.js 18+ installed

## Step-by-step commands

Run from the repository root:

### 1. Import the golden ontology

```bash
node tools/npm/bin/owl4agents.js import test/corpus/golden/v0.3-claim-verification.owl v0.3-claim-verification --workspace claim-demo
```

### 2. Verify a supported claim

```bash
node tools/npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json --workspace claim-demo --json
```

Expected output snippet:

```json
{
  "verdict": "supported",
  "claimId": "claim-supported-001",
  "ontologyId": "v0.3-claim-verification",
  "evidence": [
    { "role": "supporting", "kind": "explicit_axiom" }
  ]
}
```

### 3. Run reasoner (required before contradicted verification)

```bash
node tools/npm/bin/owl4agents.js reason v0.3-claim-verification --workspace claim-demo
```

**Note:** The contradicted claim relies on explicit disjointness axioms. Reasoning is recommended for full evidence but the basic disjointness check works with explicit scope.

### 4. Verify a contradicted claim

```bash
node tools/npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-contradicted.json --workspace claim-demo --json
```

Expected output snippet:

```json
{
  "verdict": "contradicted",
  "claimId": "claim-contradicted-001",
  "evidence": [
    { "role": "counter", "kind": "counterexample" }
  ]
}
```

### 5. Verify an unknown claim

```bash
node tools/npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-unknown.json --workspace claim-demo --json
```

Expected output snippet:

```json
{
  "verdict": "unknown",
  "unknownReason": "insufficient_axioms",
  "unknownExplanation": "No axioms connect Goldfish to Fish"
}
```

### 6. Verify an out_of_scope claim

```bash
node tools/npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-real-out-of-scope.json --workspace claim-demo --json
```

Expected output snippet:

```json
{
  "verdict": "out_of_scope",
  "claimId": "claim-real-out-of-scope-001"
}
```

## Fixture attribution

- `test/corpus/golden/v0.3-claim-verification.owl` — Project-created golden ontology (Apache-2.0)
- `test/fixtures/v0.3/claim-*.json` — Project-created claim fixtures (Apache-2.0)
- `test/fixtures/v0.5/answer-claims-*.json` — v0.5 batch claim fixtures (Apache-2.0)

## v0.5 Batch Workflow

v0.5 adds batch claim verification for structured answer checking. Batch workflow commands require structured claim input (JSON with `answerId` and `claims[]` array) — they do not extract claims from free text.

```bash
# Import and reason over the Pizza ontology (used by v0.5 fixtures)
node tools/npm/bin/owl4agents.js import test/corpus/smoke/pizza.owl pizza-ontology --workspace claim-demo
node tools/npm/bin/owl4agents.js reason pizza-ontology --workspace claim-demo

# Batch verify structured answer claims
node tools/npm/bin/owl4agents.js verify-answer pizza-ontology --claims test/fixtures/v0.5/answer-claims-supported.json --workspace claim-demo

# Build evidence context for LLM agents
node tools/npm/bin/owl4agents.js evidence-context pizza-ontology --claims test/fixtures/v0.5/answer-claims-supported.json --max-context-tokens 500 --workspace claim-demo

# Review answer claims with handling guidance
node tools/npm/bin/owl4agents.js review-answer pizza-ontology --claims test/fixtures/v0.5/answer-claims-mixed.json --policy strict --workspace claim-demo --json
```

See [tools/agent-skills/](../tools/agent-skills/) for cross-agent SOP packs for claim verification, evidence-grounded answering, and ontology scope checks.

## Troubleshooting

- **Missing fixture:** Run `.\gradlew.bat :modules:ontology-cli:shadowJar` to build the runtime, then verify that `test/corpus/golden/v0.3-claim-verification.owl` exists.
- **ACCESS_VIOLATION on Windows:** Use `node tools/npm/bin/owl4agents.js` instead of `java -jar`. See root README.
- **Verdict not as expected:** Make sure you imported the ontology and ran the reasoner before checking contradicted claims.
# Claim Verification Example

This example demonstrates all four claim verification verdicts: **supported**, **contradicted**, **unknown**, and **out_of_scope**.

## What it demonstrates

owl4agents can verify structured claims against an OWL ontology and return a verdict with evidence:

- **Supported**: The ontology entails the claim (Dog is a subclass of Animal)
- **Contradicted**: The ontology contradicts the claim (Dog is disjoint with Cat)
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
node npm/bin/owl4agents.js import test/corpus/golden/v0.3-claim-verification.owl v0.3-claim-verification --workspace temp/examples/claim-verification
```

### 2. Verify a supported claim

```bash
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json --workspace temp/examples/claim-verification --json
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
node npm/bin/owl4agents.js reason v0.3-claim-verification --workspace temp/examples/claim-verification
```

**Note:** The contradicted claim relies on explicit disjointness axioms. Reasoning is recommended for full evidence but the basic disjointness check works with explicit scope.

### 4. Verify a contradicted claim

```bash
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-contradicted.json --workspace temp/examples/claim-verification --json
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
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-unknown.json --workspace temp/examples/claim-verification --json
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
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-real-out-of-scope.json --workspace temp/examples/claim-verification --json
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

## Troubleshooting

- **Missing fixture:** Run `.\gradlew.bat :modules:ontology-cli:shadowJar` to build the runtime, then verify that `test/corpus/golden/v0.3-claim-verification.owl` exists.
- **ACCESS_VIOLATION on Windows:** Use `node npm/bin/owl4agents.js` instead of `java -jar`. See root README.
- **Verdict not as expected:** Make sure you imported the ontology and ran the reasoner before checking contradicted claims.
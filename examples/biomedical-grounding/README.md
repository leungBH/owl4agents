# Biomedical Grounding Example

This example demonstrates how owl4agents can ground agent answers in a domain ontology without requiring large private corpora. It uses a small project-owned biomedical fixture.

## What it demonstrates

- **Search**: Find ontology entities by label or keyword (e.g., searching for "Hypertension")
- **Entity context**: Explore disease hierarchy, phenotypes, and organ relationships
- **Claim verification**: Verify whether a biomedical claim is supported, contradicted, unknown, or out of scope

The fixture is intentionally small (8-12 classes) so that CI can run it deterministically. Large real-world ontologies like GO, HPO, or Mondo are referenced in documentation only — they are not required for this example.

## User Questions This Addresses

| Question | owl4agents command | Expected result |
| --- | --- | --- |
| "Is Hypertension a Disease?" | `verify-claim v0.4-biomedical-grounding --claim claim-bio-supported.json` | verdict: `supported` |
| "Is Arthritis an Infectious Disease?" | `verify-claim v0.4-biomedical-grounding --claim claim-bio-unknown.json` | verdict: `unknown` |
| "Is CancerStage in this ontology?" | `verify-claim v0.4-biomedical-grounding --claim claim-bio-out-of-scope.json` | verdict: `out_of_scope` |

This example avoids claiming medical truth beyond what the fixture supports. The fixture is a simplified educational model, not a clinical reference.

## Prerequisites

- Java 22+ installed
- Shadow jar built: `.\gradlew.bat :modules:ontology-cli:shadowJar`
- Node.js 18+ installed

## Step-by-step commands

Run from the repository root:

### 1. Import the biomedical golden ontology

```bash
node npm/bin/owl4agents.js import test/corpus/golden/v0.4-biomedical-grounding.owl v0.4-biomedical-grounding --workspace bio-demo
```

### 2. Search for Hypertension

```bash
node npm/bin/owl4agents.js search v0.4-biomedical-grounding Hypertension --workspace bio-demo
```

Expected output snippet:

```json
{
  "results": [
    { "iri": "http://example.org/v0.4-biomedical#Hypertension", "label": "Hypertension", "kind": "class" }
  ]
}
```

### 3. Get entity context for Hypertension

```bash
node npm/bin/owl4agents.js entity v0.4-biomedical-grounding http://example.org/v0.4-biomedical#Hypertension --workspace bio-demo
```

Expected: shows Hypertension → CardiovascularDisease → ChronicDisease → Disease hierarchy.

### 4. Run reasoner for inferred facts

```bash
node npm/bin/owl4agents.js reason v0.4-biomedical-grounding --workspace bio-demo
```

Expected: reasoner classifies the ontology, produces inferred hierarchy and type information.

### 5. Verify supported claim — Hypertension is a Disease

```bash
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-supported.json --workspace bio-demo --json
```

Expected output snippet:

```json
{
  "verdict": "supported",
  "claimId": "bio-supported-001",
  "ontologyId": "v0.4-biomedical-grounding"
}
```

### 6. Verify unknown claim — Arthritis is an InfectiousDisease

```bash
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-unknown.json --workspace bio-demo --json
```

Expected output snippet:

```json
{
  "verdict": "unknown",
  "unknownReason": "insufficient_axioms"
}
```

Arthritis IS a ChronicDisease (subclass), but NOT an InfectiousDisease. The ontology marks InfectiousDisease as disjoint with ChronicDisease, so a reasoner would classify this as `contradicted`. Without the reasoner, it returns `unknown` because no direct axiom connects Arthritis to InfectiousDisease.

### 7. Verify out_of_scope claim — CancerStage is not in ontology

```bash
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-out-of-scope.json --workspace bio-demo --json
```

Expected output snippet:

```json
{
  "verdict": "out_of_scope",
  "claimId": "bio-out-of-scope-001"
}
```

## Fixture attribution

- `test/corpus/golden/v0.4-biomedical-grounding.owl` — Project-created golden ontology (Apache-2.0). A simplified educational biomedical model, not a clinical reference.
- `test/fixtures/v0.4/claim-bio-*.json` — Project-created claim fixtures (Apache-2.0).

## Using Large Real-World Ontologies (optional)

For production biomedical grounding with GO, HPO, Mondo, Uberon, ChEBI, or OBI:

1. Download the ontology file manually or via a download script
2. Place it in the ignored local corpus directory (e.g., `test/corpus/large/`)
3. Import it into a workspace: `node npm/bin/owl4agents.js import test/corpus/large/mondo.owl mondo --workspace <path>`
4. Run search, context, or claim verification commands

Large ontology files are NOT required for default CI. They are local-only and remain ignored by Git.

## Troubleshooting

- **Missing fixture:** Verify that `test/corpus/golden/v0.4-biomedical-grounding.owl` exists. It is committed to the repository.
- **ACCESS_VIOLATION on Windows:** Use `node npm/bin/owl4agents.js` instead of `java -jar`.
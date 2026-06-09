# v0.5 Workflow Report Assertion Specification

This document defines schema/field assertions for verifying workflow report outputs.
Reports are validated by field presence and value constraints, NOT byte-for-byte matching.

## Common Report Assertions (all verdict paths)

Every workflow report MUST contain:

| Field | Assertion |
| --- | --- |
| `answerId` | Present, matches input `answerId` |
| `ontologyId` | Present, matches the ontology used |
| `aggregateStatus` | Present, one of: `invalid_input`, `contradicted`, `insufficient_evidence`, `out_of_scope`, `partially_verified`, `verified` |
| `claimResults` | Present, non-empty array |
| `summary` | Present, contains verdict counts |

Every claim result MUST contain:

| Field | Assertion |
| --- | --- |
| `claimId` | Present, matches input claim `id` |
| `claimType` | Present, matches input claim `type` |
| `required` | Present, boolean |
| `verdict` | Present, one of: `supported`, `contradicted`, `unknown`, `out_of_scope` |
| `evidence` | Present (may be empty array) |
| `unknownReason` | Present when verdict is `unknown`, null otherwise |
| `counterexamples` | Present (may be null) |
| `missingEntities` | Present (may be null) |
| `diagnostics` | Present (may be null) |

## Path-Specific Assertions

### supported path (V05-WF-001)
- `aggregateStatus` = `verified`
- Every claim result `verdict` = `supported`
- Evidence entries contain `kind` and `summary`

### contradicted path (V05-WF-002)
- `aggregateStatus` = `contradicted`
- At least one claim result `verdict` = `contradicted`
- Contradicted claim result contains `counterexamples` or contradiction evidence

### unknown path (V05-WF-003)
- `aggregateStatus` = `insufficient_evidence`
- At least one claim result `verdict` = `unknown`
- Unknown claim result contains `unknownReason`
- No fabricated supporting evidence

### out_of_scope path (V05-WF-004)
- `aggregateStatus` = `out_of_scope`
- All claim results `verdict` = `out_of_scope`
- `scopeDiagnostic` contains out-of-scope entities

### partially_verified path (V05-WF-005)
- `aggregateStatus` = `partially_verified`
- At least one `supported` and at least one `out_of_scope` claim
- `scopeDiagnostic` present with scope warnings
- No `contradicted` or `unknown` required claims

### malformed path (V05-WF-006)
- CLI exits nonzero
- stderr contains field-level diagnostics
- stdout does NOT contain a success report

### v0.3 wrapped path (V05-WF-007)
- Batch verdict for the wrapped claim matches v0.3 single-claim verdict for the same claim
- `aggregateStatus` = `verified` (for supported claim)

## Evidence Context Assertions (V05-CTX-001)

| Field | Assertion |
| --- | --- |
| `answerId` | Present, matches input |
| `status` | Present, matches aggregate status |
| `claims` | Present, non-empty array |
| `omittedClaimCount` | Present, integer >= 0 |
| `agentInstructions` | Present, contains "Cite only evidence returned in this context." |

Each claim context entry:
- `id` present, `verdict` present, `claimText` present
- `evidence` present (may be truncated), `omittedEvidenceCount` present >= 0
- No fabricated evidence when unavailable
# Agent Claim Workflow Contract

This contract defines the v0.5 answer-level claim workflow, including batch claim schema, aggregate status rules, optional claim handling, malformed input, and report fields.

A v0.5 workflow report claiming PASS MUST conform to every rule listed below.

## Claim Batch Schema

A claim batch is a JSON object with the following required and optional fields:

| Field | Required | Type | Description |
| --- | --- | --- | --- |
| `answerId` | yes | string | Unique identifier for the answer being verified |
| `question` | no | string | The original question (for report context only) |
| `answerText` | no | string | The proposed answer text (for report context only; owl4agents does NOT extract claims from it) |
| `claims` | yes | array | Non-empty array of structured claim objects |
| `options` | no | object | Workflow options (reasoner, requireReasoning, maxEvidencePerClaim, maxContextTokens) |

Each claim object SHALL have:

| Field | Required | Type | Description |
| --- | --- | --- | --- |
| `id` | yes | string | Unique claim identifier within the batch |
| `type` | yes | string | Claim type matching v0.3 claim type names (subclass, class_compatibility, etc.) |
| `required` | no | boolean | Whether the claim is required for aggregate status. Defaults to `true` when omitted |
| subject/predicate/object | varies | object | Type-specific fields compatible with existing v0.3 claim verification |

### Batch Validation Rules

1. `claims` MUST be non-empty. An empty claims array is `invalid_input`.
2. Each claim MUST have `id` and `type`. Missing either is `invalid_input` with field-level diagnostics.
3. Free-text-only submissions (only `answerText`, no `claims`) MUST be rejected as `invalid_input`.
4. Omitted `required` MUST default to `true` — the claim is treated as required unless explicitly set to `false`.
5. v0.3-valid single claim fields SHALL be preserved without changing semantics when wrapped into a batch.

## Aggregate Status Rules

The aggregate answer status is determined from required claims only. Optional claim failures are preserved in per-claim results but do not override the aggregate status.

Priority order (highest priority wins):

| Priority | Aggregate Status | Condition |
| --- | --- | --- |
| 1 | `invalid_input` | Batch cannot be parsed or validated |
| 2 | `contradicted` | Any **required** claim has verdict `contradicted` |
| 3 | `insufficient_evidence` | Any **required** claim has verdict `unknown` AND no required claim is `contradicted` |
| 4 | `out_of_scope` | All **required** claims have verdict `out_of_scope` |
| 5 | `partially_verified` | At least one required `supported`, at least one required NOT `supported`, no required `contradicted` or `unknown` |
| 6 | `verified` | All **required** claims have verdict `supported` |

### Aggregate Status Truth Table

| Required claim mix | Aggregate status | Notes |
| --- | --- | --- |
| all supported | `verified` | All required claims pass |
| supported + unknown | `insufficient_evidence` | Unknown dominates over supported for required claims |
| supported + out_of_scope | `partially_verified` | Mix of supported and not-supported, no contradicted or unknown |
| all out_of_scope | `out_of_scope` | All required claims outside ontology scope |
| contradicted + anything | `contradicted` | Contradicted always dominates |
| contradicted + unknown | `contradicted` | Contradicted priority is higher |
| contradicted + out_of_scope | `contradicted` | Contradicted priority is higher |
| supported + contradicted | `contradicted` | Contradicted priority is higher |
| unknown + out_of_scope | `insufficient_evidence` | Unknown priority is higher than out_of_scope |
| optional contradicted, all required supported | `verified` | Optional failures do not override required successes |
| optional unknown, all required supported | `verified` | Optional failures do not override required successes |

### Optional Claim Handling

- Optional claim verdicts MUST be preserved in per-claim results.
- Optional failures MUST NOT override the aggregate status derived from required claims.
- The report MAY include a separate optional-claim failure summary.
- `required` defaults to `true` when omitted.

## Report Fields

The workflow report SHALL include:

| Field | Type | Description |
| --- | --- | --- |
| `answerId` | string | From the input batch |
| `ontologyId` | string | Ontology used for verification |
| `aggregateStatus` | string | One of the six aggregate status values |
| `claimResults` | array | Per-claim result objects |
| `scopeDiagnostic` | object or null | Scope warnings when partially_verified or out_of_scope |
| `summary` | object | Counts per verdict category |

Each claim result SHALL include:

| Field | Type | Description |
| --- | --- | --- |
| `claimId` | string | From the input claim |
| `claimType` | string | From the input claim |
| `required` | boolean | Whether this claim was required for aggregation |
| `verdict` | string | supported, contradicted, unknown, or out_of_scope |
| `evidence` | array | Evidence entries (canonical workflow schema) |
| `missingEntities` | array or null | Missing entity diagnostics |
| `unknownReason` | string or null | Reason when verdict is unknown |
| `counterexamples` | array or null | Counterexample information |
| `diagnostics` | string or null | Additional per-claim diagnostics |

Unavailable status-specific fields SHALL be present as empty arrays, nulls, or explicit unavailable diagnostics — NOT omitted silently.

## Malformed Input Rules

1. Malformed JSON MUST produce deterministic nonzero CLI exit code and structured MCP error.
2. Field-level diagnostics MUST identify the specific invalid fields.
3. Stdout MUST NOT contain a success report.
4. Free-text-only submissions MUST be rejected as `invalid_input` with explanation that structured claims are required.

## PASS Verdict Invalid Without

A v0.5 workflow report claiming PASS is invalid if:

- `unknown` or `out_of_scope` verdicts are converted into `supported`
- Evidence is fabricated when evidence is unavailable
- Optional claim failures override required claim successes in aggregate status
- Per-claim verdicts are omitted or replaced by aggregate status
- `contradicted` claims are downgraded to `unknown` or `insufficient_evidence`
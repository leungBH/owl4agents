# Example Output Schema Assertions (v0.4)

This contract defines the schema/field assertions for each v0.4 example pack. Validation MUST use these assertions rather than byte-for-byte full-output snapshots.

## Assertion Format

Each assertion specifies:
- `field`: JSON field path in the output
- `present`: whether the field must exist (true = required)
- `value`: exact expected value if deterministic, or `any-non-empty` for presence-only checks
- `snippet`: optional sample output snippet for documentation (not for exact matching)

## claim-verification Assertions

### Supported claim

| field | present | value | snippet |
| --- | --- | --- | --- |
| `claimId` | true | any-non-empty | `"claim-001"` |
| `ontologyId` | true | any-non-empty | `"v0.3-claim-verification"` |
| `claimType` | true | any-non-empty | `"subclass"` |
| `verdict` | true | `"supported"` | `"supported"` |
| `evidence` | true | any-non-empty array | `[{"evidenceId": "...", "role": "supporting"}]` |
| `reasonerName` | true/false | any-non-empty if present | `"hermit"` |
| `graphScope` | true | any-non-empty | `"inferred"` |

### Contradicted claim

| field | present | value | snippet |
| --- | --- | --- | --- |
| `claimId` | true | any-non-empty | `"claim-002"` |
| `verdict` | true | `"contradicted"` | `"contradicted"` |
| `evidence` | true | any-non-empty array with at least one counter evidence | `[{"role": "counter"}]` |

Prerequisite: `reason` command MUST be run on the ontology before this claim can return `contradicted`.

### Unknown claim

| field | present | value | snippet |
| --- | --- | --- | --- |
| `claimId` | true | any-non-empty | `"claim-003"` |
| `verdict` | true | `"unknown"` | `"unknown"` |
| `unknownReason` | true | any-non-empty | `"insufficient_axioms"` |
| `unknownExplanation` | true | any-non-empty | `"No axioms connect..."` |

Prerequisite: `reason` command MUST be run on the ontology if the unknown explanation depends on inferred facts.

### Out_of_scope claim

| field | present | value | snippet |
| --- | --- | --- | --- |
| `claimId` | true | any-non-empty | `"claim-004"` |
| `verdict` | true | `"out_of_scope"` | `"out_of_scope"` |
| `evidence` | true/false | may contain scope_statement or be minimal | |

## pizza-reasoning Assertions

### Import command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `ontologyId` | true | any-non-empty | `"pizza"` |
| `status` | true | `"success"` | `"success"` |

### Summary command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `ontologyId` | true | any-non-empty | `"pizza"` |
| `classCount` or equivalent | true | any-positive-integer | `"20"` |
| `objectPropertyCount` or equivalent | true | any-positive-integer | |
| `dataPropertyCount` or equivalent | true | any-positive-integer or zero | |

### Class context command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `classIri` or `iri` | true | any-non-empty | `"http://..."` |
| `superclasses` or `hierarchy` | true | any-non-empty array | |

### Classify command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `reasonerName` | true | any-non-empty | `"hermit"` |
| `consistent` | true | any-non-empty boolean | `"true"` |

## agent-mcp Assertions

### Initialize response

| field | present | value | snippet |
| --- | --- | --- | --- |
| `capabilities` | true | any-non-empty object | |
| `serverInfo.name` | true | contains `owl4agents` | `"owl4agents"` |
| `serverInfo.version` | true | any-non-empty | `"0.4.0"` |

### Tools/list response

| field | present | value | snippet |
| --- | --- | --- | --- |
| `tools` | true | any-non-empty array | |
| `tools[].name` | true | includes at least one claim or ontology tool | `"ontology_verify_claim"` |

### Sanitized transcript assertions

Transcript MUST NOT contain: absolute local paths, local usernames, bearer tokens, passwords, private ontology IRIs.

Transcript MUST contain: at least one tool-call request/response pair with a documented tool name.

## biomedical-grounding Assertions

### Search command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `results` or `matched` | true | any-non-empty array | `[{"iri": "...", "label": "Hypertension"}]` |

### Context command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `iri` or `classIri` | true | any-non-empty | `"http://example.org/v0.4-biomedical#Hypertension"` |
| `superclasses` or `hierarchy` | true | any-non-empty array | |

### Verify claim command

| field | present | value | snippet |
| --- | --- | --- | --- |
| `verdict` | true | any of `supported`, `contradicted`, `unknown`, `out_of_scope` | |
| `ontologyId` | true | any-non-empty | `"v0.4-biomedical-grounding"` |

## Negative Example Assertions

### Error response

| field | present | value | snippet |
| --- | --- | --- | --- |
| `status` | true | `"error"` | `"error"` |
| `error.code` | true | exact error code per contract | `"ONTOLOGY_NOT_FOUND"` |
| `error.message` | true | any-non-empty human-readable string | |

## Assertion Rules

1. Fields marked `present: true` MUST exist in the output JSON.
2. Fields marked `value: any-non-empty` MUST have a non-null, non-empty string value.
3. Fields marked `value: any-positive-integer` MUST have a numeric value >= 1.
4. Fields marked `value: any-non-empty array` MUST have a JSON array with at least one element.
5. Fields marked `value: "exact"` MUST match the exact string (case-sensitive).
6. Order of elements in arrays is NOT required to match exactly.
7. Optional metadata fields not listed here are allowed and MUST NOT cause validation failure.
8. Version numbers, timestamps, and reasoner-specific output MAY vary across runs and MUST NOT be asserted exactly unless the contract specifies an exact value.
9. Byte-for-byte full-output equality MUST NOT be required.
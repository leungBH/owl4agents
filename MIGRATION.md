# Migration Guide: Claim Verification Result Schema v1 → v2

This guide helps you migrate from the v0.8.4 (schema v1) claim verification result format to the v0.8.5 (schema v2) format.

## Summary of Changes

| Aspect | Schema v1 (v0.8.4) | Schema v2 (v0.8.5) |
| --- | --- | --- |
| `verdict` field | Always present (`SUPPORTED`/`CONTRADICTED`/`UNKNOWN`/`OUT_OF_SCOPE`) | Renamed to `semanticVerdict`; nullable when `executionStatus != COMPLETED` |
| `executionStatus` | Not present | New field: `COMPLETED`, `TIMEOUT`, `ERROR` |
| `errorCode` | Not present | New field: `Optional<ErrorCode>` (present when `executionStatus != COMPLETED`) |
| `perStageTiming` | Not present | New field: 8-stage timing breakdown |
| `schemaVersion` | `claim-verification-result/1` | `claim-verification-result/2` |
| Timeout handling | Returned `UNKNOWN` verdict | Returns `TIMEOUT` execution status with null `semanticVerdict` |
| Error handling | Returned `UNKNOWN` verdict | Returns `ERROR` execution status with null `semanticVerdict` and error code |

## New Fields in Detail

### `executionStatus`

An enum with three values:

- `COMPLETED` — The verification pipeline completed successfully. `semanticVerdict` is present.
- `TIMEOUT` — The exact consistency check exceeded the configured timeout. `semanticVerdict` is null. `errorCode` is `REASONER_TIMEOUT`.
- `ERROR` — An error occurred during verification. `semanticVerdict` is null. `errorCode` contains the specific error.

### `semanticVerdict` (replaces `verdict`)

Same enum values as before (`SUPPORTED`, `CONTRADICTED`, `UNKNOWN`, `OUT_OF_SCOPE`), but now nullable. Only present when `executionStatus == COMPLETED`.

### `errorCode`

Present only when `executionStatus != COMPLETED`. New error codes:

| Code | When Returned |
| --- | --- |
| `SOURCE_ONTOLOGY_INCONSISTENT` | Source ontology is inconsistent; verification cannot proceed |
| `REASONER_TIMEOUT` | Exact consistency check exceeded the configured timeout |
| `CLAIM_AXIOM_BUILD_FAILED` | Could not construct an OWL axiom from the structured claim |
| `CLAIM_CONSISTENCY_CHECK_FAILED` | Exact consistency check threw an unexpected exception |
| `TEMPORARY_ONTOLOGY_CREATION_FAILED` | Failed to create isolated temporary ontology |
| `TRANSIENT_REASONER_INIT_FAILED` | Failed to initialize transient reasoner session |

### `perStageTiming`

An object with 8 timing fields (all in milliseconds):

```json
{
  "axiomBuildMs": 2,
  "sourceConsistencyMs": 15,
  "entailmentMs": 8,
  "temporaryCopyMs": 1,
  "reasonerInitMs": 120,
  "consistencyCheckMs": 45,
  "explanationMs": null,
  "totalMs": 191
}
```

## Migration Steps

### 1. Update JSON parsing

**Before (v1):**
```java
Verdict verdict = result.verdict();
```

**After (v2):**
```java
Verdict verdict = result.verdict();  // nullable — check executionStatus first
ExecutionStatus status = result.executionStatus();
if (status == ExecutionStatus.COMPLETED) {
    // safe to use verdict
} else {
    ErrorCode code = result.errorCode().orElse(null);
    // handle timeout/error
}
```

### 2. Update JSON output serialization

The custom Gson TypeAdapter renders `Optional<Verdict>` as:
- `Optional.empty()` → JSON `null`
- `Optional.of(v)` → `v.jsonName()` (e.g., `"SUPPORTED"`)

### 3. Update timeout handling

**Before:** Timeout returned `UNKNOWN` verdict (indistinguishable from "claim not entailed and consistent").

**After:** Timeout returns `executionStatus=TIMEOUT`, `semanticVerdict=null`, `errorCode=REASONER_TIMEOUT`. Callers can distinguish timeout from semantic UNKNOWN.

### 4. Update CLI/MCP consumers

Both CLI (`verify-claim`, `verify-answer`, `review-answer`) and MCP (`verify` tool) now output schema v2. The `schemaVersion` field in the JSON output identifies the version:

```json
{
  "schemaVersion": "claim-verification-result/2",
  "executionStatus": "COMPLETED",
  "semanticVerdict": "CONTRADICTED",
  ...
}
```

### 5. Use the `--timeout` CLI flag

The `verify-claim` command now accepts `--timeout <duration>` (e.g., `--timeout 30s`, `--timeout 2m`, `--timeout 500ms`). Default is 60 seconds.

## JSON Schema

The formal JSON Schema is at `schemas/claim-verification-result-2.schema.json`. Validate your output against this schema to ensure compliance.

## Backward Compatibility

Schema v2 is a **breaking change**. There is no automatic fallback to v1. All consumers must be updated to handle the new fields.

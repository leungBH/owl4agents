# Evidence Context Format Contract

This contract defines the compact evidence context format for LLM agents produced by the v0.5 workflow.

## Required Context Fields

The evidence context SHALL include:

| Field | Type | Description |
| --- | --- | --- |
| `answerId` | string | From the workflow input |
| `status` | string | Aggregate status from the workflow report |
| `claims` | array | Per-claim context entries |
| `omittedClaimCount` | integer | Claims whose evidence detail was omitted entirely |
| `agentInstructions` | array | Conservative handling instructions for agents |

Each claim context entry SHALL include:

| Field | Type | Description |
| --- | --- | --- |
| `id` | string | Claim ID |
| `verdict` | string | supported, contradicted, unknown, or out_of_scope |
| `claimText` | string | Human-readable claim summary |
| `evidence` | array | Evidence items (may be truncated) |
| `omittedEvidenceCount` | integer | Evidence items omitted for this claim due to budget |

The context MUST preserve `supported`, `contradicted`, `unknown`, and `out_of_scope` distinctions — no verdict conversion.

## Canonical Evidence Entry Schema

Each evidence item in the context SHALL use:

| Field | Type | Description |
| --- | --- | --- |
| `kind` | string | Evidence kind (inferred_axiom, explicit_axiom, etc.) |
| `summary` | string | Human-readable evidence description |
| `source` | string | Where the evidence comes from |
| `reasoner` | string or null | Reasoner name if applicable |
| `provenance` | string or null | Traceable provenance reference |

Missing optional values MUST be represented explicitly as null or unavailable diagnostics — NOT omitted silently or changed across CLI vs MCP.

Note: This is the **workflow evidence entry schema**, distinct from the v0.3 single-claim evidence schema (`evidenceId`, `role`, `kind`, `value`, `source`, `graphScope`, `entities`). Both schemas SHALL coexist; the workflow schema is a projection for compact context, not a replacement for single-claim payloads.

## Deterministic Truncation Budget

1. When `maxContextTokens` is set to a positive integer `n`, the context builder SHALL use a deterministic approximate character budget of `4 * n` characters for evidence item summaries and agent-facing text.
2. Evidence entries SHALL be retained in deterministic **source order** until the budget is exhausted.
3. Overflow entries SHALL be dropped and counted:
   - Per-claim: `omittedEvidenceCount`
   - Top-level: `omittedClaimCount`
4. All claim IDs and verdicts MUST remain visible under truncation unless the request itself is invalid.
5. Invalid budget (zero, negative, non-numeric) MUST produce nonzero CLI exit or structured MCP error with field diagnostics.

## Contradicted Claims Prominence

When truncation is necessary:
- Contradicted claim summaries MUST appear before lower-priority supported evidence.
- The evidence context MUST mark the answer as contradicted when any required claim is contradicted.

## Unknown and Out-of-Scope Explicitness

- When a claim is `unknown`, the context MUST include the reason category.
- When a claim is `out_of_scope`, the context MUST include the scope diagnostic.
- The context MUST instruct agents NOT to cite unknown or out_of_scope claims as supported.

## No Fabricated Evidence

1. When no evidence is available for a claim, the context MUST state that evidence is unavailable or insufficient.
2. The context MUST NOT fabricate axioms, triples, reasoner output, or citations.
3. The `agentInstructions` array MUST include: "Cite only evidence returned in this context."

## PASS Verdict Invalid Without

An evidence context claiming validity is invalid if:

- Evidence is fabricated when no real evidence exists
- Unknown or out_of_scope verdicts are converted into `supported`
- Truncation omits verdicts without counting them explicitly
- The canonical evidence entry schema differs across CLI and MCP for the same input
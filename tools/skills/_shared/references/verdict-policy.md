# Verdict Policy

Shared verdict interpretation policy for all owl4agents agent skills.

This document defines how agents should interpret claim verification verdicts. All skills reference this policy rather than duplicating divergent verdict definitions.

## Verdicts

| Verdict | Meaning | Agent Guidance |
|---------|---------|----------------|
| `ENTAILED` | The claim is fully supported by ontology evidence and reasoning. | Present as verified fact. Cite supporting evidence. |
| `NOT_ENTAILED` | The claim is contradicted by the ontology. | Do not present as fact. Report the contradiction and counterexamples. |
| `UNKNOWN` | Insufficient evidence to confirm or contradict. | State that verification is inconclusive. Do not guess or fabricate. |

## Aggregate Answer Status (v0.5 batch workflow)

The aggregate status for a batch of claims follows this priority order:

1. **`invalid_input`** — Batch validation failed (malformed JSON, missing fields, empty claims). No verification was attempted.
2. **`contradicted`** — At least one required claim received `NOT_ENTAILED`. The answer must be rejected under strict policy.
3. **`insufficient_evidence`** — At least one required claim received `UNKNOWN`. The answer cannot be confirmed; state limitations clearly.
4. **`out_of_scope`** — All required claims reference entities outside the ontology. No claims can be verified.
5. **`partially_verified`** — All required claims are supported (`ENTAILED`) but some reference entities outside the ontology scope. Present supported claims as verified; mark out-of-scope claims as unverified.
6. **`verified`** — All required claims are `ENTAILED`. Present as verified.

### Optional claims and aggregate status

Optional claims (`required: false`) do not override the aggregate status. An optional contradicted claim does not change a `verified` aggregate to `contradicted`. Optional claims are informational only.

### `UNKNOWN` verdict and `unknownReason`

When a claim receives `UNKNOWN`, the `unknownReason` field explains why:
- `insufficient_axioms` — The ontology does not contain enough axioms to determine entailment.
- `missing_entity` — A referenced entity does not exist in the ontology.
- `ambiguous_entity` — The referenced entity matches multiple ontology entities.
- `reasoning_timeout` — The reasoner did not complete within the allowed time.
- `unsupported_claim_type` — The claim type is not supported by the current verification implementation.

## Review Policies

Three review policies govern how agents handle verification results:

- **strict** (default): Do not present any claim as fact unless it is supported by ontology evidence. Contradicted answers must be rejected.
- **conservative**: Prefer caution. Only cite explicitly verified claims. Verify optional claims independently before citing.
- **report-only**: Provide the factual report without judgment. The agent decides how to use the evidence.

Unsupported policy values are rejected deterministically with an error message listing valid options.
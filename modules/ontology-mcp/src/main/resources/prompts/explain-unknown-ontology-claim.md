# explain-unknown-ontology-claim prompt template

## Purpose
Review an answer by verifying claims and building evidence context with policy-dependent handling guidance. Returns the same factual report as `ontology_verify_claims_batch` plus policy-dependent agent-facing instructions.

## When to Use
Use this tool when an agent needs both the factual verification report AND explicit guidance on how to present the results to users. This is the recommended single-call workflow for answer review.

## Policies
- `strict` (default): Do not present any claim as fact unless it is supported by ontology evidence. Contradicted answers must be rejected.
- `conservative`: Prefer caution. Only cite explicitly verified claims. Verify optional claims independently before citing.
- `report-only`: Provide the factual report without judgment. The agent decides how to use the evidence.

Unsupported policy values (anything other than `strict`, `conservative`, or `report-only`) are rejected deterministically.

## Conservative Verdict Policy
- Do not fabricate claims, evidence, IRIs, or citations.
- Under `strict` policy, a contradicted required claim means the entire answer must be rejected.
- Under `conservative` policy, present only supported claims and state limitations for unknown/out-of-scope claims.
- Under `report-only` policy, the agent receives the raw data and decides independently.

## Structured Claim Guidance
Same as `ontology_verify_claims_batch`: each claim must have `id`, `type`, and structured `subject`/`predicate`/`object`.

## Example
```json
{
  "ontology_id": "pizza-ontology",
  "claims": {
    "answerId": "answer-001",
    "claims": [
      {
        "id": "c1",
        "type": "subclass",
        "required": true,
        "subject": { "kind": "class", "iri": "http://example.org/Pizza" },
        "predicate": "subClassOf",
        "object": { "kind": "class", "iri": "http://example.org/Food" }
      }
    ]
  },
  "max_context_tokens": 500,
  "policy": "strict"
}
```
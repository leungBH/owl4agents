# ground-answer-with-evidence prompt template

## Purpose
Build compact evidence context from a claims batch for LLM agent prompts. Takes the same claims batch as `ontology_verify_claims_batch` and produces a budget-aware context suitable for injection into agent instructions.

## When to Use
Use this tool after verifying claims with `ontology_verify_claims_batch` when an agent needs a concise, token-budget-aware evidence summary to ground its response. This is preferred over the full verification report when context window space is limited.

## Budget Behavior
- `max_context_tokens` controls the output size. The character budget is `4 * max_context_tokens`.
- When `max_context_tokens` is 0 or negative, no truncation occurs.
- Under truncation, all claim IDs and verdicts remain visible. Evidence entries are omitted from the end, with `omittedEvidenceCount` tracking what was dropped.
- The tool never fabricates evidence. If no evidence is available, the evidence list is empty.

## Agent Instructions
The output includes mandatory `agentInstructions`:
- "Cite only evidence returned in this context."
- Status-dependent instructions (e.g., "Do not present any claim as fact unless supported by ontology evidence.")

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
        "subject": { "kind": "class", "iri": "http://example.org/Pizza" },
        "predicate": "subClassOf",
        "object": { "kind": "class", "iri": "http://example.org/Food" }
      }
    ]
  },
  "max_context_tokens": 500
}
```
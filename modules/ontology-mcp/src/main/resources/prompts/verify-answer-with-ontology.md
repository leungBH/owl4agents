# verify-answer-with-ontology prompt template

## Purpose
Verify a batch of structured claims against an ontology and return an answer verification report with aggregate status.

## When to Use
Use this tool when an LLM agent needs to verify multiple claims about an ontology at once. This is the batch version of `ontology_verify_claim` (v0.3 single-claim tool).

## Structured Claim Guidance
Each claim in the batch must have:
- `id`: unique identifier for the claim within the batch
- `type`: one of `subclass`, `class_assertion`, `object_property`, `data_property`, `ontology_consistency`, `equivalent_class`, `disjoint_classes`, `same_individual`, `different_individuals`, `property_characteristic`, `datatype_constraint`
- `required`: boolean (defaults to `true` if omitted). Required claims determine aggregate status; optional claims are informational.
- `subject`: entity with `kind` (class, individual, property) and `iri`
- `predicate`: property IRI or axiom type name
- `object`: entity with `kind` and `iri` (optional for consistency/type checks)

## Aggregate Status Priority
The aggregate status follows this priority order:
1. `invalid_input` — batch validation failed
2. `contradicted` — at least one required claim is contradicted
3. `insufficient_evidence` — at least one required claim has unknown verdict
4. `out_of_scope` — all required claims reference entities outside the ontology
5. `partially_verified` — required claims are supported but some are out_of_scope
6. `verified` — all required claims are supported

Optional claim failures do NOT override the aggregate status derived from required claims.

## Conservative Verdict Policy
- Do not fabricate claims, evidence, IRIs, or citations.
- Do not present any claim as fact unless it is supported by ontology evidence.
- If a claim receives `unknown` verdict, state that evidence is insufficient rather than guessing.
- If entities are out of scope, clearly state that verification is limited to the ontology's domain.

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
      },
      {
        "id": "c2",
        "type": "subclass",
        "required": false,
        "subject": { "kind": "class", "iri": "http://example.org/Margherita" },
        "predicate": "subClassOf",
        "object": { "kind": "class", "iri": "http://example.org/Pizza" }
      }
    ]
  },
  "options": {
    "reasoner": "auto",
    "requireReasoning": true
  }
}
```
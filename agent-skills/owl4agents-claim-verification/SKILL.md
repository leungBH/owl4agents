# owl4agents Claim Verification Skill

Standard operating procedure for preparing and verifying structured claims against an ontology using the owl4agents v0.5 batch workflow.

## Purpose

This skill enables LLM agents to decompose an answer or statement into structured claims, verify those claims against an ontology, and interpret the verification results using the shared verdict policy.

## Prerequisites

- An ontology imported into owl4agents (use `owl4agents import` or `ontology_list` to check available ontologies)
- A clear understanding of the [verdict-policy.md](../_shared/references/verdict-policy.md) for interpreting results
- Familiarity with the [claim-batch-schema.md](../_shared/references/claim-batch-schema.md) for preparing claims

## Standard Operating Procedure

### Step 1: Check Entity Scope

Before preparing claims, verify that the entities you want to reference exist in the ontology:

1. Use `ontology_search_entities` to find entity IRIs for terms in your answer.
2. Use `ontology_detect_missing_entities` to identify entities that are missing, ambiguous, or out-of-scope.
3. See the [owl4agents-ontology-scope-check skill](../owl4agents-ontology-scope-check/SKILL.md) for the full scope-checking SOP.

If entities are missing, follow the [refusal-scope-policy.md](../_shared/references/refusal-scope-policy.md) — do not fabricate IRIs or claim entities exist when they don't.

### Step 2: Prepare Structured Claims

Decompose your answer into individual claims following the [claim-batch-schema.md](../_shared/references/claim-batch-schema.md):

1. Identify each factual statement in the answer.
2. For each statement, create a claim object with:
   - `id`: a unique identifier (e.g., `c1`, `c2`)
   - `type`: the appropriate claim type (subclass, class_assertion, object_property, etc.)
   - `required`: `true` for claims that must be verified for the answer to be valid; `false` for supplementary claims
   - `subject`: entity with `kind` and `iri` (use IRIs confirmed in Step 1)
   - `predicate`: property IRI or axiom type name
   - `object`: entity with `kind` and `iri` (if applicable)

3. Wrap all claims in a batch object:
```json
{
  "answerId": "unique-answer-id",
  "claims": [
    { "id": "c1", "type": "subclass", "required": true, ... },
    { "id": "c2", "type": "class_assertion", "required": false, ... }
  ]
}
```

### Step 3: Verify Claims

Use `ontology_verify_claims_batch` to verify the batch:

**MCP tool:**
```json
{
  "ontology_id": "pizza-ontology",
  "claims": { "answerId": "answer-001", "claims": [...] },
  "options": { "reasoner": "auto", "requireReasoning": true }
}
```

**CLI command:**
```bash
owl4agents verify-answer pizza-ontology --claims test/fixtures/v0.5/answer-claims-supported.json --json
```

### Step 4: Interpret Results

Read the verification report:
- `aggregateStatus`: the overall answer status (see [verdict-policy.md](../_shared/references/verdict-policy.md))
- `claimResults`: per-claim verdict, evidence, and diagnostics
- `summary`: counts per verdict category

For each claim:
- `ENTAILED` → claim is supported by ontology evidence
- `NOT_ENTAILED` → claim is contradicted by the ontology
- `UNKNOWN` → evidence is insufficient (see `unknownReason` for details)

### Step 5: Present Results

Follow the [evidence-citation-policy.md](../_shared/references/evidence-citation-policy.md):
- Cite only evidence returned by the verification workflow
- Do not fabricate evidence, IRIs, or citations
- State limitations clearly for unknown or out-of-scope claims

## Example

Using the `pizza-ontology` fixture:

```json
{
  "ontology_id": "pizza-ontology",
  "claims": {
    "answerId": "pizza-classification-answer",
    "claims": [
      {
        "id": "c1",
        "type": "subclass",
        "required": true,
        "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
        "predicate": "subClassOf",
        "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" }
      }
    ]
  }
}
```

## Important Notes

- v0.5 does not extract claims from free text. You must prepare structured claims manually.
- The `required` field defaults to `true`. Mark optional claims explicitly with `required: false`.
- Do not submit free-text claims — they will be rejected as invalid input.
- Use the [answer-review-sop.md](../_shared/references/answer-review-sop.md) for the combined verification+context workflow.
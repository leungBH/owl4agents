# owl4agents Ontology Scope Check Skill

Standard operating procedure for checking entity existence and ontology scope before claim verification.

## Purpose

This skill enables LLM agents to verify that entity references exist within an ontology's scope before submitting claims for verification. This prevents out-of-scope errors and ensures claims reference real entities.

## Prerequisites

- An ontology imported into owl4agents (use `owl4agents import` or `ontology_list` to check available ontologies)
- Familiarity with the [refusal-scope-policy.md](../_shared/references/refusal-scope-policy.md) for handling scope limitations

## Standard Operating Procedure

### Step 1: List Available Ontologies

Check what ontologies are available in your workspace:

**MCP tool:**
```json
{ "ontology_id": "pizza-ontology" }
```

**CLI command:**
```bash
owl4agents list
```

### Step 2: Check Ontology Scope

Before preparing claims, understand what the ontology covers:

**MCP tool:**
```json
{ "ontology_id": "pizza-ontology" }
```

**CLI command:**
```bash
owl4agents scope pizza-ontology
```

This returns:
- `coveredDomains`: subject areas the ontology covers
- `knownGaps`: areas the ontology explicitly does not cover
- `profileLimitations`: OWL profile constraints
- `unsupportedFeatureTypes`: features the ontology does not support

### Step 3: Search for Entity IRIs

For each entity you want to reference in a claim, search the ontology:

**MCP tool:**
```json
{ "ontology_id": "pizza-ontology", "query": "Pizza" }
```

**CLI command:**
```bash
owl4agents search pizza-ontology --query Pizza
```

Use the exact IRI returned by the search — not a guessed or fabricated IRI. If the search returns no results, the entity is likely out of scope.

### Step 4: Detect Missing Entities

Before submitting a full claim batch, use `ontology_detect_missing_entities` to identify entities that are missing, ambiguous, or out-of-scope:

**MCP tool:**
```json
{
  "ontology_id": "pizza-ontology",
  "claim": {
    "claimId": "scope-check",
    "type": "SUBCLASS",
    "ontologyId": "pizza-ontology",
    "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
    "predicate": "subClassOf",
    "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" }
  }
}
```

This returns:
- `matched`: entities that exist in the ontology
- `ambiguous`: entities that match multiple ontology entries
- `missing`: entities not found in the ontology
- `outOfScope`: entities that exist but are outside the ontology's domain

### Step 5: Handle Scope Issues

Based on the results from Step 4:

1. **All matched**: Proceed with claim verification using the confirmed IRIs.
2. **Ambiguous entities**: Resolve the ambiguity by choosing the correct IRI from the search results. Use `ontology_get_entity_context` to examine each candidate.
3. **Missing entities**: Follow the [refusal-scope-policy.md](../_shared/references/refusal-scope-policy.md) — state that the entity does not exist in the ontology and cannot be verified. Do not fabricate IRIs.
4. **Out-of-scope entities**: State that the entity exists but is outside the ontology's verification scope. Do not make claims about out-of-scope entities.

### Step 6: Proceed to Verification

Once all entities are confirmed, proceed with the [owl4agents-claim-verification skill](../owl4agents-claim-verification/SKILL.md) for structured claim preparation and verification.

## Example

Checking scope before verifying claims about `Dog` subclass of `Animal`:

```bash
# Step 2: Check ontology scope
owl4agents scope pizza-ontology

# Step 3: Search for entity IRIs
owl4agents search pizza-ontology --query Dog

# Step 4: Detect missing entities
owl4agents missing-entities pizza-ontology --claim '{"claimId":"scope-check","type":"SUBCLASS","ontologyId":"pizza-ontology","subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}}'
```

## Important Notes

- Always confirm entity existence before referencing them in claims. This prevents `out_of_scope` and `missing_entity` verdicts.
- Do not fabricate entity IRIs. Use only IRIs returned by `ontology_search_entities` or `ontology_get_entity_context`.
- If an entity is ambiguous, use `ontology_get_entity_context` to examine each candidate and select the correct one.
- The scope check should be performed before claim preparation, not after verification fails.
- See [verdict-policy.md](../_shared/references/verdict-policy.md) for how scope issues affect aggregate status.
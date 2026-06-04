# Claim Verification Contracts (v0.3)

## Principle

v0.3 claim verification provides five readonly operations that accept structured claims and return verdicts, evidence, counterexamples, unknown explanations, and missing-entity detections. All operations route through shared `ClaimVerificationService` and `EvidenceGroundingService`.

## Supported Claim Types

| ClaimType | JSON name | Subject kind | Predicate | Object kind |
| --- | --- | --- | --- | --- |
| SUBCLASS | `subclass` | class | `subClassOf` | class |
| EQUIVALENT_CLASSES | `equivalent_classes` | class | `equivalentTo` | class |
| DISJOINT_CLASSES | `disjoint_classes` | class | `disjointWith` | class |
| CLASS_COMPATIBILITY | `class_compatibility` | class | `compatibleWith` | class |
| INDIVIDUAL_MEMBERSHIP | `individual_membership` | individual | `typeOf` | class |
| OBJECT_PROPERTY_ASSERTION | `object_property_assertion` | individual | `relation` | individual |
| DATA_PROPERTY_ASSERTION | `data_property_assertion` | individual | `hasValue` | literal |
| OBJECT_PROPERTY_DOMAIN | `object_property_domain` | object_property | `domain` | class |
| OBJECT_PROPERTY_RANGE | `object_property_range` | object_property | `range` | class |
| DATA_PROPERTY_DOMAIN | `data_property_domain` | data_property | `domain` | class |
| DATA_PROPERTY_RANGE | `data_property_range` | data_property | `range` | datatype |
| LITERAL_VALIDITY | `literal_validity` | literal | `conformsTo` | datatype |
| ONTOLOGY_CONSISTENCY | `ontology_consistency` | ontology | `isConsistent` | — |
| ONTOLOGY_SCOPE | `ontology_scope` | entity | `inScopeOf` | ontology |

## Verdict Order

Verdicts are resolved in strict order: `out_of_scope` → `supported` → `contradicted` → `unknown`. Lack of entailment alone MUST yield `unknown`, never `contradicted`.

## Contract: ontology_verify_claim

**Input schema:**

```json
{
  "claimId": "string — required, unique identifier",
  "type": "string — required, one of supported claim type JSON names",
  "ontologyId": "string — required, ontology ID in workspace catalog",
  "subject": { "kind": "string — required, OWL entity type", "iri": "string — required, full IRI" },
  "predicate": "string — required",
  "object": { "kind": "string — required, OWL entity type", "iri": "string — required, full IRI" },
  "reasoner": "string — optional, reasoner name",
  "graphScope": "string — optional, explicit|inferred|union",
  "options": { "includeEvidence": "boolean — optional, default true", "maxEvidence": "integer — optional" }
}
```

**Success response:**

```json
{
  "claimId": "string",
  "ontologyId": "string",
  "claimType": "string",
  "verdict": "out_of_scope | supported | contradicted | unknown",
  "evidence": [ { "evidenceId": "...", "role": "supporting|counter", "kind": "...", "value": "...", "source": "...", "entities": [...] } ],
  "unknownReason": "string — null unless verdict is unknown",
  "unknownExplanation": "string — null unless verdict is unknown",
  "reasonerName": "string — null if no reasoner used",
  "graphScope": "string",
  "truncated": "boolean",
  "totalEvidenceAvailable": "integer"
}
```

**Error codes:**

| Code | When |
| --- | --- |
| `INVALID_CLAIM_SCHEMA` | Missing required fields or invalid claim structure |
| `UNSUPPORTED_CLAIM_TYPE` | Claim type not in supported v0.3 types |
| `ONTOLOGY_NOT_FOUND` | Ontology ID not in workspace catalog |
| `ENTITY_NOT_FOUND` | Subject or object entity IRI not found in ontology |
| `REASONING_NOT_RUN` | Claim requires inferred scope but no reasoner has been run |
| `REASONER_NOT_AVAILABLE` | Requested reasoner not available |
| `PROFILE_NOT_SUPPORTED` | Ontology profile does not support required reasoning |
| `INVALID_AXIOM_PARAMETERS` | Entity kinds do not match claim type requirements |

## Contract: ontology_get_evidence_path

**Input:** Same claim schema as `ontology_verify_claim`.

**Success response:**

```json
{
  "claimId": "string",
  "ontologyId": "string",
  "items": [
    {
      "evidenceId": "string",
      "role": "supporting | counter",
      "kind": "explicit_axiom | inferred_axiom | explicit_triple | inferred_triple | reasoning_report | scope_statement | literal_validation | counterexample",
      "value": "string — axiom or triple text",
      "source": "string — IRI or axiom type",
      "reasoner": "string — null for explicit evidence",
      "graphScope": "string",
      "entities": [ "string — entity IRIs" ],
      "confidence": "entailed | explicit | inferred"
    }
  ],
  "reasonerName": "string",
  "graphScope": "string",
  "truncated": "boolean",
  "totalAvailable": "integer"
}
```

**Error codes:** Same as `ontology_verify_claim`.

## Contract: ontology_find_counterexamples

**Input:** Same claim schema. Only meaningful for `contradicted` verdict claims.

**Success response:**

```json
{
  "claimId": "string",
  "ontologyId": "string",
  "counterexamples": [
    {
      "evidenceId": "string",
      "kind": "counterexample",
      "value": "string — contradicting axiom text",
      "source": "string — axiom type",
      "entities": [ "string" ],
      "confidence": "entailed | explicit | inferred"
    }
  ],
  "totalAvailable": "integer",
  "truncated": "boolean"
}
```

**Error codes:** Same as `ontology_verify_claim`, plus `EVIDENCE_UNAVAILABLE` when no counterexamples exist for a non-contradicted claim.

## Contract: ontology_explain_unknown

**Input:** Same claim schema. Only meaningful for `unknown` verdict claims.

**Success response:**

```json
{
  "claimId": "string",
  "ontologyId": "string",
  "reason": "insufficient_axioms | missing_reasoning | unsupported_profile | unsupported_claim_type | ambiguous_entity | missing_entity | scope_unavailable | evidence_unavailable",
  "explanation": "string — human-readable explanation",
  "relevantEntities": [ "string — entity IRIs relevant to the unknown" ],
  "suggestedAction": "string — suggested resolution action"
}
```

**Error codes:** Same as `ontology_verify_claim`, plus `EVIDENCE_UNAVAILABLE` when the claim verdict is not `unknown`.

## Contract: ontology_detect_missing_entities

**Input:**

```json
{
  "ontologyId": "string — required",
  "terms": [ "string — entity IRIs or labels to check" ]
}
```

**Success response:**

```json
{
  "ontologyId": "string",
  "matched": [ { "iri": "string", "kind": "string", "label": "string" } ],
  "ambiguous": [ { "iri": "string", "kind": "string", "label": "string", "alternatives": [ "string" ] } ],
  "missing": [ { "term": "string", "suggestion": "string" } ],
  "outOfScope": [ { "term": "string", "reason": "string" } ]
}
```

**Error codes:**

| Code | When |
| --- | --- |
| `ONTOLOGY_NOT_FOUND` | Ontology ID not in workspace catalog |
| `ENTITY_NOT_FOUND` | All terms resolve to nothing |

## Evidence Kinds

| Kind | JSON name | Description |
| --- | --- | --- |
| EXPLICIT_AXIOM | `explicit_axiom` | Axiom directly present in the ontology file |
| INFERRED_AXIOM | `inferred_axiom` | Axiom derived by a reasoner |
| EXPLICIT_TRIPLE | `explicit_triple` | RDF triple directly present in the ontology |
| INFERRED_TRIPLE | `inferred_triple` | RDF triple derived by inference |
| REASONING_REPORT | `reasoning_report` | Reasoner output summary |
| SCOPE_STATEMENT | `scope_statement` | Ontology scope declaration |
| LITERAL_VALIDATION | `literal_validation` | Datatype constraint validation result |
| COUNTEREXAMPLE | `counterexample` | Axiom that contradicts the claim |

## Confidence Levels

| Level | When |
| --- | --- |
| `entailed` | Fact is logically entailed by the ontology |
| `explicit` | Fact is directly stated in the ontology |
| `inferred` | Fact is derived by a reasoner |

## Negative Tests

| Scenario | Input defect | Expected error code | Expected behavior |
| --- | --- | --- | --- |
| Malformed claim schema | Missing `claimId`, `type`, `ontologyId`, `subject`, `predicate`, or `object` | `INVALID_CLAIM_SCHEMA` | Error result, no verification attempted |
| Unsupported claim type | `type` value not in supported v0.3 types (e.g., `verify_answer`, `shacl_constraint`) | `UNSUPPORTED_CLAIM_TYPE` | Error result, no verification attempted |
| Unknown ontology ID | `ontologyId` not in workspace catalog | `ONTOLOGY_NOT_FOUND` | Error result, no ontology loaded |
| Missing entity IRI | `subject.iri` or `object.iri` not found in ontology | `ENTITY_NOT_FOUND` | Error result, no verification attempted |
| Unsupported reasoner | `reasoner` value not available | `REASONER_NOT_AVAILABLE` | Error result, no reasoning attempted |
| Reasoning not run | `graphScope: "inferred"` with no prior reasoner run | `REASONING_NOT_RUN` | Error result, advises running reasoner first |
| Wrong entity kind | `subject.kind` does not match claim type requirements (e.g., class for individual_membership) | `INVALID_AXIOM_PARAMETERS` | Error result, schema mismatch reported |
| Evidence unavailable for non-contradicted claim | `ontology_find_counterexamples` called with `supported` or `unknown` verdict claim | `EVIDENCE_UNAVAILABLE` | Error result, no counterexamples produced |
| Evidence unavailable for non-unknown claim | `ontology_explain_unknown` called with `supported` or `contradicted` verdict claim | `EVIDENCE_UNAVAILABLE` | Error result, no explanation produced |

### Negative Test Fixture Files

| Fixture | Path | Purpose |
| --- | --- | --- |
| Malformed claim (missing fields) | `test/fixtures/v0.3/claim-malformed.json` | Missing required fields → `INVALID_CLAIM_SCHEMA` |
| Unsupported claim type | `test/fixtures/v0.3/claim-unsupported-type.json` | `type: verify_answer` → `UNSUPPORTED_CLAIM_TYPE` |
| Unknown ontology | `test/fixtures/v0.3/claim-unknown-ontology.json` | `ontologyId: nonexistent` → `ONTOLOGY_NOT_FOUND` |
| Real out-of-scope entity | `test/fixtures/v0.3/claim-real-out-of-scope.json` | `subject.iri: DeliveryPrice` (not in ontology) → `out_of_scope` verdict |
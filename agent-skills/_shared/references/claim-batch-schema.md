# Claim Batch Input Schema

Reference document for v0.5 claim batch input structure.

## Top-Level Fields

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `answerId` | yes | string | Unique identifier for the answer being verified |
| `question` | no | string | The original question (for context, not verified) |
| `answerText` | no | string | The original answer text (for context, not verified) |
| `claims` | yes | array | List of structured claims to verify |
| `options` | no | object | Workflow options (reasoner, requireReasoning, maxEvidencePerClaim, maxContextTokens) |

## Claim Fields

Each claim in the `claims` array must include:

| Field | Required | Type | Default | Description |
|-------|----------|------|---------|-------------|
| `id` | yes | string | — | Unique identifier for the claim within the batch |
| `type` | yes | string | — | Claim type (see supported types below) |
| `required` | no | boolean | `true` | Whether the claim is required for the answer to be valid |
| `subject` | yes* | object | — | Entity with `kind` and `iri` |
| `predicate` | depends | string | — | Property IRI or axiom type name |
| `object` | depends | object | — | Entity with `kind` and `iri` |
| `reasoner` | no | string | — | Reasoner override (auto, hermit, elk, openllet) |
| `graphScope` | no | string | — | Graph scope (explicit, inferred, union) |
| `options` | no | object | — | Per-claim workflow options |

\* `subject` and `object` are not required for `ontology_consistency` claims.

## Supported Claim Types

| Type | Description | Required Fields |
|------|-------------|-----------------|
| `subclass` | Class A is a subclass of Class B | subject, predicate, object |
| `class_assertion` | Individual is an instance of Class | subject, predicate, object |
| `object_property` | Subject has object property relation to Object | subject, predicate, object |
| `data_property` | Subject has data property with value | subject, predicate, object |
| `ontology_consistency` | The ontology is logically consistent | ontology_id only |
| `equivalent_class` | Two classes are equivalent | subject, object |
| `disjoint_classes` | Two classes are disjoint | subject, object |
| `same_individual` | Two individuals are the same (owl:sameAs) | subject, object |
| `different_individuals` | Two individuals are different (owl:differentFrom) | subject, object |
| `property_characteristic` | A property has a characteristic (functional, transitive, etc.) | subject, predicate |
| `datatype_constraint` | A datatype has a constraint (min, max, pattern, enumeration) | subject, predicate |

## Entity Structure

Each entity reference has:

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `kind` | yes | string | Entity type: `class`, `individual`, `object_property`, `data_property`, `datatype` |
| `iri` | yes | string | Full IRI of the entity (e.g., `http://example.org/Pizza`) |

## Important Notes

- Claims without `id` or `type` are rejected as invalid input.
- Free-text claims (strings without structure) are rejected as invalid input.
- Empty `claims` arrays are rejected as invalid input.
- The `required` field defaults to `true` when omitted.
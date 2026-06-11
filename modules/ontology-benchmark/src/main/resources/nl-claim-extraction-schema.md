# NL-to-Claim Extraction Schema

## Purpose

This schema defines rules for converting natural-language (NL) questions into structured claims
that owl4agents can verify. NL-to-claim extraction is an **optional agent-side pre-processing
step** — the owl4agents pipeline does NOT require it as a mandatory stage. Claim decomposition
quality depends on the agent or LLM, not owl4agents internals.

## Required Fields for Each Claim

Every decomposed claim must include:

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique claim identifier (e.g., "pizza-001-c1") |
| `type` | Yes | One of the 14 claim types (see below) |
| `required` | Yes | Boolean; if true, claim must verify for overall question success |
| `subject.kind` | Yes | OWL entity kind: class, object_property, data_property, individual, datatype |
| `subject.iri` | Yes | Full IRI of the subject entity |
| `predicate` | Yes | Relationship predicate (e.g., subClassOf, disjointWith, domain, range) |
| `object.kind` | Yes | OWL entity kind of the object |
| `object.iri` | Yes | Full IRI of the object entity |

## Mapping Rules: NL Question Types → Claim Types

| NL Question Pattern | Claim Type | Subject | Predicate | Object |
|---------------------|------------|---------|-----------|--------|
| "Is A a subclass of B?" | `subclass` | A (class) | subClassOf | B (class) |
| "Is A equivalent to B?" | `equivalent_classes` | A (class) | equivalentTo | B (class) |
| "Is A disjoint with B?" | `disjoint_classes` | A (class) | disjointWith | B (class) |
| "Is X an instance of A?" | `individual_membership` | X (individual) | type | A (class) |
| "Does X have property P to Y?" | `object_property_assertion` | P (object_property) | assertion | Y (class) |
| "Does X have attribute value V?" | `data_property_assertion` | property (data_property) | assertion | value (datatype) |
| "Does property P have domain A?" | `object_property_domain` | P (object_property) | domain | A (class) |
| "Does property P have range A?" | `object_property_range` | P (object_property) | range | A (class) |
| "Does property P have domain A?" | `data_property_domain` | P (data_property) | domain | A (class) |
| "Does property P have range D?" | `data_property_range` | P (data_property) | range | D (datatype) |
| "Is value V a valid literal of type D?" | `literal_validity` | P (data_property) | literalValid | D (datatype) |
| "Are A and B compatible?" | `class_compatibility` | A (class) | compatibleWith | B (class) |
| "Is the ontology consistent?" | `ontology_consistency` | owl:Thing | consistent | owl:Thing |
| "Is A within the ontology scope?" | `ontology_scope` | A (class) | scope | B (class) |

## Pizza Example Pipeline

### Input: NL Question
"Is Margherita a subclass of Pizza?"

### Step 1: Identify claim type
NL pattern matches "Is A a subclass of B?" → claim type = `subclass`

### Step 2: Resolve entities to IRIs
- A = Margherita → `http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita` (class)
- B = Pizza → `http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza` (class)

### Step 3: Construct structured claim
```json
{
  "id": "pizza-001-c1",
  "type": "subclass",
  "required": true,
  "subject": {"kind": "class", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Margherita"},
  "predicate": "subClassOf",
  "object": {"kind": "class", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza"}
}
```

### Step 4: Submit for verification
The structured claim is submitted to owl4agents `verify-claims` endpoint.
Result: `supported` (Margherita is indeed a subclass of NamedPizza, which is a subclass of Pizza).

## NL Field in Question Sets

The `question` field in benchmark question sets is informational — it captures the original NL
question for human readability. However, **structured claims are still required for verification
execution**. NL-only questions without claim decomposition are rejected with
`INVALID_QUESTION_SET`.

## All 14 Claim Types

1. **subclass** — A subClassOf B
2. **equivalent_classes** — A equivalentTo B
3. **disjoint_classes** — A disjointWith B
4. **individual_membership** — X type A
5. **object_property_assertion** — P has assertion involving Y
6. **data_property_assertion** — P has data value assertion
7. **object_property_domain** — P domain A
8. **object_property_range** — P range A
9. **data_property_domain** — P domain A
10. **data_property_range** — P range D
11. **literal_validity** — Value V valid for datatype D
12. **class_compatibility** — A compatibleWith B
13. **ontology_consistency** — Ontology is consistent
14. **ontology_scope** — A is within ontology scope
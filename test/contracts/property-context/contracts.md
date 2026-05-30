# Property Context Output Contracts

## Contract: Object property context

```json
{
  "status": "success",
  "data": {
    "iri": "string — the property IRI",
    "prefixedName": "string",
    "label": "string",
    "comment": "string — or null",
    "domain": ["string — list of domain class IRIs"],
    "range": ["string — list of range class IRIs"],
    "inverseProperties": ["string — list of inverse property IRIs"],
    "superProperties": ["string — list of superproperty IRIs"],
    "subProperties": ["string — list of subproperty IRIs"],
    "characteristics": ["functional | inverseFunctional | transitive | symmetric | asymmetric | reflexive | irreflexive"]
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

## Contract: Data property context

```json
{
  "status": "success",
  "data": {
    "iri": "string — the property IRI",
    "prefixedName": "string",
    "label": "string",
    "comment": "string — or null",
    "domain": ["string — list of domain class IRIs"],
    "range": ["string — list of range/datatype IRIs"],
    "datatype": "string — primary datatype IRI, or null",
    "superProperties": ["string — list of superproperty IRIs"],
    "subProperties": ["string — list of subproperty IRIs"]
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

## Contract: Property not found

```json
{
  "status": "error",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "message": "No property entity with IRI '<iri>' found in ontology '<ontologyId>'.",
    "details": {
      "entityIri": "<iri>",
      "ontologyId": "<id>"
    }
  }
}
```

## Fixture-Specific Expected Values

### 05-property-axioms.owl — context for worksFor (object property)

- `domain`: `["http://example.org/property-axioms#Person"]`
- `range`: `["http://example.org/property-axioms#Organization"]`
- `inverseProperties`: `["http://example.org/property-axioms#employs"]`
- `superProperties`: []
- `subProperties`: []

### 05-property-axioms.owl — context for employs (object property)

- `domain`: `["http://example.org/property-axioms#Organization"]`
- `range`: `["http://example.org/property-axioms#Person"]`
- `inverseProperties`: `["http://example.org/property-axioms#worksFor"]`

### 05-property-axioms.owl — context for hasParent (object property)

- `domain`: `["http://example.org/property-axioms#Person"]`
- `range`: `["http://example.org/property-axioms#Person"]`
- `inverseProperties`: `["http://example.org/property-axioms#hasChild"]`
- `subProperties`: `["http://example.org/property-axioms#hasMother", "http://example.org/property-axioms#hasFather"]`

### 05-property-axioms.owl — context for hasName (data property)

- `domain`: `["http://example.org/property-axioms#Person"]`
- `range`: `["http://www.w3.org/2001/XMLSchema#string"]`
- `datatype`: `"http://www.w3.org/2001/XMLSchema#string"`
- `superProperties`: []
- `subProperties`: `["http://example.org/property-axioms#hasFirstName", "http://example.org/property-axioms#hasLastName"]`

### 05-property-axioms.owl — context for hasAge (data property)

- `domain`: `["http://example.org/property-axioms#Person"]`
- `range`: `["http://www.w3.org/2001/XMLSchema#integer"]`
- `datatype`: `"http://www.w3.org/2001/XMLSchema#integer"`
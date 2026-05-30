# Individual Context Output Contracts

## Contract: Individual context for valid individual

```json
{
  "status": "success",
  "data": {
    "iri": "string — the individual IRI",
    "prefixedName": "string",
    "label": "string — rdfs:label",
    "comment": "string — rdfs:comment, or null",
    "explicitTypes": ["string — list of asserted class type IRIs"],
    "objectPropertyAssertions": [
      {
        "propertyIri": "string — the object property IRI",
        "propertyLabel": "string",
        "targetIri": "string — the target individual IRI",
        "targetLabel": "string"
      }
    ],
    "dataPropertyAssertions": [
      {
        "propertyIri": "string — the data property IRI",
        "propertyLabel": "string",
        "literalValue": "string — the lexical form of the literal",
        "datatypeIri": "string — the datatype IRI of the literal, e.g. xsd:string, xsd:integer"
      }
    ]
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

## Contract: Individual not found

```json
{
  "status": "error",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "message": "No individual entity with IRI '<iri>' found in ontology '<ontologyId>'.",
    "details": {
      "entityIri": "<iri>",
      "ontologyId": "<id>"
    }
  }
}
```

## Fixture-Specific Expected Values

### 06-individual-assertions.owl — context for alice

- `label`: `"Alice"`
- `explicitTypes`: `["http://example.org/individual-assertions#Student"]`
- `objectPropertyAssertions`: []
- `dataPropertyAssertions`: [
    { `propertyIri`: `"http://example.org/individual-assertions#hasName"`, `literalValue`: `"Alice Smith"`, `datatypeIri`: `"http://www.w3.org/2001/XMLSchema#string"` },
    { `propertyIri`: `"http://example.org/individual-assertions#hasAge"`, `literalValue`: `"22"`, `datatypeIri`: `"http://www.w3.org/2001/XMLSchema#integer"` }
  ]

### 06-individual-assertions.owl — context for bob

- `label`: `"Bob"`
- `explicitTypes`: `["http://example.org/individual-assertions#Professor"]`
- `objectPropertyAssertions`: [
    { `propertyIri`: `"http://example.org/individual-assertions#worksFor"`, `targetIri`: `"http://example.org/individual-assertions#stanford"` },
    { `propertyIri`: `"http://example.org/individual-assertions#teaches"`, `targetIri`: `"http://example.org/individual-assertions#alice"` }
  ]
- `dataPropertyAssertions`: [
    { `propertyIri`: `"http://example.org/individual-assertions#hasName"`, `literalValue`: `"Bob Johnson"`, `datatypeIri`: `"http://www.w3.org/2001/XMLSchema#string"` },
    { `propertyIri`: `"http://example.org/individual-assertions#hasAge"`, `literalValue`: `"45"`, `datatypeIri`: `"http://www.w3.org/2001/XMLSchema#integer"` }
  ]

### 06-individual-assertions.owl — context for stanford

- `label`: `"Stanford University"`
- `explicitTypes`: `["http://example.org/individual-assertions#University"]`
- `objectPropertyAssertions`: []
- `dataPropertyAssertions`: []
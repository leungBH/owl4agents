# Entity Search Output Contracts

## Contract: Search by label

```json
{
  "status": "success",
  "data": {
    "results": [
      {
        "iri": "string — full entity IRI",
        "prefixedName": "string — short form with prefix, e.g. ex:Dog",
        "label": "string — rdfs:label value",
        "comment": "string — rdfs:comment value, or null",
        "type": "class | object_property | data_property | annotation_property | individual | datatype",
        "score": "number — relevance score",
        "evidence": {
          "ontologyId": "string",
          "graphScope": "explicit",
          "entitySource": "string — the matched IRI",
          "extractionStatus": "explicit"
        }
      }
    ],
    "totalResults": "integer",
    "query": "string — the original search query"
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

## Contract: Search with no matches

```json
{
  "status": "success",
  "data": {
    "results": [],
    "totalResults": 0,
    "query": "string"
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

## Contract: Search on missing ontology

```json
{
  "status": "error",
  "error": {
    "code": "ONTOLOGY_NOT_FOUND",
    "message": "No ontology with ID '<id>' found in the workspace catalog.",
    "details": {
      "ontologyId": "<id>"
    }
  }
}
```

## Fixture-Specific Expected Values

### 02-subclass-transitive.owl — search "Dog"

- Should match: `http://example.org/subclass-transitive#Dog`
- `type`: `"class"`
- `label`: `"Dog"`

### 05-property-axioms.owl — search "works for"

- Should match: `http://example.org/property-axioms#worksFor`
- `type`: `"object_property"`
- `label`: `"works for"`

### 05-property-axioms.owl — search "has name"

- Should match: `http://example.org/property-axioms#hasName`
- `type`: `"data_property"`
- `label`: `"has name"`

### 06-individual-assertions.owl — search "Alice"

- Should match: `http://example.org/individual-assertions#alice`
- `type`: `"individual"`
- `label`: `"Alice"`
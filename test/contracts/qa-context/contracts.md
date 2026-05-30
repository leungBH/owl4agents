# QA Context Output Contracts

## Contract: QA context for matched question

```json
{
  "status": "success",
  "data": {
    "question": "string — the original question",
    "matchedEntities": [
      {
        "iri": "string",
        "prefixedName": "string",
        "label": "string",
        "type": "class | object_property | data_property | individual",
        "score": "number",
        "matchReason": "label | alias | iri | comment"
      }
    ],
    "classContext": [
      {
        "iri": "string",
        "label": "string",
        "superclasses": ["string — IRI list"],
        "subclasses": ["string — IRI list"],
        "equivalentClasses": ["string — IRI list"],
        "disjointClasses": ["string — IRI list"],
        "restrictions": []
      }
    ],
    "propertyContext": [
      {
        "iri": "string",
        "label": "string",
        "domain": ["string — IRI list"],
        "range": ["string — IRI list"],
        "inverseProperties": ["string — IRI list"]
      }
    ],
    "individualContext": [
      {
        "iri": "string",
        "label": "string",
        "explicitTypes": ["string — IRI list"],
        "objectPropertyAssertions": [],
        "dataPropertyAssertions": []
      }
    ],
    "sparqlEvidence": [
      {
        "query": "string — the SPARQL query used",
        "resultSummary": "string — brief description of results",
        "bindingCount": "integer"
      }
    ],
    "naturalLanguageContext": "string — assembled human-readable context summary",
    "bounds": {
      "maxEntities": "integer — limit applied",
      "maxDepth": "integer — depth limit applied",
      "includedSections": ["string — list of included context sections]"
    },
    "warnings": []
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601",
    "evidence": {
      "ontologyId": "string",
      "graphScope": "explicit",
      "entitySource": "string — primary matched entity",
      "extractionStatus": "explicit"
    }
  }
}
```

## Contract: QA context for no-match question

```json
{
  "status": "success",
  "data": {
    "question": "string — the original question",
    "matchedEntities": [],
    "classContext": [],
    "propertyContext": [],
    "individualContext": [],
    "sparqlEvidence": [],
    "naturalLanguageContext": "string — explains no entities were matched",
    "bounds": {
      "maxEntities": "integer",
      "maxDepth": "integer",
      "includedSections": []
    },
    "warnings": [
      {
        "type": "no_match",
        "message": "string — explicit warning that no ontology entities matched the question terms",
        "severity": "low_confidence"
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

## Contract: QA context with bounds applied

When `maxEntities` or `maxDepth` limits truncate the context, the result SHALL:
- Respect the limit in the number of matched entities and context depth
- Include the `bounds` field showing the applied limits
- Not silently exceed the limits

## Contract: QA context on missing ontology

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

### 06-individual-assertions.owl — QA context for "Who does Alice study with?"

- `matchedEntities`: should include `alice` (individual)
- `classContext`: should include Student class context
- `individualContext`: should include alice individual context
- `warnings`: empty or minimal

### 07-sparql-safety.owl — QA context for "What cities are in France?"

- `matchedEntities`: should include `paris` (individual), `City` (class), `france` (individual)
- `classContext`: should include City class context
- `individualContext`: should include paris individual context showing `locatedIn → france`

### 01-minimal.owl — QA context for "quantum entanglement" (no match)

- `matchedEntities`: empty
- `warnings`: should include a `no_match` warning with `severity: "low_confidence"`
# Summary Output Contracts

## Contract: Summary for valid fixture

Every valid imported ontology SHALL produce a summary result with these fields:

```json
{
  "status": "success",
  "data": {
    "ontologyId": "string — user-provided ID",
    "ontologyIri": "string — the ontology IRI from the OWL file, or null if anonymous",
    "versionIri": "string — the version IRI, or null if absent",
    "imports": ["string — list of imported ontology IRIs"],
    "profile": {
      "profiles": ["string — e.g. OWL2DL, OWL2EL, OWL2QL, OWL2RL, OWL2Full"],
      "violations": [
        {
          "profile": "string",
          "description": "string",
          "axiomType": "string — optional",
          "count": "integer — number of violations for this type"
        }
      ]
    },
    "entityCounts": {
      "classes": "integer",
      "objectProperties": "integer",
      "dataProperties": "integer",
      "annotationProperties": "integer",
      "individuals": "integer",
      "datatypes": "integer"
    }
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

## Contract: Summary for ontology not found

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

### 01-minimal.owl

- `ontologyIri`: `"http://example.org/minimal"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 1, objectProperties: 0, dataProperties: 0, annotationProperties: 0, individuals: 0, datatypes: 0 }`

### 02-subclass-transitive.owl

- `ontologyIri`: `"http://example.org/subclass-transitive"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 5, objectProperties: 0, dataProperties: 0, annotationProperties: 0, individuals: 0, datatypes: 0 }`

### 03-equivalent-classes.owl

- `ontologyIri`: `"http://example.org/equivalent-classes"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 4, objectProperties: 0, dataProperties: 0, annotationProperties: 0, individuals: 0, datatypes: 0 }`

### 04-disjoint-classes.owl

- `ontologyIri`: `"http://example.org/disjoint-classes"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 4, objectProperties: 0, dataProperties: 0, annotationProperties: 0, individuals: 0, datatypes: 0 }`

### 05-property-axioms.owl

- `ontologyIri`: `"http://example.org/property-axioms"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 3, objectProperties: 6, dataProperties: 5, annotationProperties: 0, individuals: 0, datatypes: 0 }`

### 06-individual-assertions.owl

- `ontologyIri`: `"http://example.org/individual-assertions"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 4, objectProperties: 2, dataProperties: 2, annotationProperties: 0, individuals: 3, datatypes: 0 }`

### 07-sparql-safety.owl

- `ontologyIri`: `"http://example.org/sparql-safety"`
- `versionIri`: null
- `imports`: []
- `entityCounts`: `{ classes: 2, objectProperties: 1, dataProperties: 1, annotationProperties: 0, individuals: 4, datatypes: 0 }`
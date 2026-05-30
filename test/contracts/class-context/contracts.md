# Class Context Output Contracts

## Contract: Class context for valid class

```json
{
  "status": "success",
  "data": {
    "iri": "string — the class IRI",
    "prefixedName": "string",
    "label": "string — rdfs:label",
    "comment": "string — rdfs:comment, or null",
    "directSubclasses": ["string — list of direct subclass IRIs"],
    "directSuperclasses": ["string — list of direct superclass IRIs"],
    "equivalentClasses": ["string — list of equivalent class IRIs"],
    "disjointClasses": ["string — list of disjoint class IRIs"],
    "restrictions": [
      {
        "restrictionType": "someValuesFrom | allValuesFrom | hasValue | cardinality",
        "onProperty": "string — property IRI",
        "filler": "string — class IRI or datatype IRI",
        "cardinality": "integer — for cardinality restrictions, or null"
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

## Contract: Class not found

```json
{
  "status": "error",
  "error": {
    "code": "ENTITY_NOT_FOUND",
    "message": "No class entity with IRI '<iri>' found in ontology '<ontologyId>'.",
    "details": {
      "entityIri": "<iri>",
      "ontologyId": "<id>"
    }
  }
}
```

## Fixture-Specific Expected Values

### 02-subclass-transitive.owl — context for Mammal

- `directSuperclasses`: `["http://example.org/subclass-transitive#Animal"]`
- `directSubclasses`: `["http://example.org/subclass-transitive#Dog", "http://example.org/subclass-transitive#Cat"]`
- `equivalentClasses`: []
- `disjointClasses`: []
- `restrictions`: []

### 02-subclass-transitive.owl — context for Animal

- `directSuperclasses`: `["http://www.w3.org/2002/07/owl#Thing"]` or empty (top-level class)
- `directSubclasses`: `["http://example.org/subclass-transitive#Mammal"]`
- `equivalentClasses`: []
- `disjointClasses`: []

### 03-equivalent-classes.owl — context for Person

- `directSuperclasses`: []
- `directSubclasses`: []
- `equivalentClasses`: `["http://example.org/equivalent-classes#Human"]`
- `disjointClasses`: []

### 04-disjoint-classes.owl — context for Cat

- `directSuperclasses`: []
- `directSubclasses`: []
- `equivalentClasses`: []
- `disjointClasses`: `["http://example.org/disjoint-classes#Dog"]`
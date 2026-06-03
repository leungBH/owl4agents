# SPARQL Output Contracts

## Contract: SELECT query result

```json
{
  "status": "success",
  "data": {
    "queryForm": "SELECT",
    "variables": ["string — list of result variable names"],
    "bindings": [
      {
        "variableName": "string — the variable name",
        "value": "string — the binding value (IRI or literal lexical form)",
        "datatype": "string — datatype IRI for literals, or null for IRIs",
        "type": "uri | literal | blank"
      }
    ],
    "totalBindings": "integer — total number of binding rows",
    "truncated": "boolean — true if results were cut off by a limit"
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601",
    "queryMetadata": {
      "queryForm": "SELECT",
      "graphScope": "explicit",
      "executionTimeMs": "integer"
    }
  }
}
```

## Contract: ASK query result

```json
{
  "status": "success",
  "data": {
    "queryForm": "ASK",
    "result": "boolean"
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601",
    "queryMetadata": {
      "queryForm": "ASK",
      "executionTimeMs": "integer"
    }
  }
}
```

## Contract: CONSTRUCT query result

```json
{
  "status": "success",
  "data": {
    "queryForm": "CONSTRUCT",
    "triples": [
      {
        "subject": "string — subject IRI or blank node",
        "predicate": "string — predicate IRI",
        "object": "string — object IRI or literal value",
        "objectDatatype": "string — datatype IRI for literal objects, or null"
      }
    ],
    "totalTriples": "integer",
    "truncated": "boolean"
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601",
    "queryMetadata": {
      "queryForm": "CONSTRUCT",
      "executionTimeMs": "integer"
    }
  }
}
```

## Contract: DESCRIBE query result

```json
{
  "status": "success",
  "data": {
    "queryForm": "DESCRIBE",
    "triples": [
      {
        "subject": "string",
        "predicate": "string",
        "object": "string",
        "objectDatatype": "string — or null"
      }
    ],
    "totalTriples": "integer",
    "truncated": "boolean"
  },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601",
    "queryMetadata": {
      "queryForm": "DESCRIBE",
      "executionTimeMs": "integer"
    }
  }
}
```

## Contract: Malformed query validation

```json
{
  "status": "error",
  "error": {
    "code": "INVALID_SPARQL",
    "message": "string — parser diagnostics describing the syntax error",
    "details": {
      "queryForm": "unknown",
      "parseError": "string — detailed parser error message",
      "errorLine": "integer — line number of syntax error, if available"
    }
  }
}
```

## Contract: SPARQL update rejection

```json
{
  "status": "error",
  "error": {
    "code": "READONLY_VIOLATION",
    "message": "SPARQL update operations are not allowed in readonly mode.",
    "details": {
      "queryForm": "UPDATE",
      "operationType": "INSERT | DELETE | LOAD | CLEAR | CREATE | DROP | MOVE"
    }
  }
}
```

## Contract: Query timeout

```json
{
  "status": "error",
  "error": {
    "code": "QUERY_TIMEOUT",
    "message": "string — the query exceeded the configured timeout.",
    "details": {
      "timeoutMs": "integer — configured timeout in milliseconds",
      "executionTimeMs": "integer — actual time before cancellation"
    }
  }
}
```

## Contract: Result limit

When a query produces more results than the configured limit, the result is returned with `truncated: true` in the data field. The system SHALL NOT silently drop results beyond the limit without indicating truncation.

## Fixture-Specific Expected Values

### 07-sparql-safety.owl — SELECT all cities

Query: `SELECT ?city ?name WHERE { ?city a <http://example.org/sparql-safety#City> . ?city <http://www.w3.org/2000/01/rdf-schema#label> ?name }`

Expected:
- `queryForm`: `"SELECT"`
- `variables`: `["city", "name"]`
- Should return 2 bindings: Paris and Berlin
- `totalBindings`: 2

### 07-sparql-safety.owl — ASK whether Paris is a City

Query: `ASK { <http://example.org/sparql-safety#paris> a <http://example.org/sparql-safety#City> }`

Expected:
- `queryForm`: `"ASK"`
- `result`: true

### 07-sparql-safety.owl — CONSTRUCT city locations

Query: `CONSTRUCT { ?city <http://example.org/sparql-safety#locatedIn> ?country } WHERE { ?city <http://example.org/sparql-safety#locatedIn> ?country }`

Expected:
- `queryForm`: `"CONSTRUCT"`
- Should return triples for Paris→France and Berlin→Germany

### 07-sparql-safety.owl — DESCRIBE Paris

Query: `DESCRIBE <http://example.org/sparql-safety#paris>`

Expected:
- `queryForm`: `"DESCRIBE"`
- Should return triples where Paris is subject or object

### 07-sparql-safety.owl — SPARQL update rejection

Query: `INSERT DATA { <http://example.org/sparql-safety#newCity> a <http://example.org/sparql-safety#City> }`

Expected:
- `error.code`: `"READONLY_VIOLATION"`
- `error.details.operationType`: `"INSERT"`

### 07-sparql-safety.owl — Malformed query

Query: `SELCT ?x WHERE { ?x a ?y }`

Expected:
- `error.code`: `"INVALID_SPARQL"`
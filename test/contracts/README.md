# owl4agents Test Contract Overview

This directory contains public, versioned contracts for acceptance tests, CLI/MCP parity, release readiness, output schemas, and tester reporting discipline.

Local acceptance reports are stored under `reports/acceptance/` and are intentionally ignored by Git. Testers MUST use `test/contracts/acceptance-report/contracts.md` as the default report standard unless a version-specific OpenSpec change defines stricter requirements.

## Shared Output Schema

This document defines the shared schema patterns used across v0.1, v0.2, and v0.3 output contracts.

## Contract Files

| Contract | Purpose |
| --- | --- |
| `acceptance-report/contracts.md` | Required acceptance report structure, evidence, defect ledger, skip rules, and verdict rules |
| `cli-mcp-parity/contracts.md` | Parity expectations for overlapping CLI and MCP operations |
| `claim-verification/contracts.md` | v0.3 structured claim schema, verdicts, evidence paths, negative tests, and fixture expectations |
| `release-readiness/contracts.md` | Release checklist and handoff quality expectations |

## Result Envelope

All successful service operations return a result envelope:

```json
{
  "status": "success",
  "data": { ... },
  "metadata": {
    "ontologyId": "string",
    "graphScope": "explicit",
    "extractionStatus": "explicit",
    "timestamp": "ISO-8601"
  }
}
```

All error results return an error envelope:

```json
{
  "status": "error",
  "error": {
    "code": "string - one of the defined error codes",
    "message": "string - human-readable error description",
    "details": { ... }
  }
}
```

## Error Codes

| Code | Description | When |
| --- | --- | --- |
| `ONTOLOGY_NOT_FOUND` | No ontology with the given ID in the workspace catalog | Any operation referencing an unknown ontology ID |
| `ENTITY_NOT_FOUND` | No entity matching the given IRI or search criteria | Entity context or search with no match |
| `IMPORT_FAILED` | OWL API could not parse or load the ontology file | Invalid or unsupported ontology import |
| `INVALID_SPARQL` | SPARQL query has syntax or structural errors | Malformed SPARQL validation or execution |
| `READONLY_VIOLATION` | Operation would modify ontology or workspace state | Write-style tool call, SPARQL update, or file write |
| `FILE_ACCESS_DENIED` | File path is not cataloged or not an explicit import path | Attempt to read arbitrary file through tool |
| `QUERY_TIMEOUT` | SPARQL query exceeded the configured timeout | Long-running SPARQL query |

## Evidence Metadata

Results that include evidence should provide:

```json
{
  "evidence": {
    "ontologyId": "string",
    "graphScope": "explicit | inferred | union",
    "entitySource": "string - IRI of the source entity",
    "axiomSource": "string - axiom type, e.g. SubClassOf",
    "tripleSource": "string - RDF triple pattern, optional",
    "extractionStatus": "explicit | inferred | unknown"
  }
}
```

## Ontology ID Format

Ontology IDs are user-provided identifiers used to reference imported ontologies in the workspace catalog. They are strings, not IRIs.

## Graph Scope

| Scope | Description |
| --- | --- |
| `explicit` | Axioms and facts from the imported ontology file only |
| `inferred` | Axioms inferred by a reasoner (requires prior reasoning) |
| `union` | Combined explicit + inferred |

## Entity Types

| Type | OWL API category |
| --- | --- |
| `class` | OWLClass |
| `object_property` | OWLObjectProperty |
| `data_property` | OWLDataProperty |
| `annotation_property` | OWLAnnotationProperty |
| `individual` | OWLNamedIndividual |
| `datatype` | OWLDatatype |

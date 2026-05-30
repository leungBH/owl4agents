# Readonly Safety Contracts

## Principle

v0.1 is strictly readonly. The system SHALL reject all write-style operations through every interface (CLI, MCP, SPARQL). This is enforced by the shared `OntologyService` layer, not only by adapters.

## Safety Scenarios

### Contract: Write-style tool rejection

Any MCP tool call that would modify ontology or workspace state SHALL be rejected:

```json
{
  "status": "error",
  "error": {
    "code": "READONLY_VIOLATION",
    "message": "string — describes that the operation is not allowed in v0.1 readonly mode",
    "details": {
      "operationType": "import_ontology | edit_axiom | create_snapshot | rollback | export_ontology",
      "mode": "readonly"
    }
  }
}
```

Note: CLI `import` is the only exception — it is a workspace setup operation that adds an ontology to the catalog. Import does not modify ontology content.

### Contract: SPARQL update rejection

Any SPARQL update operation SHALL be rejected:

```json
{
  "status": "error",
  "error": {
    "code": "READONLY_VIOLATION",
    "message": "SPARQL update operations are not allowed in v0.1 readonly mode.",
    "details": {
      "operationType": "INSERT | DELETE | LOAD | CLEAR | CREATE | DROP | MOVE",
      "queryForm": "UPDATE"
    }
  }
}
```

Blocked SPARQL keywords:
- `INSERT`, `DELETE`, `LOAD`, `CLEAR`, `CREATE`, `DROP`, `MOVE`
- Any SPARQL Update syntax

### Contract: Non-cataloged file read rejection

Any tool call that attempts to read a file not in the workspace catalog (and not an explicit import path) SHALL be rejected:

```json
{
  "status": "error",
  "error": {
    "code": "FILE_ACCESS_DENIED",
    "message": "string — explains that the file path is not cataloged",
    "details": {
      "requestedPath": "string — the requested file path",
      "reason": "not_cataloged | not_import_path"
    }
  }
}
```

### Contract: Failed-attempt logging

Every rejected readonly violation SHALL be logged. The log entry SHALL include:

```json
{
  "timestamp": "ISO-8601",
  "interface": "cli | mcp | sparql",
  "operation": "string — the attempted operation type",
  "ontologyId": "string — when present",
  "resultStatus": "rejected",
  "errorCode": "READONLY_VIOLATION | FILE_ACCESS_DENIED",
  "details": { ... }
}
```

## Verification Scenarios

### Scenario: Reject write-style MCP tool

- **WHEN** an MCP client calls an unsupported write tool (e.g., `ontology_import`, `ontology_edit_axiom`, `ontology_snapshot`, `ontology_rollback`)
- **THEN** the server returns `READONLY_VIOLATION` error
- **AND** workspace files are unchanged

### Scenario: Reject SPARQL update via CLI

- **WHEN** the user submits a SPARQL UPDATE query via `owl4agents query`
- **THEN** the CLI exits non-zero and reports `READONLY_VIOLATION`

### Scenario: Reject SPARQL update via MCP

- **WHEN** an MCP client submits a SPARQL UPDATE through any SPARQL tool
- **THEN** the tool returns `READONLY_VIOLATION` error

### Scenario: Reject arbitrary file read

- **WHEN** a CLI command or MCP tool requests reading a file path that is not in the catalog
- **THEN** the system returns `FILE_ACCESS_DENIED` error

### Scenario: Log failed attempt

- **WHEN** any readonly violation is rejected
- **THEN** the system appends a log record to the workspace log
- **AND** the log contains timestamp, interface, operation, result status, and error code
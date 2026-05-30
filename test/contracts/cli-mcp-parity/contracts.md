# CLI/MCP Parity Contracts

## Principle

For every v0.1 operation that has both a CLI command and an MCP tool, the system SHALL return equivalent behavior. "Equivalent" means:

1. **Same data fields**: Both interfaces return the same data fields in their result payloads.
2. **Same error codes**: Both interfaces return the same error codes for the same failure conditions.
3. **Same behavior**: Both interfaces route through the shared `OntologyService` and produce the same semantic results.

The CLI and MCP adapters may differ in:
- Output format (CLI prints human-readable text, MCP returns JSON)
- Exit codes (CLI uses non-zero exit codes, MCP returns error JSON)

## Parity Checks Required for v0.1

### Summary Parity

| CLI command | MCP tool | Parity requirement |
| --- | --- | --- |
| `owl4agents summary <ontology-id>` | `ontology_summary` | Both return same ontology IRI, version IRI, imports, profile, entity counts |

### Search Parity

| CLI command | MCP tool | Parity requirement |
| --- | --- | --- |
| `owl4agents search <ontology-id> <query>` | `ontology_search_entities` | Both return same matched entities, types, scores |

### Entity Context Parity

| CLI command | MCP tool | Parity requirement |
| --- | --- | --- |
| `owl4agents entity <ontology-id> <entity-iri>` | `ontology_get_entity_context` | Both return same entity context fields |
| (class subset) | `ontology_get_class_context` | Same class context fields |
| (property subset) | `ontology_get_object_property_context` / `ontology_get_data_property_context` | Same property context fields |
| (individual subset) | `ontology_get_individual_context` | Same individual context fields |

### SPARQL Parity

| CLI command | MCP tool | Parity requirement |
| --- | --- | --- |
| `owl4agents query <ontology-id> --validate <query>` | `ontology_validate_sparql` | Both return same validation diagnostics |
| `owl4agents query <ontology-id> --select <query>` | `ontology_sparql_select` | Same bindings |
| `owl4agents query <ontology-id> --ask <query>` | `ontology_sparql_ask` | Same boolean result |
| `owl4agents query <ontology-id> --construct <query>` | `ontology_sparql_construct` | Same triples |
| `owl4agents query <ontology-id> --describe <query>` | `ontology_sparql_describe` | Same triples |

### QA Context Parity

| CLI command | MCP tool | Parity requirement |
| --- | --- | --- |
| `owl4agents context <ontology-id> <question>` | `ontology_get_qa_context` | Same matched entities, context, warnings, evidence metadata |

## Contract: Parity verification test

Each parity test SHALL:
1. Call the CLI command and capture the output
2. Call the MCP tool with equivalent parameters
3. Parse both outputs into structured data
4. Compare: equivalent ontology ID, equivalent metadata, equivalent entity counts / search results / context fields
5. Report differences as parity failures

```json
{
  "parityCheck": {
    "operation": "summary | search | entity_context | class_context | property_context | individual_context | sparql_validate | sparql_select | sparql_ask | sparql_construct | sparql_describe | qa_context",
    "cliOutput": { ... },
    "mcpOutput": { ... },
    "equivalent": "boolean",
    "differences": [
      {
        "field": "string — the field path where a difference was found",
        "cliValue": "any",
        "mcpValue": "any"
      }
    ]
  }
}
```
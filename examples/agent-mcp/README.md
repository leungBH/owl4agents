# Agent MCP Example

This example demonstrates how a local agent connects to owl4agents through the readonly MCP (Model Context Protocol) server.

## What it demonstrates

- **MCP client configuration**: How to configure Claude, Cursor, or a generic MCP client to connect to owl4agents
- **MCP startup**: Starting the server, sending `initialize`, and receiving capabilities
- **Tool discovery**: Listing available readonly tools via `tools/list`
- **Tool-call samples**: Sanitized transcript showing a real tool call and response

## Prerequisites

- Java 22+ installed
- Shadow jar built: `.\gradlew.bat :modules:ontology-cli:shadowJar`
- An MCP-compatible agent client (Claude Desktop, Cursor, or custom)

## MCP Client Configuration

### Claude Desktop

Add to your Claude Desktop MCP config (typically `claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "node",
      "args": ["tools/npm/bin/owl4agents.js", "mcp"],
      "cwd": "<your-owl4agents-repo-path>"
    }
  }
}
```

Or use the Windows wrapper on Windows:

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "bin\\owl4agents-mcp.cmd",
      "cwd": "<your-owl4agents-repo-path>"
    }
  }
}
```

### Cursor

Use `owl4agents mcp-config --client cursor` to generate the config:

```bash
node tools/npm/bin/owl4agents.js mcp-config --client cursor
```

### Generic MCP client

Use `owl4agents mcp-config --client generic` for a generic configuration:

```bash
node tools/npm/bin/owl4agents.js mcp-config --client generic
```

Or manually start the server in stdio mode:

```bash
node tools/npm/bin/owl4agents.js mcp
```

## MCP Startup and Tool List

When the MCP server starts, it responds to the `initialize` request with:

```json
{
  "capabilities": { "tools": {} },
  "serverInfo": {
    "name": "owl4agents",
    "version": "0.4.0"
  }
}
```

Send `tools/list` to discover available tools. The response includes readonly ontology and claim verification tools:

- `ontology_summary`
- `ontology_list`
- `ontology_search_entities`
- `ontology_get_entity_context`
- `ontology_classify`
- `ontology_verify_claim`
- `ontology_get_evidence_path`
- `ontology_find_counterexamples`
- `ontology_explain_unknown`
- `ontology_detect_missing_entities`
- `ontology_sparql_select`
- `ontology_sparql_ask`
- `ontology_check_entailment`
- `ontology_check_consistency`
- `ontology_get_inferred_facts`

> Note: `ontology_import` is a write tool planned for v0.8. It requires `--allow-write` and is not available in the default readonly MCP server.

## Tool-Call Transcript Sample

See `transcripts/verify-claim-transcript.md` for a sanitized example of calling `ontology_verify_claim` through MCP.

## Windows Note

On Windows, use the npm launcher (`node tools/npm/bin/owl4agents.js mcp`) or the Windows wrapper (`tools/bin\owl4agents-mcp.cmd`). Both use `java -cp` mode. Do not use `java -jar` for MCP on Windows — it produces an `ACCESS_VIOLATION` crash on some setups.

## Fixture attribution

No fixtures required — the MCP server starts from workspace state.

## Troubleshooting

- **MCP startup failure:** Verify the shadow jar is built. Run `.\gradlew.bat :modules:ontology-cli:shadowJar`.
- **ACCESS_VIOLATION on Windows:** Use `node tools/npm/bin/owl4agents.js mcp` or `tools/bin\owl4agents-mcp.cmd`. Both avoid the `java -jar` path that crashes on Windows.
- **Timeout:** The default MCP startup timeout is 30 seconds. If the reasoner takes longer on large ontologies, increase the timeout in the manifest.
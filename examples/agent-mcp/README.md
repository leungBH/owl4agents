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
    "version": "0.8.4"
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

> Note: `ontology_import` is a write tool not available in the default readonly MCP server. It requires `--allow-write`.

## HTTP Transport (v0.7+)

The v0.7 release added an optional HTTP/JSON-RPC transport alongside stdio. **Stdio remains the default path**; the HTTP transport is opt-in via `--transport http`. Use it when your agent client speaks JSON-RPC over HTTP/POST (e.g. an HTTP-capable MCP client, or a sidecar/in-process setup).

### Start the HTTP listener

```bash
node tools/npm/bin/owl4agents.js mcp --readonly --transport http --port 8080
```

Default port is `8080`; default host is `127.0.0.1`. Use `--host 0.0.0.0` to bind on all interfaces. The listener stays in the foreground until killed; the systemd unit on the reference deployment uses `Restart=on-failure` for recovery.

### HTTP client config

A committed HTTP-only MCP client config is at [`configs/http-mcp-config.json`](configs/http-mcp-config.json):

```json
{
  "mcpServers": {
    "owl4agents": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

You can regenerate it (or override the URL) with:

```bash
node tools/npm/bin/owl4agents.js mcp-config --client http
node tools/npm/bin/owl4agents.js mcp-config --client http --url http://remote-host:9000/mcp
```

## Trae IDE (v0.8+)

Trae IDE speaks the MCP 2025-03-26 Streamable HTTP transport — it opens a
`GET /mcp` SSE stream and uses `Mcp-Session-Id` headers to bind a
logical session. v0.8.0+ owl4agents implements that transport, so the
configuration is the same as the generic HTTP client above (a single
`url` field ending in `/mcp`).

A committed Trae-only fixture is at
[`configs/trae-mcp-config.json`](configs/trae-mcp-config.json):

```json
{
  "mcpServers": {
    "owl4agents": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

You can regenerate it (or override the URL) with:

```bash
node tools/npm/bin/owl4agents.js mcp-config --client trae
node tools/npm/bin/owl4agents.js mcp-config --client trae --url http://remote-host:9000/mcp
```

In the Trae IDE MCP configuration dialog, paste the `mcpServers` block
into the JSON config. The IDE will open a long-lived `GET /mcp` SSE
stream, send `initialize` to receive a `Mcp-Session-Id`, and use that
id on every subsequent `POST /mcp` request. If the server is older
than v0.8.0, Trae IDE will fail to connect with `SSE error: Non-200
status code (405)` — upgrade to v0.8.0 or later to fix it (v0.8.4 is
recommended for the latest claim-verification accuracy and performance improvements).

### v0.8.1 Claim Types

The `ontology_verify_claim` tool in v0.8.1 supports the following
additional claim types beyond the v0.8.0 baseline:

- `different_individuals` — verify that two named individuals are
  explicitly or inferably distinct (counter-evidence: an
  `OWLSameIndividualAxiom` linking them).
- `object_property_subproperty` — verify sub-property relationships
  between two object properties, including transitive closure and
  reverse-direction entailment (for contradiction detection).
- Complex class expressions in `equivalent_classes` claims — the
  `subject.expression` and `object.expression` fields accept a
  nested structure with 6 supported types: `named`, `intersection`,
  `union`, `existential` (∃), `universal` (∀),
  and `complement` (¬). Up to 3 levels of nesting are allowed.

For example, the v0.8.1 sample `example.yaml` shows a
`different_individuals` claim against the pizza ontology, and an
`object_property_subproperty` claim against the same corpus.

### Probe commands

With the HTTP listener running on `127.0.0.1:8080`:

```bash
# Liveness banner (GET /)
curl http://127.0.0.1:8080/

# JSON-RPC initialize handshake (POST /mcp)
curl -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"probe","version":"1.0.0"}}}'

# tools/list
curl -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Error matrix for the HTTP transport (matches the v0.7 spec):

| Status | Meaning |
| --- | --- |
| 200 | JSON-RPC response with `result` or `result.isError = true` |
| 202 | JSON-RPC notification (no `id`); no body |
| 400 | JSON parse error (`code = -32700`) |
| 405 | `GET /mcp` (mcp is POST-only) |
| 415 | Wrong `Content-Type` |
| 500 | Saturated worker pool or adapter exception (`code = -32000`) |
| 501 | `Accept: text/event-stream` (SSE not supported) |

### Parity with stdio

HTTP and stdio return **JSON field-level identical** responses for the same JSON-RPC request. The wire format is **not** byte-level identical; transport-level framing (HTTP status codes, headers, empty-body for notifications) is outside the parity contract.

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
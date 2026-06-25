# v0.7.0 — MCP HTTP Transport

**Release date:** 2026-06-25
**Compatibility:** Drop-in for v0.6.0. Stdio transport unchanged; HTTP transport is opt-in via `--transport http`.

## Highlights

- **New: optional HTTP/JSON-RPC transport** for the MCP server (`POST /mcp`, `GET /info`, `GET /`). Stdio remains the default and is byte-level unchanged.
- **Single routing entry point** — `McpServerAdapter.handleJsonRpc(JsonObject)` — so HTTP and stdio transports return **JSON field-level identical** responses for the same request.
- **Eager service initialization** — 7 services constructed in dependency order in the constructor; the previous `getXxxService()` lazy accessors are gone.
- **Bounded worker pools** — 8 core threads + 100-slot queue for non-reasoner tools; 1-thread pool with `SynchronousQueue` for the 14 reasoner-using tools. Saturated pool returns `HTTP 500` + JSON-RPC `code = -32000`.
- **Strict HTTP error matrix** — `200` / `202` (notification) / `400` (parse error) / `405` / `415` / `500` (saturated pool / adapter exception) / `501` (SSE not supported).

## Quick start

```bash
# default: stdio (unchanged)
node tools/npm/bin/owl4agents.js mcp --readonly

# new in v0.7: HTTP transport
node tools/npm/bin/owl4agents.js mcp --readonly --transport http --port 8080
```

```bash
curl -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'
```

## What's in this release

### Added
- `modules/ontology-mcp/src/main/java/org/owl4agents/mcp/HttpMcpServer.java` — HTTP transport.
- `modules/ontology-mcp/src/test/java/org/owl4agents/mcp/HttpMcpServerTest.java` — 12 HTTP behavior tests (incl. TC-25 reasoner saturation and TC-27 parallel non-reasoner).
- `modules/ontology-mcp/src/test/java/org/owl4agents/mcp/McpServerAdapterTest.java` — 6 unit tests for the unified entry point and eager-init order (TC-26).
- `test/contracts/v07-acceptance/contracts.md` — 34 acceptance gates.
- CLI flags: `--transport=stdio|http`, `--host`, `--port` on the `mcp` subcommand.
- v0.7.0 OpenSpec change: `openspec/changes/add-v0-7-mcp-http-transport/`.

### Changed
- `McpServerAdapter` refactored to eager `final`-field initialization.
- `McpCommand` uses direct `new HttpMcpServer(adapter)` (no reflection).
- `McpServerIntegrationTest` routes through `handleJsonRpc`.
- `build.gradle.kts` version `0.6.0` → `0.7.0`.
- `README.md` adds v0.7 quick-start + roadmap items.
- `CHANGELOG.md` adds v0.7.0 entry.

## Test coverage

- 39 spec scenarios, 27 test cases (TC-01 .. TC-27), 34 acceptance gates.
- 12 + 6 + 6 + 4 + 1 = 29 JUnit tests added/modified in the MCP module.
- 17-section deployment smoke test (`temp/full_test2.sh`) passes against a live JDK 22 service.

## Deployment

Verified on Tencent Cloud (Ubuntu 22.04, JDK 22.0.2.9) at `106.52.5.101:8080`:

- Service runs as a systemd unit (`/etc/systemd/system/owl4agents.service`).
- `--home=/opt/owl4agents/data` puts workspace data and `mcp-tool-calls.jsonl` under `/opt` (avoids `ProtectHome=read-only`).
- `kill -9` recovers via `Restart=on-failure` (verified).

## Notes

- Readonly tool count remains **56** (unchanged from v0.6).
- HTTP/stdio parity is **field-level identical**, **not** byte-level identical. Transport framing (HTTP status, headers, empty-body for notifications) is outside the parity contract.
- v0.7 does not implement SSE server→client push — `Accept: text/event-stream` returns `501` + JSON-RPC `code = -32000`. Planned for a later release if reasoner streaming becomes a requirement.

# v0.7.1 — Post-release Asset Completion

**Release date:** 2026-06-25
**Compatibility:** Drop-in for v0.7.0. No runtime change; docs / example / version-reference fixes only.

## What changed in v0.7.1

v0.7.0 shipped the MCP HTTP transport and its full code, tests, and CHANGELOG entry, but several public-facing assets were not updated in the v0.7.0 commits. v0.7.1 closes those gaps.

### Fixed

- **`examples/agent-mcp/configs/http-mcp-config.json`** — committed (was missing in v0.7.0). Declares `mcpServers.owl4agents.url = "http://127.0.0.1:8080/mcp"`, consistent (field-level) with the new generator output.
- **`examples/agent-mcp/README.md`** — added a "HTTP Transport (v0.7+)" section covering listener startup, the committed HTTP client config, `curl` probes for `GET /` and `POST /mcp initialize`, the HTTP error matrix, and the stdio parity note. The `initialize` response example in the README now reports `serverInfo.version = "0.7.1"`.
- **`examples/agent-mcp/example.yaml`** — `fixtures:` now lists all four committed config files; two new validation commands added (`mcp-config --client http` and a manual HTTP `mcp --transport http` probe step). Expected-output block now includes `mcpServers.owl4agents.url`.
- **`examples/agent-mcp/transcripts/verify-claim-transcript.md`** — `initialize` response now reports `serverInfo.version = "0.7.1"`.
- **`tools/npm/package.json`** — `version` is now `0.7.1` (was stale `0.6.0`).
- **`.github/workflows/ci.yml`** — `--version` assertion expects `0.7.1` (was stale `0.4.0`).

### Added

- **`mcp-config --client http` (also `--client=http`)** — generates an HTTP-only MCP client config (a single `mcpServers.owl4agents.url` field). Adds a `--url <url>` override (default `http://127.0.0.1:8080/mcp`).
- 3 new test cases in `McpConfigCommandTest` (`HttpClientTests`): default URL, `--url` override, committed-fixture contract assertion.

## Compatibility

- Runtime behavior of v0.7.0 is unchanged. v0.7.1 only ships docs / example / config-fixture / version-reference corrections.
- Readonly tool count remains **56** (unchanged from v0.6 / v0.7.0).
- HTTP/stdio parity contract is unchanged: JSON field-level identical, not byte-level identical.
- No new external dependencies.

## Upgrade

```bash
git pull origin main
git checkout v0.7.1
.\gradlew.bat :modules:ontology-cli:shadowJar   # Windows
# or: ./gradlew :modules:ontology-cli:shadowJar # Linux / macOS
```

The shadow jar path is unchanged: `build/modules/ontology-cli/libs/owl4agents.jar`.

## Quick verification

```bash
java -jar build/modules/ontology-cli/libs/owl4agents.jar --version
# expect: 0.7.1

node tools/npm/bin/owl4agents.js mcp-config --client http
# expect: {"mcpServers":{"owl4agents":{"url":"http://127.0.0.1:8080/mcp"}}}

node tools/npm/bin/owl4agents.js mcp-config --client http --url http://remote:9000/mcp
# expect: {"mcpServers":{"owl4agents":{"url":"http://remote:9000/mcp"}}}
```

## Test coverage

- `McpConfigCommandTest` gains 3 cases under `HttpClientTests` (default URL, override, fixture contract). Existing stdio client tests unchanged.
- All v0.7.0 tests continue to pass: `HttpMcpServerTest` (12), `McpServerAdapterTest` (6), `McpServerIntegrationTest` (4), `McpConfigCommandTest` (4 stdio + 3 http), plus 4 unrelated tests = 29 JUnit tests in the MCP module.
- 39 spec scenarios, 27 test cases (TC-01 .. TC-27), 34 acceptance gates for v0.7.0 — unchanged. v0.7.1 does not introduce new spec scenarios.

# v0.7 Acceptance Contract

This contract defines the v0.7 acceptance gates covering the MCP HTTP transport upgrade
(POST /mcp + GET /info + GET /), public `McpServerAdapter.handleJsonRpc` entry point, and
stdio/HTTP JSON field parity.

A v0.7 report claiming PASS MUST include evidence for every required gate listed below.

## Required Report Path

```text
reports/acceptance/YYYY-MM-DD-HHMMSS-v0.7-acceptance-report.md
```

The filename MUST match the pattern `^\d{4}-\d{2}-\d{2}-\d{6}-v0\.7-acceptance-report\.md$`.
The report MUST follow `test/contracts/acceptance-report/contracts.md` and this contract.

## Required Gates

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V07-HTTP-001 | initialize-handshake | MCP-HTTP | `POST /mcp initialize` returns 200 with `protocolVersion=2025-06-18` and `serverInfo.version=0.7.0` |
| V07-HTTP-002 | tools-list-enumeration | MCP-HTTP | `POST /mcp tools/list` returns 200; tools array length equals the v0.6 readonly tool count recorded in the v0.7 CHANGELOG entry (currently 56) and matches `McpAcceptanceTest` ground truth |
| V07-HTTP-003 | tools-call-ontology-list | MCP-HTTP | `POST /mcp tools/call ontology_list` returns 200 with `content[0].text` containing the `ontologies` field |
| V07-HTTP-004 | tools-call-context-batch-real-fixture | MCP-HTTP | `POST /mcp tools/call ontology_context_batch` against the v0.6 `pizza-50.jsonl` first entry returns 200 with non-empty evidence context |
| V07-HTTP-005 | malformed-json-400 | MCP-HTTP | `POST /mcp` with non-JSON body returns 400 with JSON-RPC error `code=-32700` |
| V07-HTTP-006 | non-post-405 | MCP-HTTP | `GET /mcp` returns 405 with `Allow: POST` header |
| V07-HTTP-007 | non-json-content-type-415 | MCP-HTTP | `POST /mcp` with `Content-Type: text/plain` returns 415 |
| V07-HTTP-008 | sse-accept-501 | MCP-HTTP | `POST /mcp` with `Accept: text/event-stream` returns 501 with JSON-RPC error `code=-32000` |
| V07-HTTP-009 | unknown-method-32601 | MCP-HTTP | `POST /mcp` with `{"method":"unknown"}` returns 200 with `error.code=-32601` |
| V07-HTTP-010 | info-endpoint-200 | MCP-HTTP | `GET /info` returns 200 with body containing `transport=http` and `ontologies` array (may be empty) |
| V07-HTTP-011 | root-liveness-200 | MCP-HTTP | `GET /` returns 200 with plain-text banner |
| V07-HTTP-012 | reasoner-serialization | MCP-HTTP | 2 concurrent `POST /mcp tools/call ontology_check_consistency` requests both return 200; neither response body contains `ConcurrentModificationException` or `InconsistentOntologyException` |
| V07-HTTP-013 | reasoner-backpressure-500 | MCP-HTTP | 1 in-flight reasoner call + 2nd reasoner call → 2nd response is `500 Internal Server Error` with `error.code=-32000` and `message` containing `"reasoner executor saturated"` |
| V07-HTTP-014 | non-reasoner-parallelism | MCP-HTTP | 8 concurrent `POST /mcp tools/call ontology_list` requests all complete in ≤ 1.5× single-request duration (parallelism verified) |
| V07-HTTP-015 | shutdown-banner | MCP-HTTP | Server prints `Listening on http://{host}:{port}` banner to stderr on successful bind; banner format matches `^Listening on http://[^:]+:[0-9]+$` |
| V07-HTTP-016 | shutdown-cleanup | MCP-HTTP | `kill $SERVER_PID` triggers exit within 5 seconds with exit code 0 |
| V07-HTTP-017 | port-conflict-78 | MCP-HTTP | `java -jar owl4agents.jar mcp --readonly --transport=http --port=1` (privileged port, expected to fail) prints `port 127.0.0.1:1 already in use` to stderr (single line) and exits with code 78 |
| V07-CLI-001 | http-launch | CLI process | `java -jar owl4agents.jar mcp --readonly --transport=http --port=0` starts, prints banner, accepts `POST /mcp` requests, exits 0 on `kill` |
| V07-CLI-002 | transport-validation | CLI process | `java -jar owl4agents.jar mcp --readonly --transport=invalid` exits non-zero with deterministic validation error |
| V07-CLI-003 | mcp-config-http | CLI process | `node tools/npm/bin/owl4agents.js mcp-config --client=http` exits 0; stdout parses as JSON; contains `mcpServers.owl4agents.url` ending in `/mcp` |
| V07-CLI-004 | npm-launcher-stdio-regression | CLI process | `node tools/npm/test/launcher.test.js` passes; `MCP process should exit 0 after stdin closes` still passes (stdio behavior unchanged) |
| V07-PARITY-001 | stdio-vs-http-tools-list | CLI/HTTP | `tools/list` response over stdio and HTTP have same length, same names, same schemas, same ordering |
| V07-PARITY-002 | stdio-vs-http-tools-call | CLI/HTTP | `tools/call ontology_list` response `result.content[0].text` is field-equivalent between stdio and HTTP (excluding `serverInfo.version`) |
| V07-PARITY-003 | notifications-parity | CLI/HTTP | `notifications/initialized` returns no output over stdio and `202 Accepted` with empty body over HTTP |
| V07-PARITY-004 | error-parity | CLI/HTTP | Adapter exceptions surface as JSON-RPC `-32603` to stdout over stdio and as `500` + same JSON body over HTTP |
| V07-READONLY-001 | mcp-server-integration | JUnit | All 6 existing `McpServerIntegrationTest` scenarios still pass (stdio regression) |
| V07-READONLY-002 | mcp-acceptance | JUnit | `McpAcceptanceTest` ground truth tool count is recorded in the CHANGELOG v0.7.0 entry |
| V07-ADAPTER-001 | eager-init | JUnit | `McpServerAdapterTest.constructorInitializesAllServicesEagerlyInDependencyOrder` (TC-26) passes: all 7 services non-null after construction in documented order; no `getXxxService()` lazy-init methods exist |
| V07-BUILD-001 | shadow-jar | Build | `./gradlew :modules:ontology-cli:shadowJar` produces `build/modules/ontology-cli/libs/owl4agents.jar` |
| V07-BUILD-002 | zero-new-deps | Build | `modules/ontology-mcp` has no new Maven coordinates beyond v0.6.0 (only `com.sun.net.httpserver` from JDK, no HTTP framework dependencies added) |
| V07-BUILD-003 | transport-isolation | Build | `McpServerAdapter` no longer references `com.sun.net.httpserver`; only `HttpMcpServer` imports it |
| V07-REL-001 | release | Build/test | `./gradlew test` passes including v0.1 through v0.6 regression + v0.7 gates |
| V07-REL-002 | acceptance | Report | Timestamped acceptance report lists all required scenarios, commands, durations, results, and failures |

## Required Fixtures

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| pizza-50 | test/fixtures/v0.6/question-sets/pizza-50.jsonl | yes | Pizza ontology 50-question benchmark set (used for V07-HTTP-004) |

Missing required fixtures MUST make the gate FAIL, not SKIP.

## Optional Stress Test (acceptance gate 8.5 only)

| Gate ID | Category | Tag | Required Evidence |
| --- | --- | --- | --- |
| V07-STRESS-001 | reasoner-throughput-quantification | `@Tag("stress")` | 10 concurrent reasoner HTTP requests complete sequentially with median response time ≤ 1.5× single-request duration; no 5xx. Per-request durations logged for v0.7.1 design review. Executed via `./gradlew test --tests "*HttpMcpServerTest*concurrentReasonerStressTestQuantifiesSingleThreadBackpressure"`. |

## PASS Verdict Invalid Without

A v0.7 report claiming PASS is invalid if it omits evidence for:

- HTTP transport initialize / tools/list / tools/call round trip on a real subprocess
- All 3 HTTP endpoints (`POST /mcp`, `GET /info`, `GET /`) returning 200
- HTTP error matrix: 400 / 405 / 415 / 500 / 501 with documented error codes
- JSON-RPC error matrix: `-32601` unknown method, `-32700` parse error
- Reasoner backpressure 500 path (V07-HTTP-013) — this is a design-level SLA, not optional
- Non-reasoner parallelism (V07-HTTP-014) — proves 8-worker pool is actually parallel
- stdio/HTTP field parity for tools/list and tools/call (V07-PARITY-001, V07-PARITY-002)
- stdio regression: all 6 `McpServerIntegrationTest` scenarios still pass (V07-READONLY-001)
- npm launcher stdio regression: `MCP process should exit 0 after stdin closes` still passes (V07-CLI-004)
- Eager init: 7 services initialized in `McpServerAdapter` constructor (V07-ADAPTER-001)
- Build: shadowJar produces owl4agents.jar (V07-BUILD-001)
- Zero new dependencies: no HTTP framework added (V07-BUILD-002)
- Transport isolation: `com.sun.net.httpserver` not in `McpServerAdapter` (V07-BUILD-003)
- Fixture integrity: pizza-50.jsonl still passes V06-FIX-001
- Release gate: `./gradlew test` passes with v0.7 gates (V07-REL-001)
- Documented SLA boundary: HTTP transport supports up to 108 concurrent non-reasoner requests and 1 concurrent reasoner request (spec.md §"Bounded worker pool with explicit saturation semantics")
- Known limitation declared: v0.7.0 surfaces 500 on > 5 concurrent sustained reasoner calls per minute; v0.7.1+ may add per-ontology reasoner pool

## Version

Server MUST advertise `serverInfo.version = "0.7.0"` on `initialize` handshake. Build
artifacts MUST match the version recorded in `CHANGELOG.md` v0.7.0 entry.

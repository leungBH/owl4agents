# v0.8 Acceptance Contract

This contract defines the v0.8.0 acceptance gates covering the Streamable HTTP
transport upgrade (MCP `GET /mcp` SSE, session management, content
negotiation, backpressure, reasoner serialization across paths, Trae IDE
smoke, asset alignment, and the no-new-dependency invariant).

A v0.8 report claiming PASS MUST include evidence for every required gate
listed below and MUST additionally pass the v0.7.1 regression gates listed
in `test/contracts/v07-acceptance/contracts.md` (HTTP transport no-regression).

## Required Report Path

```text
reports/acceptance/YYYY-MM-DD-HHMMSS-v0.8-acceptance-report.md
```

The filename MUST match the pattern
`^\d{4}-\d{2}-\d{2}-\d{6}-v0\.8-acceptance-report\.md$`. The report MUST
follow `test/contracts/acceptance-report/contracts.md` and this contract.

## Required Gates

### Streamable HTTP / SSE (new in v0.8)

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-SSE-001 | sse-open-stream | MCP-HTTP | `GET /mcp` with `Accept: text/event-stream` and a valid `Mcp-Session-Id` returns 200 with `Content-Type: text/event-stream` and a first `ready` frame echoing the session id |
| V08-SSE-002 | sse-no-session-400 | MCP-HTTP | `GET /mcp` without `Mcp-Session-Id` returns 400 with a plain-text body (v0.8 requires `initialize` first) |
| V08-SSE-003 | sse-bad-uuid-400 | MCP-HTTP | `GET /mcp` with a non-UUID `Mcp-Session-Id` returns 400 |
| V08-SSE-004 | sse-bad-accept-405 | MCP-HTTP | `GET /mcp` without `Accept: text/event-stream` returns 405 with `Allow: GET, POST` |
| V08-SSE-005 | sse-resume-ack | MCP-HTTP | `GET /mcp` with `Last-Event-ID: <n>` (non-negative) writes a single `message` event with `data: {"resumed":true,"lastEventId":<n>}` and closes the stream |
| V08-SSE-006 | sse-heartbeat | MCP-HTTP | An open SSE stream emits at least one `:` comment frame within `2 * heartbeatInterval` seconds (RFC 8895 keep-alive) |
| V08-SSE-007 | sse-cap-503 | MCP-HTTP | With `maxSseConnections=N`, the (N+1)th open stream returns 503 with `Retry-After: 30`; the cap is enforced atomically so concurrent opens cannot overshoot |
| V08-SSE-008 | sse-shutdown-cleanup | MCP-HTTP | `stop()` closes every open SSE stream and the shutdown latch counts down within 5 seconds; `stop()` is idempotent |
| V08-SSE-009 | sse-info-fields | MCP-HTTP | `GET /info` reports `version=0.8.0`, `max_sse_connections`, and `active_sse_connections` (>= 0) |

### Session Management

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-SES-001 | session-uuid-v4 | MCP-HTTP | A `POST /mcp initialize` returns 200 with an `Mcp-Session-Id` response header whose value is a UUID v4 (version nibble = 4, variant = RFC 4122) |
| V08-SES-002 | session-uuid-v1-rejected | MCP-HTTP | A `POST /mcp` with `Mcp-Session-Id: <UUID v1>` returns 400 (non-v4 rejected) |
| V08-SES-003 | session-touch | MCP-HTTP | Every request that mentions a session id updates `lastAccessAt`; an idle session past the TTL is treated as expired on the next access |
| V08-SES-004 | session-sweeper | MCP-HTTP | The background sweeper removes expired sessions on its tick; `McpSessionManager.sweepExpired(ttl)` is unit-testable and returns the number of removed sessions |
| V08-SES-005 | session-ttl-min-1 | MCP-HTTP | `HttpMcpServer` constructor rejects `sessionTtl < Duration.ofMinutes(1)` with `IllegalArgumentException` |

### Content Negotiation

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-NEG-001 | post-json-accept-json | MCP-HTTP | `POST /mcp` with `Content-Type: application/json` and `Accept: application/json` returns 200 with JSON body (v0.7.1 behavior preserved) |
| V08-NEG-002 | post-json-accept-sse-no-stream | MCP-HTTP | `POST /mcp` with `Content-Type: application/json` and `Accept: text/event-stream` but no open SSE stream returns 200 + JSON body and `X-Streamable-Http-Fallback: application/json` (or `no-open-stream` if a session exists) |
| V08-NEG-003 | post-json-accept-sse-with-stream | MCP-HTTP | `POST /mcp` with an open SSE stream on the session returns 200 with empty body; the JSON-RPC response is written as a `message` event on the stream |
| V08-NEG-004 | post-text-plain-415 | MCP-HTTP | `POST /mcp` with `Content-Type: text/plain` returns 415 (v0.7.1 behavior preserved) |
| V08-NEG-005 | post-accept-406 | MCP-HTTP | `POST /mcp` with an `Accept` value other than `application/json`, `*/*`, or `text/event-stream` returns 406 |

### Backpressure and Reasoner Serialization

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-BP-001 | non-reasoner-parallelism | MCP-HTTP | 8 concurrent `POST /mcp tools/call ontology_list` complete in <= 1.5x the single-request baseline (v0.7.1 TC-27 still holds) |
| V08-BP-002 | reasoner-serialization | MCP-HTTP | 2 concurrent `POST /mcp tools/call ontology_check_consistency` requests both return 200; neither body contains `ConcurrentModificationException` or `InconsistentOntologyException` (single-thread reasoner executor; v0.7.1 TC-28 still holds) |
| V08-BP-003 | reasoner-sse-does-not-block-heartbeat | MCP-HTTP | During a long-running reasoner call dispatched over SSE, the heartbeat scheduler continues to emit comment frames on the same stream within `2 * heartbeatInterval` seconds |

### HTTP Transport (v0.7.1 Regression)

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-REG-001 | initialize-handshake | MCP-HTTP | `POST /mcp initialize` returns 200 with `protocolVersion=2025-06-18` and `serverInfo.version=0.8.0` (regression of V07-HTTP-001 with bumped version) |
| V08-REG-002 | tools-list | MCP-HTTP | `POST /mcp tools/list` returns 200; tool count matches the v0.6 / v0.7 ground truth recorded in CHANGELOG (regression of V07-HTTP-002) |
| V08-REG-003 | tools-call-ontology-list | MCP-HTTP | `POST /mcp tools/call ontology_list` returns 200 with `content[0].text` containing the `ontologies` field (regression of V07-HTTP-003) |
| V08-REG-004 | malformed-json-400 | MCP-HTTP | `POST /mcp` with non-JSON body returns 400 with JSON-RPC error `code=-32700` (regression of V07-HTTP-005) |
| V08-REG-005 | non-post-405 | MCP-HTTP | Plain `GET /mcp` (no `Accept: text/event-stream`) returns 405 with `Allow: GET, POST` (v0.8 widens the Allow header to GET, POST) |
| V08-REG-006 | info-endpoint-200 | MCP-HTTP | `GET /info` returns 200 with `version=0.8.0` and `ontologies` array (regression of V07-HTTP-010) |
| V08-REG-007 | root-liveness-200 | MCP-HTTP | `GET /` returns 200 with the v0.8 banner that lists `GET /mcp (SSE)` in the available endpoints |
| V08-REG-008 | reasoner-parity-across-paths | MCP-HTTP | A reasoner-using tool call dispatched over plain HTTP and the same tool call dispatched over SSE return 200 with the same `result.content[0].text` JSON (modulo envelope) |

### Trae IDE Smoke

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-TRAE-001 | trae-config-fixture | CLI process | `examples/agent-mcp/configs/trae-mcp-config.json` is committed, is field-level identical to the generator's default for `mcp-config --client=trae`, and contains `mcpServers.owl4agents.url` ending in `/mcp` |
| V08-TRAE-002 | trae-sse-handshake | MCP-HTTP | A Trae-style handshake succeeds end-to-end: `POST /mcp initialize` returns 200 with `Mcp-Session-Id`; subsequent `GET /mcp` with that session id returns 200 + text/event-stream; a `POST /mcp tools/call` with the same session id returns 200 with the JSON-RPC response written to the stream |
| V08-TRAE-003 | trae-help-mentions-streamable | CLI process | `node tools/npm/bin/owl4agents.js mcp-config --client=trae --help` mentions the Streamable HTTP transport and the requirement that the URL end in `/mcp` |

### Asset Alignment and Versioning

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-VER-001 | server-version | Build | `McpServerAdapter.SERVER_VERSION == "0.8.0"`; `GET /info` and the `initialize` response both report `0.8.0` |
| V08-VER-002 | cli-version | Build | `tools/npm/package.json` `version` field is `0.8.0` |
| V08-VER-003 | gradle-version | Build | `build.gradle.kts` `project.version` is `0.8.0` |
| V08-VER-004 | systemd-description | Build | `examples/agent-mcp/systemd/owl4agents.service` `Description=` reads `(v0.8.0)`; the operator README is updated to call out the unit re-install step |
| V08-VER-005 | changelog | Build | `CHANGELOG.md` has a `## [0.8.0]` entry at the top that records Added (SSE, Streamable HTTP, new CLI options, Trae IDE smoke), Fixed (the 405/501 errors that blocked Trae), and Changed (version bump 0.7.1 -> 0.8.0) |
| V08-VER-006 | ci-version-alignment | Build | A CI assertion (in `.github/workflows/ci.yml` or a sibling workflow) fails the build when any of the strings in V08-VER-001 .. V08-VER-005 disagree |

### Build and Dependency Gates

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V08-BLD-001 | shadow-jar | Build | `./gradlew :modules:ontology-cli:shadowJar` produces `build/modules/ontology-cli/libs/owl4agents.jar` |
| V08-BLD-002 | no-new-deps | Build | `modules/ontology-mcp` and `modules/ontology-cli` have no new Maven coordinates beyond v0.7.1; only the JDK's `com.sun.net.httpserver` is used for HTTP, no framework is added |
| V08-BLD-003 | transport-isolation | Build | `McpServerAdapter` still does NOT import `com.sun.net.httpserver`; only `HttpMcpServer` does |
| V08-BLD-004 | full-test-suite | Build/test | `./gradlew test` passes including v0.1 through v0.7 regression and the new v0.8 SSE / session / content-negotiation suites |

## Required Fixtures

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| pizza-50 | test/fixtures/v0.6/question-sets/pizza-50.jsonl | yes | Pizza ontology 50-question benchmark set (regression for V08-REG-003 and TC-27 parallelism) |
| trae-mcp-config | examples/agent-mcp/configs/trae-mcp-config.json | yes | Trae IDE MCP config fixture used by V08-TRAE-001 and by the example-validator test |

## Required JUnit Tests (server side)

| Class | Test | Covers |
| --- | --- | --- |
| `HttpMcpServerSseTest` | `GetAcceptNegotiation.*` | V08-SSE-001 .. V08-SSE-004 |
| `HttpMcpServerSseTest` | `SseStreamLifecycle.resumeAckClosesAfterOneEvent` | V08-SSE-005 |
| `HttpMcpServerSseTest` | `Heartbeat.*` | V08-SSE-006 |
| `HttpMcpServerSseTest` | `SseConnectionCap.overCapReturns503` | V08-SSE-007 |
| `HttpMcpServerSseTest` | `Shutdown.*` | V08-SSE-008 |
| `HttpMcpServerSseTest` | `InfoEndpoint.infoIncludesSseConfig` | V08-SSE-009 |
| `HttpMcpServerSseTest` | `PostFallback.*`, `PostSessionId.*` | V08-NEG-002, V08-NEG-003, V08-SES-001, V08-SES-002 |
| `McpSessionTest` | `*` | V08-SES-003, V08-SES-005 |
| `McpSessionManagerTest` | `sweepExpiredRemovesOldSessions` and concurrency | V08-SES-004 |
| `HttpMcpServerTest` | regression scenarios | V08-REG-001 .. V08-REG-008 |
| `McpConfigCommandTest` | `traeFixtureHasMcpUrl` and `traeCliHelpMentionsStreamableHttp` | V08-TRAE-001, V08-TRAE-003 |

## PASS/FAIL/SKIP/BLOCKED/DEFERRED Rules

Inherit the rules from `test/contracts/acceptance-report/contracts.md` with
these v0.8-specific additions:

- A gate that exercises SSE behavior MUST include the captured `curl -N`
  output (or a JUnit transcript) showing the `event:`, `data:`, and
  `id:` lines, not just a status code.
- A gate that exercises `stop()` / shutdown MUST report the wall-clock
  duration from SIGTERM to exit, and the duration MUST be <= 5 seconds.
- A gate that exercises the cap (V08-SSE-007) MUST report the actual
  concurrent request count that produced the 503, to prove the atomic
  claim is not just a no-op.

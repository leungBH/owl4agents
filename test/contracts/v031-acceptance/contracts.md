# v0.3.1 Acceptance Contract

This contract defines the v0.3.1 usability and release-hardening acceptance gates.
A v0.3.1 report claiming PASS MUST include evidence for every required gate listed below.

## Required Gates

| Gate ID | Category | Interface | Required Evidence |
| --- | --- | --- | --- |
| V031-SETUP-001 | setup | CLI real process | `owl4agents setup --check` exits 0 when Java 22, Gradle wrapper, source layout, writable workspace, npm launcher, and runtime jar are present |
| V031-SETUP-002 | setup | CLI real process | `owl4agents setup --init --home <path>` initializes workspace and imports Pizza and v0.3 golden ontology; idempotent rerun reports existing resources |
| V031-SETUP-003 | setup | CLI real process | `owl4agents setup --check --dry-run` reports planned actions without modifying files |
| V031-SMOKE-001 | smoke | CLI real process | `owl4agents smoke --workspace <tmp>` imports fixtures, lists ontologies, summarizes at least one, lists reasoners, verifies one supported and one out_of_scope claim; every step has PASS or FAIL |
| V031-SMOKE-002 | smoke | CLI real process | Smoke fails on placeholder payloads, empty success output, or missing required fixtures |
| V031-SMOKE-003 | smoke | CLI real process | `owl4agents smoke` defaults to temporary workspace; `--workspace <path>` uses explicit path |
| V031-MCP-CONFIG-001 | mcp-config | CLI real process | `owl4agents mcp-config --client claude` prints valid JSON with command, args, OWL4AGENTS_HOME; no write flags |
| V031-MCP-CONFIG-002 | mcp-config | CLI real process | `owl4agents mcp-config --client generic` prints valid JSON |
| V031-MCP-CONFIG-003 | mcp-config | CLI real process | `owl4agents mcp-config --client cursor` prints valid JSON |
| V031-MCP-CONFIG-004 | mcp-config | CLI real process | `owl4agents mcp-config --client unknown` exits nonzero with supported-client diagnostic |
| V031-MCP-CONFIG-005 | mcp-config | CLI real process | `owl4agents mcp-config --client claude --workspace-home <path>` propagates workspace in env |
| V031-MCP-CONFIG-006 | mcp-config | CLI real process | `owl4agents mcp-config --client generic --out <path>` writes to file; default writes stdout only |
| V031-MCP-CONFIG-007 | mcp-config | structural test | Generated JSON contains no placeholders, no empty command, no --allow-write |
| V031-MCP-SMOKE-001 | mcp-readiness | child process | MCP readonly starts and returns initialize or tools/list before 30s timeout |
| V031-MCP-SMOKE-002 | mcp-readiness | CLI | `--timeout <seconds>` validation rejects non-positive values |
| V031-LAUNCH-001 | launcher | Node process | `node npm/bin/owl4agents.js --version` prints `0.3.1`, exits 0 |
| V031-LAUNCH-002 | launcher | Node process | setup, smoke, mcp-config forwarded to Java runtime with exact arguments |
| V031-LAUNCH-003 | launcher | Node process | Missing runtime produces deterministic exit and diagnostic mentioning shadowJar |
| V031-CI-001 | ci | CI | Gradle + Node + launcher smoke workflow passes using public fixtures only |
| V031-RELEASE-001 | release | CI/script | Release jar, checksum, and release notes are generated; jars remain uncommitted |
| V031-REGRESS-001 | regression | CLI + JUnit | v0.1, v0.2, v0.2.1, and v0.3 regression gates still pass |
| V031-REGRESS-002 | regression | JUnit | V03AcceptanceSuite remains present and green |
| V031-REPORT-001 | acceptance | report | Timestamped v0.3.1 report follows acceptance-report contracts.md |

## Required Fixtures

| Fixture ID | Path | Required | Purpose |
| --- | --- | --- | --- |
| pizza.owl | test/corpus/smoke/pizza.owl | yes | onboarding import and summary |
| v0.3-golden | test/corpus/golden/v0.3-claim-verification.owl | yes | v0.3 claim verification smoke |
| claim-smoke-supported | test/fixtures/v0.3/claim-smoke-supported.json | yes | supported verdict smoke |
| claim-real-out-of-scope | test/fixtures/v0.3/claim-real-out-of-scope.json | yes | out_of_scope verdict smoke |

Missing required fixtures MUST make the gate FAIL, not SKIP.

## PASS Verdict Invalid Without

A v0.3.1 report claiming PASS is invalid if it omits evidence for:
- setup (check, init, dry-run)
- launcher (version, forwarding, missing runtime)
- mcp-config (generic, claude, cursor, unknown, workspace, out)
- smoke (fixture import, list, summary, reasoners, claim verification)
- MCP readiness (mandatory — smoke --mcp-readiness must receive initialize or tools/list within timeout)
- CI
- release assets
- regression (v0.1 through v0.3)
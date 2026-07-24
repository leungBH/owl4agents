# Smart Home Tool Call Validation — Research Baseline (v0.8.7)

**Captured**: 2026-07-21
**Git commit**: `2a5e08bb0c9df6bde9ef9ebb3e56d8d72a70d6e6` (test(v0.8.6): add deployment smoke test and real ontology integration test)
**Baseline version**: v0.8.7 (post section 1 of v087-tool-call-validation OpenSpec change)

## Purpose

This document records the complete environmental and test baseline for the v0.8.7 smart home tool call validation work. It is the first milestone anchor for the smart-home-study research side — all subsequent experiment results should reference this baseline.

Per OA-002 (work list item), this baseline captures:
- Software stack versions (Java, Gradle, Jena, OWL API, reasoners, OS)
- JVM parameters
- Git commit hash
- Complete test results snapshot

## Software Stack

| Component | Version | Source |
|---|---|---|
| owl4agents | 0.8.7 | `build.gradle.kts` root `version` |
| Java | 22 (toolchain) | `build.gradle.kts` `JavaLanguageVersion.of(22)` |
| Gradle | 8.14 | `gradle/wrapper/gradle-wrapper.properties` |
| Apache Jena | 5.3.0 | `ontology-query/build.gradle.kts` `apache-jena-libs:5.3.0` |
| OWL API | 5.1.20 | `ontology-reasoner/build.gradle.kts` `owlapi-distribution:5.1.20` |
| HermiT | 1.4.5.519 | `ontology-reasoner/build.gradle.kts` |
| ELK | 0.6.0 | `ontology-reasoner/build.gradle.kts` (liveontologies) |
| Openllet | 2.6.5 | `ontology-reasoner/build.gradle.kts` |
| Caffeine | 3.1.8 | `ontology-reasoner/build.gradle.kts` (LRU cache) |
| JUnit | 5.12.2 | `build.gradle.kts` `junit-bom:5.12.2` |
| picocli | (existing) | `ontology-cli/build.gradle.kts` |
| JSON Schema validator | 1.5.2 (networknt) | `ontology-toolcall/build.gradle.kts` (v0.8.7 new) |

## Operating Environment

| Property | Value |
|---|---|
| OS | Windows 11 (PowerShell 5) |
| Architecture | x86_64 |
| Timezone | Asia/Shanghai |
| User home | `C:\Users\34688` |
| owl4agents home | `C:\Users\34688\.owl4agents` |
| Project root | `D:\owl4agents` |

## JVM Parameters

### Test runs (`gradlew test`)
```
-Xmx4g
-Dcorpus.fixtures=<root>/test/corpus
-Dowl4agents.version=0.8.7
```

### Production shadow jar (HTTP MCP server)
```
-Xmx4g
-XX:MaxMetaspaceSize=512m
-XX:+ExitOnOutOfMemoryError
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=./owl4agents-heapdump.hprof
```

### Manifest attributes (shadow jar)
```
Implementation-Version: 0.8.7
JVM-Args: -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError
```

## Module Inventory (post v0.8.7 section 1)

| Module | Purpose | New in v0.8.7? |
|---|---|---|
| `ontology-core` | ServiceResult, ErrorCode, data models | No |
| `ontology-owlapi` | OWL API wrappers, OntologyCache, EntitySignatureCache | No |
| `ontology-query` | Jena Model, SPARQL execution | No |
| `ontology-retrieval` | Entity lookup, context retrieval | No |
| `ontology-storage` | Workspace persistence, CatalogStore | No |
| `ontology-reasoner` | HermiT/ELK/Openllet, TemporaryOntologyFactory, ReasonerCallWrapper | No |
| `ontology-validation` | ClaimVerificationService, ClaimWorkflowService, ConsistencyAnalysisService | No |
| `ontology-shacl` | SHACL validation service, Shape Registry | **Yes** |
| `ontology-overlay` | TransientOntologyOverlayService, dynamic state parsing | **Yes** |
| `ontology-toolcall` | ToolCallCandidate, ToolContract, validation pipeline | **Yes** |
| `ontology-cli` | picocli commands, shadow jar main | No (version bump only) |
| `ontology-mcp` | MCP server adapter, HTTP transport, readonly tool registry | No (write mode in section 2) |
| `ontology-distribution` | Acceptance tests, deployment smoke tests | No |
| `ontology-benchmark` | Benchmark harness, QA evaluation | No |

## Test Baseline

> **Note**: Captured 2026-07-21 by task 1.3 (OA-003). `clean` was skipped because the running service (PID 13864, port 8081) holds a file lock on `owl4agents.jar`; `--rerun-tasks` ensures all tests re-run from compiled state.

### Gradle unit tests
- Command: `./gradlew buildVerification --rerun-tasks` (skipped `clean` due to running service jar lock)
- Total tests: **1230**
- Passed: **1230**
- Failed: **0**
- Skipped: **16** (intentional — deployment/integration tagged tests excluded from default suite)
- Errors: **0**
- Test files: 385

### Gradle integration tests
- Command: `./gradlew integrationTest`
- Status: _not run in this baseline (deferred to section 9 acceptance)_

### Gradle deployment tests
- Command: `./gradlew deploymentTest`
- Status: _not run in this baseline (deferred to section 9 acceptance)_

### NPM launcher tests
- Command: `node tools/npm/test/launcher.test.js`
- Total tests: **29**
- Passed: **29**
- Failed: **0**

### Version consistency
- `McpServerAdapter.SERVER_VERSION`: `0.8.7` (via `owl4agents.version` system property under gradle test; via manifest `Implementation-Version` under shadow jar)
- CLI `--version`: `0.8.7` (picocli `@Command(version=...)` annotation)
- NPM package version: `0.8.7` (`tools/npm/package.json`)

## Ontology Corpus

| Ontology | Size | Classes | Source |
|---|---|---|---|
| pizza | ~100KB | ~100 | `test/corpus/pizza.owl` |
| ec-disjoint-witness | small | <20 | `test/corpus/` |
| ec-dw | small | <20 | `test/corpus/` |
| HPO | 74.2MB | ~32K | `~/.owl4agents/workspaces/default/ontologies/hpo/source/hp.owl` |
| Mondo | 236.8MB | ~30K | `~/.owl4agents/workspaces/default/ontologies/mondo/source/mondo.owl` |
| skill2owl | small | <50 | `~/.owl4agents/workspaces/default/ontologies/` |

## v0.8.7 Scope Summary

This baseline anchors the first research milestone (HomeBench golden subset integration). The v0.8.7 change (`v087-tool-call-validation`) delivers:

- **MCP write mode**: `ontology_import` tool, `--readonly=false` opt-in, 50MB size limit, path traversal protection
- **SHACL validation**: Jena SHACL integration, Shape Registry, 3 readonly MCP tools, CLI `shacl-validate`/`shacl-register`
- **Transient overlay**: dynamic ABox injection, RDF state parsing, TOCTOU protection, snapshot isolation
- **Tool call model**: ToolCallCandidate, ToolContract, decision enum, validation report, JSON Schema pre-validation
- **Claim decomposition**: 6-type semantic claim splitting, batch verification reuse, OWL/SHACL division of labor
- **Validation pipeline**: 10-stage pipeline, short-circuit strategy, risk policy, 5 readonly MCP tools, CLI `toolcall validate`
- **Reliability**: reasoner isolation option (independent JVM), cache correctness, snapshot isolation

See [tasks.md](../../openspec/changes/v087-tool-call-validation/tasks.md) for the full 83-task breakdown.

## Reproducibility

To reproduce this baseline:
1. Check out git commit `2a5e08bb0c9df6bde9ef9ebb3e56d8d72a70d6e6`
2. Apply v0.8.7 section 1 changes (version bump + 3 new empty modules)
3. Run `./gradlew clean buildVerification` — record test counts
4. Run `./gradlew :modules:ontology-cli:shadowJar` — record jar MD5
5. Run `node tools/npm/test/launcher.test.js` — record pass/fail
6. Run `java -jar build/modules/ontology-cli/libs/owl4agents.jar --version` — must output `0.8.7`
7. Record all outputs in this document

## Change Log

| Date | Change |
|---|---|
| 2026-07-21 | Initial baseline captured (post section 1 of v0.8.7 change) |

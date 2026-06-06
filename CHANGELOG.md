# Changelog

## 0.4.0 - 2026-06-05

### Added

- Added `examples/` top-level directory with runnable example packs for claim verification, pizza reasoning, MCP agent integration, and biomedical grounding.
- Added `examples/claim-verification/` demonstrating supported, contradicted, unknown, and out_of_scope claim verification.
- Added `examples/pizza-reasoning/` demonstrating ontology import, summary, class context, classification, and property context.
- Added `examples/agent-mcp/` demonstrating MCP client configuration, startup, tool list, and sanitized tool-call transcript.
- Added `examples/biomedical-grounding/` demonstrating biomedical grounding using project-owned golden fixture.
- Added `example.yaml` manifests for each required example pack with commands, fixtures, expected outputs, and attribution.
- Added `test/corpus/golden/v0.4-biomedical-grounding.owl` — project-owned biomedical golden ontology with disease hierarchy, phenotype, organ system, object/data properties, equivalent class, and disjointness axioms.
- Added `test/fixtures/v0.4/` claim fixtures for biomedical grounding examples.
- Added `test/contracts/example-demo-packs/contracts.md` — public contract for example IDs, manifest schema, fixture policy, and sanitized output policy.
- Added `test/contracts/example-validation/contracts.md` — public contract for example validation, child-process execution, JSON field assertions, MCP timeout, and crash/placeholder rejection.
- Added `test/contracts/example-output-schema/contracts.md` — public contract defining schema/field assertions for each example output.
- Added `test/contracts/v04-acceptance/contracts.md` — public contract defining v0.4 acceptance gates, required fixtures, and PASS invalidation rules.
- Added JUnit example validation tests: `ExampleValidationTest` (manifest, fixture, sanitization, doc drift), `ExampleExecutionTest` (CLI child-process execution, JSON field assertions), `McpExampleValidationTest` (MCP startup, tools/list, transcript validation).
- Added CI workflow steps for v0.4 example validation (claim verification, pizza reasoning, biomedical grounding, MCP readiness).

### Changed

- Updated README with v0.4 "Try in 3 minutes" flow, example showcase table, and MCP configuration link to `examples/agent-mcp/`.
- Updated README roadmap to mark v0.4 delivered items and refresh v0.5+ based on example-first strategy.
- Updated `test/corpus/README.md` with v0.4 biomedical grounding fixture attribution and claim fixture entries.
- Updated `.gitignore` to explicitly exclude `temp/examples/` generated outputs.
- Updated CI workflow with v0.4 example validation steps using npm launcher and public fixtures.

### Notes

- v0.4 examples are script-driven — no CLI `examples` or `demo` discovery command is provided. This is explicitly deferred to a future release.
- Example scripts use `node npm/bin/owl4agents.js` as the entry point — direct `java -jar` is not used due to the known Windows ACCESS_VIOLATION limitation.
- Expected outputs use schema/field assertions, not byte-for-byte full-output snapshots.
- Contradicted and unknown claim examples document reasoning prerequisites.
- The `research-context` example is deferred to v0.5 pending fixture license and size review.

All notable release changes for owl4agents are tracked here.

## 0.3.1 - 2026-06-05

### Added

- Added `setup --check` command validating Java, Gradle, source layout, workspace, npm launcher, and runtime jar.
- Added `setup --check --dry-run` behavior reporting planned actions without modifying files.
- Added `setup --init` command initializing workspace and importing Pizza and v0.3 golden ontology idempotently.
- Added `smoke` command running fixture import, ontology list, summary, reasoner list, classification, and claim verification.
- Added `mcp-config --client generic/claude/cursor` command generating MCP client JSON configuration.
- Added `mcp-config --workspace-home` and `--out` options for workspace propagation and file output.
- Added GitHub Actions CI workflow for build verification, launcher smoke tests, MCP readiness smoke, and release asset generation.
- Added sha256sum checksum generation and release notes artifact generation for release jar.
- Added v0.3.1 acceptance contract and required fixture definitions.
- Added fixture attribution documentation for Pizza, BFO, and benchmark ontologies.

### Changed

- Updated project version to 0.3.1 across Gradle, CLI, MCP server info, npm package, and npm launcher fallback.
- Updated README quick start, troubleshooting, MCP configuration, and roadmap for v0.3.1.
- Updated Windows MCP launcher path to use Java classpath mode for MCP startup.
- Deferred codex/codex-cli config templates until client naming and format stabilize.

### Fixed

- Fixed Apache-2.0 LICENSE boilerplate copyright placeholder.
- Fixed Windows MCP wrapper recursion by avoiding npm-wrapper round trips.
- Documented the Windows `java -jar` ACCESS_VIOLATION boundary and supported npm/Gradle alternatives.
- Fixed smoke workspace path doubling by normalizing paths ending in `workspaces/<name>`.

## 0.3.0 - 2026-06-04

### Added

- Added structured claim verification for agent-facing ontology checks.
- Added evidence grounding commands and MCP tools for evidence paths, counterexamples, unknown explanations, and missing entity detection.
- Added v0.3 golden ontology and claim fixtures covering supported, contradicted, unknown, out-of-scope, malformed, unsupported-type, and unknown-ontology scenarios.
- Added CLI/MCP parity coverage for claim verification behavior.

### Changed

- Updated README quick start, deployment notes, MCP integration examples, release checklist, and roadmap for v0.3.
- Hardened JSON output serialization for Java `Optional` fields and contract enum names.
- Aligned Gradle, CLI, MCP, and npm package versions to `0.3.0`.

### Fixed

- Fixed unsupported claim type handling to return `UNSUPPORTED_CLAIM_TYPE`.
- Fixed unknown ontology handling to return `ONTOLOGY_NOT_FOUND`.
- Fixed out-of-scope fixture coverage to use a genuinely undeclared entity.
- Fixed npm launcher release metadata and version fallback behavior.
- Resolved the v0.3 transitive subclass reasoning regression tracked as `DEFECT-008` in the acceptance report.

## 0.2.1 - 2026-06-02

### Added

- Added release hardening gates for build, test, launcher, and acceptance reporting.
- Added npm launcher smoke tests for version, help, runtime discovery, exit code semantics, and MCP startup path behavior.

### Fixed

- Stabilized Windows launcher and MCP wrapper behavior.
- Improved release documentation and local troubleshooting guidance.

## 0.2.0 - 2026-06-01

### Added

- Added reasoning expansion with reasoner selection, consistency checks, classification, realization, entailment, inferred facts, and reasoning reports.
- Added semantic deepening tools for restrictions, compatibility, property characteristics, datatype constraints, and individual assertions.
- Added expanded readonly MCP tool surface for v0.2 reasoning and semantic inspection.

## 0.1.0 - 2026-05-30

### Added

- Added local ontology import, catalog storage, summary extraction, entity search, SPARQL query support, QA context extraction, CLI entry points, and readonly MCP server basics.

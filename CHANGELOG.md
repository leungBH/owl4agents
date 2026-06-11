# Changelog

## 0.6.0 - 2026-06-11

### Added

- Research evaluation benchmark framework: `ExperimentConfig`, `ExperimentConfigParser`, `BenchmarkService`, `BenchmarkRunner`.
- QA evaluation service: `QaEvaluationService` with accuracy, false-support-rate, unresolved-rate, verification-coverage, and 4×4 confusion matrix.
- Context batch service: `ContextBatchService` with per-question evidence context and deterministic character budget truncation.
- Report generator: `BenchmarkReportGenerator` with Markdown/JSON output, multi-reasoner comparison, and static reasoner version map.
- Benchmark question set validator: `BenchmarkQuestionSetValidator` with Tier-1/Tier-2 two-tier validation, NL-only rejection, and missing-field diagnostics.
- NL claim validation helper: `NlClaimValidationHelper` for structural claim decomposition checks.
- CLI commands: `benchmark-run`, `eval-qa`, `context-batch`.
- v0.6 test fixtures: `pizza-50.jsonl`, `owl2bench-30.jsonl`, `out-of-scope-cross.jsonl` question sets.
- v0.6 benchmark configs: `pizza-small.yaml`, `owl2bench-medium.yaml`, `pizza-hallucination.yaml`.
- v0.6 acceptance contract: `test/contracts/v06-acceptance/contracts.md`.

### Changed

- Directory restructure: `agent-skills/` → `tools/skills/`, `scripts/` → `tools/scripts/`, `npm/` → `tools/npm/`, `bin/` → `tools/bin/`.
- `reports/` → `test/reports/`, `doc/` → `docs/`.
- Build output centralized to `build/modules/` (no more per-module `build/` dirs).
- `BenchmarkService.aggregateVerdict()` replaced fragile string matching with explicit switch on `AggregateAnswerStatus`.

## 0.5.0 - 2026-06-07

### Added

- Added batch claim verification workflow: `ClaimBatchInput`, `ClaimWorkflowService`, `ClaimWorkflowResult`, `AnswerVerificationReport`, and `AggregateAnswerStatus` models.
- Added `EvidenceContext` and `EvidenceContextBuilder` with deterministic `4 * maxContextTokens` character budget, source-order retention, `omittedEvidenceCount`, and `omittedClaimCount`.
- Added CLI commands: `verify-answer`, `evidence-context`, and `review-answer` with `--policy` support (strict, conservative, report-only).
- Added MCP tools: `ontology_verify_claims_batch`, `ontology_build_evidence_context`, `ontology_review_answer_claims` — all readonly, with CLI/MCP parity.
- Added review policies: `strict` (default), `conservative`, and `report-only`; unsupported policy values rejected deterministically.
- Added aggregate status rules: `invalid_input`, `contradicted`, `insufficient_evidence`, `out_of_scope`, `partially_verified`, and `verified` in priority order.
- Added optional claim handling: `required` defaults to `true`, optional claims do not dominate aggregate status.
- Added `ClaimBatchValidator` with deterministic field-level diagnostics for malformed input.
- Added v0.5 fixtures: supported, contradicted, unknown, out_of_scope, partially_verified, mixed, optional-claim, malformed, and v0.3-wrapped batches.
- Added `tools/skills/` directory with SOP packs: `owl4agents-claim-verification`, `owl4agents-evidence-grounded-answer`, `owl4agents-ontology-scope-check`.
- Added shared policy references: `verdict-policy.md`, `claim-batch-schema.md`, `evidence-citation-policy.md`, `refusal-scope-policy.md`, `answer-review-sop.md`.
- Added file-level MCP prompt templates: `verify-answer-with-ontology.md`, `ground-answer-with-evidence.md`, `explain-unknown-ontology-claim.md`.
- Added test contracts: `agent-claim-workflow`, `evidence-context-format`, `agent-skill-packs`, `mcp-workflow-prompts`.
- Added MCP schema tests for required ontology ID, structured claims, options, and error payloads.
- Added CLI/MCP parity tests for all required fixture scenarios.
- Added skill lint tests: placeholder text, missing references, stale commands, local paths, unsafe verdict policy, fabrication, portable paths, and fixture IDs.
- Added fixture-to-gate mapping documenting each required validation gate's input fixture.

### Changed

- Updated README with v0.5 quick start, workflow CLI examples, MCP workflow tools, agent skill pack links, and repository contents.
- Updated README roadmap to mark v0.5 delivered items and clarify that v0.5 requires structured claim input.
- Updated MCP tool registry with v0.5 batch workflow tool names and schemas.
- Updated `McpServerAdapter` with v0.5 workflow tool dispatch, serialization, and lazy service initialization.
- Deferred protocol-level MCP prompt listing (absent in current adapter); file-level templates committed as release assets.

### Notes

- v0.5 does not extract claims from free text — agents must submit structured claim batches with `answerId`, `claims[]`, and per-claim `id`, `type`, `subject`, `predicate`, `object`.
- The `4 * maxContextTokens` character budget controls evidence context truncation. All claim IDs and verdicts remain visible under truncation.
- Evidence context never fabricates evidence — empty evidence lists are returned when no evidence is available.
- The workflow service is readonly and does not mutate ontology state.
- v0.3 single-claim fixtures can be wrapped into v0.5 batches without verdict changes.

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
- The `research-context` example is deferred to a future release pending fixture license and size review.

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

# Changelog

All notable release changes for owl4agents are tracked here.

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

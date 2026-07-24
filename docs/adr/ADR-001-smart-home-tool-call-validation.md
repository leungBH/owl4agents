# ADR-001: Smart Home Tool Call Validation Architecture

**Status**: Accepted
**Date**: 2026-07-21
**Supersedes**: None
**Superseded by**: None

## Context

v0.8.7 introduces smart home tool call validation as a first-class capability of owl4agents. This scope merges the `mcp-write-import-tool` change with the 30+ tasks from `E:\owl4agents_代码改造工作清单_交付研发.md`, adding 6 new capabilities (MCP write mode, SHACL validation, transient overlay, tool call model, claim decomposition, validation pipeline) and 4 delta capabilities (readonly tools, CLI interface, reasoner runtime, cache governance).

The research side (smart-home-study) needs a Java-side runtime that can:
1. Accept OWL ontology imports via MCP (not just CLI)
2. Validate tool calls against OWL semantics + SHACL constraints
3. Reason over dynamic device state without polluting the static ontology cache
4. Enforce risk policies for high-risk actions (unlock_door, disable_alarm, etc.)

Eight architecture decisions must be made before implementation begins. These decisions bound the design space for v0.8.7 and all subsequent smart-home-related work.

## Decision

### Decision 1: General runtime vs research repository boundary

**Choice**: owl4agents is the **general semantic runtime**; smart-home-specific content (ontologies, SHACL shapes, tool contracts, benchmark datasets, experiment scripts) lives in the research repository.

**Rationale**: The runtime must remain domain-agnostic to serve future use cases beyond smart homes (e.g., biomedical workflow validation, IoT protocol checking). Embedding smart-home ontologies or experiment data in the core repository would couple release cadences and pollute the core with research artifacts.

**Boundary**:
- owl4agents provides: SHACL engine, overlay service, pipeline, data models, MCP/CLI interfaces
- Research repository provides: SAREF/WoT TD mappings, device ontologies, SHACL shape sets, tool contract JSON files, HomeBench golden subset, experiment configurations

### Decision 2: Dynamic state is not persisted

**Choice**: Dynamic ABox (device states, pending tool calls, current time) **never** writes to the original workspace. Dynamic state exists only in transient overlays that are released after validation.

**Rationale**: Workspaces contain static TBox (device classes, capability hierarchies) and semi-static ABox (registered devices, rooms, users). Dynamic state changes per tool call (door opens, temperature shifts). Persisting dynamic state would (a) corrupt the static knowledge base, (b) require cache invalidation on every state change, (c) break experiment reproducibility.

**Implementation**: `TransientOntologyOverlayService` creates isolated `OWLOntologyManager` instances (via `TemporaryOntologyFactory`); overlay handles are `AutoCloseable` and released after pipeline stage 8.

### Decision 3: SHACL Shapes cannot be uploaded by Agents

**Choice**: SHACL Shape Sets are **registered by operators** via CLI `shacl-register`. MCP tools accept only `shape_set_id` references; inline SHACL-SPARQL upload is rejected at the protocol layer.

**Rationale**: SHACL-SPARQL constraints are Turing-complete (SPARQL subqueries can execute arbitrary computation). Allowing agents to upload arbitrary SHACL-SPARQL would grant arbitrary code execution. The trust boundary must be established at registration time, not filtered at runtime.

**Enforcement**:
- CLI `shacl-register <id> <shapes.ttl>` — operator-controlled, writes to `~/.owl4agents/shapes/registry.json`
- MCP `ontology_validate_shacl` — accepts `shape_set_id` + `data_graph`, rejects `shapes_graph` parameter
- `ShapeRegistry.resolve(shapeSetId)` — SHA256 checksum verification on every load

### Decision 4: MCP defaults to readonly, supports opt-in write

**Choice**: Default `--readonly=true` (unchanged from v0.8.6). Write mode enabled via explicit `--readonly=false`. No environment variable fallback.

**Rationale**: Readonly is the safe default. Research-side scripts that need import capability explicitly opt in via CLI flag, making the security posture visible in process listings and launch scripts. Environment variables are invisible and can leak across subprocess boundaries.

**Warning on write-mode startup**: stderr outputs 4 lines documenting the security implications (write mode enabled, readonly guarantee no longer in effect, allowed import roots, size limit).

### Decision 5: Experiment data does not enter the core repository

**Choice**: HomeBench datasets, experiment outputs, golden subsets, and statistical analysis scripts live **only** in the research repository. owl4agents core repository contains no experiment data.

**Rationale**: Experiment data is large (device snapshots, 500-device scenarios), versioned separately from runtime code, and owned by the research team. Mixing data with code complicates licensing (data may have different usage restrictions than Apache-2.0 code) and bloats the git history.

**Exception**: Small fixture files (<10KB) used by unit tests (e.g., `fixtures/shacl/minCount.ttl`) are acceptable in the core repository because they test runtime behavior, not experiment outcomes.

### Decision 6: Module boundary — SHACL, Overlay, ToolCall are separate Gradle modules

**Choice**: Three new modules: `ontology-shacl`, `ontology-overlay`, `ontology-toolcall`. Dependency direction is unidirectional: `toolcall → shacl/overlay → core`.

**Rationale** (see design D1):
- SHACL is a general semantic validation layer (usable beyond tool calls)
- Overlay is a general dynamic knowledge layer (usable beyond tool calls)
- ToolCall is the orchestration layer that composes SHACL + Overlay + Claim decomposition

**Rejected alternatives**:
- (a) Single `ontology-toolcall` module containing SHACL + Overlay: rejected, SHACL is reusable
- (b) Overlay inside `ontology-reasoner`: rejected, reasoner would reverse-depend on SHACL consumers
- (c) SHACL inside `ontology-validation`: rejected, validation module doesn't depend on Jena SHACL

### Decision 7: Jena SHACL version aligned with existing Jena (5.3.0)

**Choice**: Use `org.apache.jena:jena-shacl:5.3.0` (included in `apache-jena-libs:5.3.0` already declared by `ontology-query`). No version upgrade, no TopBraid, no Python pySHACL subprocess.

**Rationale** (see design D2):
- Jena 5.3.0 SHACL implementation fully supports SHACL Core spec including SPARQL constraints
- Same Jena version avoids classpath conflicts with existing `SparqlExecutor`
- TopBraid rejected (license incompatibility, API divergence)
- pySHACL rejected (violates "core semantic runtime in Java" boundary, subprocess overhead, Python environment dependency)

### Decision 8: Reasoner isolation — in-process by default, independent JVM opt-in

**Choice**: Default in-process reasoner (via `ReasonerCallWrapper.callWithElkFallback`). Independent JVM worker (`IsolatedReasonerWorker`) enabled only when `owl4agents.reasoner.isolation.enabled=true` or `classCount > 50_000`.

**Rationale** (see design D15):
- In-process `Future.get(timeout)` + `Thread.interrupt` cannot reliably terminate HermiT/ELK native threads
- Independent JVM `destroyForcibly()` guarantees resource release on timeout
- Subprocess startup overhead (1-2s) is unacceptable for small ontologies (pizza 500 classes)
- Large ontologies (Mondo 30K classes) benefit from process isolation — a hung reasoner doesn't kill the main HTTP server

**v0.8.7 default**: in-process. Research-side can opt into isolation for large-scale experiments.

## Consequences

### Positive
- Clear separation between runtime and research artifacts
- Dynamic state isolation prevents cache pollution
- SHACL trust boundary at registration time, not runtime
- Module boundaries enable independent testing and future reuse
- Reasoner isolation option available for large-scale experiments without forcing subprocess overhead on small ontologies

### Negative
- Three new Gradle modules increase build complexity (mitigated by strict dependency direction)
- Researchers must use `shacl-register` CLI before MCP tools can reference shape sets (extra setup step)
- `--readonly=false` must be explicitly passed (no environment variable shortcut)
- Independent JVM worker adds 1-2s startup overhead when enabled (acceptable for large ontologies)

### Neutral
- v0.8.7 is the research-side first milestone baseline; no backward compatibility with pre-v0.8.7 experiment data is required

## References

- [Proposal](../../openspec/changes/v087-tool-call-validation/proposal.md)
- [Design](../../openspec/changes/v087-tool-call-validation/design.md) — D1 (module boundary), D2 (Jena version), D3 (readonly opt-in), D7 (Shape Registry), D9 (Overlay), D15 (reasoner isolation), D16 (cache isolation)
- [Tasks](../../openspec/changes/v087-tool-call-validation/tasks.md)
- [Smart home work list](file:///E:/owl4agents_代码改造工作清单_交付研发.md)

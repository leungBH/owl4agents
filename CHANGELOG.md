# Changelog

## 0.9.0 - 2026-07-23

### BREAKING CHANGES

- **D4: `aggregateStatus` vocabulary change** — `AggregateAnswerStatus.VERIFIED` jsonName changed from `"verified"` to `"supported"` to maintain consistency with `Verdict.SUPPORTED`. The `verify_claims_batch` response field `aggregateStatus` now returns `"supported"` instead of `"verified"` when all required claims are supported. Downstream consumers checking `aggregateStatus == "verified"` MUST update to `aggregateStatus == "supported"`.
- **D6: Out-of-scope pre-check dual check removed** — The claim-verification spec no longer requires entities to have explicit `Declaration` axioms; signature-only check (OR logic) is now the spec. Code already implemented OR logic; spec document was corrected to match. Entities referenced in axioms (e.g., SubClassOf) but without Declaration axioms now pass the scope pre-check.

### Fixed

- **D1: Per-ontology EntitySignatureCache (P0)** — Fixed cross-ontology cache eviction where loading large ontologies (HPO ~32K classes, Mondo ~30K classes) evicted small ontology entries (Pizza ~115 classes) from the global 50K-entry Caffeine cache, causing all 100 Pizza claims to return `out_of_scope`. Each `EntitySignatureCache` instance now holds its own Caffeine cache sized as `max(1000, classCount * 2)`, eliminating cross-ontology interference.
- **D2: Precise cache invalidation on ontology reload** — `EntitySignatureCacheManager.onOntologyReloaded()` now calls `invalidate()` on the removed per-ontology instance only; other ontologies' caches remain intact. Removed redundant `EntitySignatureCache.invalidateAll()` static calls from `ReasonerServiceImpl` (per-ontology invalidation is handled by the manager listener).
- **D3: Reserved predicate skip in detectMissingEntities (P1)** — `EvidenceGroundingService.detectMissingEntities()` no longer searches reserved structural keywords (e.g., `subClassOf`, `type`, `domain`) as property entities. Added `RESERVED_PREDICATES` constant set (21 OWL 2 axiom-type keywords). Predicates are only searched as property entities when they are IRIs (`http://` or `https://` prefix) and not in the reserved set.

### Removed

- **Public static API removal from `EntitySignatureCache`** — The following static methods were removed (replaced by per-instance methods): `invalidateAll()`, `put(kind, iri)`, `get(kind, iri)`, `stats()`, `estimatedSize()`, `cleanUp()`. Callers must use instance methods on `EntitySignatureCache` objects obtained from `EntitySignatureCacheManager`.

### Added

- `EntitySignatureCache.invalidate()` — per-instance method to clear this ontology's Caffeine cache.
- `EntitySignatureCache.stats()`, `estimatedSize()`, `cleanUp()` — per-instance methods for per-ontology cache monitoring.
- `EntitySignatureCacheManager.aggregatedStats()` — returns sum of `CacheStats` across all per-ontology instances for monitoring.

## 0.8.4 - 2026-07-11

### Added

- **Decision 1: Reasoner classification state tracking** — `ReasonerLifecycleManager` now maintains `ConcurrentHashMap<String, Boolean> classifiedMap` per ontology. `checkAxiomEntailment()`, `checkEquivalentClassesEntailment()`, and `ConsistencyAnalysisService.checkClassCompatibility()` skip `precomputeInferences(CLASS_HIERARCHY)` when already classified. `classify`, `checkConsistency`, and `realizeInstances` commands unconditionally classify and call `markClassified()`.
- **Decision 2: Reasoner profile caching** — `ReasonerLifecycleManager` maintains `ConcurrentHashMap<String, String> profileCacheMap`. `detectProfile()` returns cached profile on subsequent calls, avoiding repeated `OWL2DLProfile`/`OWL2ELProfile` construction and `checkOntology()` calls.
- **Decision 3: Per-request ontology single loading** — `ClaimVerificationService.verify()` loads `OWLOntology` once via `reasonerService.loadOntologyForClaim()` and threads it through all downstream methods (`applyScopePrecheck`, `verifyEntailmentClaim`, `checkDisjointCounterEvidence`, etc.). `ClaimWorkflowService.verifyBatch()` loads ontology once before the batch loop. Reduces 3-6 `loadOntology()` calls per claim to 1.
- **Decision 4: EntitySignatureCache** — New `EntitySignatureCache` class in `ontology-owlapi` module provides O(1) entity signature lookups (class/objectproperty/dataproperty/individual) with OBO namespace checking, matching v0.8.3 `isEntityDeclared()` dual-check semantics. `EntitySignatureCacheManager` manages per-ontology caches via `ConcurrentHashMap<String, CompletableFuture<EntitySignatureCache>>` with `OntologyReloadListener` integration.
- **Decision 5: Asserted axiom indexing** — `EntitySignatureCache` builds SubClassOf index (`Map<String, Set<String>>`) and DisjointClasses index (pairwise expansion) from asserted axioms. `checkAxiomEntailment()` and `ConsistencyAnalysisService.checkClassCompatibility()` check these O(1) indices before falling back to stream scanning or reasoner queries.
- **Decision 6: InferredHierarchyIndex** — `ReasonerServiceImpl` maintains `ConcurrentHashMap<String, Map<String, Set<String>>>` caching the parsed `inferred-class-hierarchy.jsonl` per ontology. Replaces per-call file scanning with in-memory map lookup. Implements `OntologyReloadListener` for cache invalidation on ontology reload.
- **Decision 7: OntologyCache TTL window** — `OntologyCache` maintains `ConcurrentHashMap<String, Long> lastValidatedAtMap` with 5s TTL. `getOrCreate()` skips `Files.exists()` + `Files.getLastModifiedTime()` + `Files.size()` syscalls when validated within the window. `invalidate()` and `invalidateAll()` clear TTL entries immediately.
- **OntologyCache multi-listener support** — `OntologyCache.reloadListener` changed from single `volatile OntologyReloadListener` to `CopyOnWriteArrayList<OntologyReloadListener>`. `setReloadListener()` replaced by `addReloadListener()`. Allows `ReasonerLifecycleManager`, `EntitySignatureCacheManager`, and `ReasonerServiceImpl` to all register as listeners.

### Changed

- Version bump 0.8.3 → 0.8.4 across `McpServerAdapter.SERVER_VERSION`, CLI banner, `build.gradle.kts`, npm package, CI assertion, README/FEATURES (EN+ZH), transcript, and this CHANGELOG.
- `ExampleClaimFileVersionCheckTest` updated to assert v0.8.4 in transcript (method `verifyClaimTranscriptUsesV084`).
- `ClaimVerificationService`, `ConsistencyAnalysisService`, `ReasonerServiceImpl` method signatures extended with `OWLOntology` and `OntologyId` parameters for per-request ontology threading. Old signatures retained as `@Deprecated` for backward compatibility.
- `CliServiceFactory` and `McpServerAdapter` initialization order: `OntologyCache` → `EntitySignatureCacheManager` (registered as listener) → `ReasonerServiceImpl` (5-arg constructor, registers lifecycleManager + this as listeners) → `ConsistencyAnalysisService` (4-arg constructor with `EntitySignatureCacheManager`).

### Performance

- Pizza hot path: ~85ms → ~30-40ms (≥45% speedup)
- Per-claim `loadOntologyForClaim()` calls: 3-6 → 1
- File stat syscalls per `getOrCreate()`: 3 → 0 (within TTL window)
- Entity signature lookup: O(N) stream scan → O(1) hash set lookup
- Asserted axiom lookup: O(N) stream scan → O(1) index lookup
- Inferred hierarchy file scan: O(N) per-call → O(1) in-memory map lookup (first call loads, subsequent hit cache)

### Notes

- v0.8.4 is **backward-compatible** with v0.8.3 clients. All changes are internal performance optimizations; no external API changes.
- Accuracy gate preserved: 80/80 verdict match (V081AcceptanceSuite), 15/15 R1-R7 tests (V083SemanticAccuracyTest), 0 failures in full Gradle suite (900+ tests).
- Readonly tool count remains 56; no new external dependencies.
- `EntitySignatureCache` memory overhead: ~5MB for mondo (~30K classes), ~15MB with axiom indices — acceptable relative to 236MB ontology file.

## 0.8.3 - 2026-07-10

### Fixed

- **R1: OOS pre-check uses `Imports.EXCLUDED` + Declaration axiom dual check** — `ConsistencyAnalysisService.isEntityDeclared()` previously used `Imports.INCLUDED`, allowing cross-ontology entities (UBERON→HPO, HP→Mondo) to pass the scope pre-check via the import closure. Now uses `Imports.EXCLUDED` for signature queries and adds a `getDeclarationAxioms(entity)` check to verify the entity has an explicit Declaration axiom in the ontology. Cross-ontology claims (hpo-057, mondo-051/052/053) now correctly return `out_of_scope`.
- **R2: Disjoint proxy signature check** — `ClaimVerificationService.checkDisjointCounterEvidence()` now checks whether subject and object entities are in the ontology's direct signature before running the disjointness proxy. Previously, cross-ontology claims (mondo-057) could trigger false `contradicted` verdicts via suffix matching. Now returns `null` (skip proxy) for out-of-scope entities.
- **R4: EquivalentClasses complex expression extraction** — `ReasonerServiceImpl.checkAxiomEntailment()` "EquivalentClasses" branch now extracts named classes from complex class expressions (e.g., `ObjectIntersectionOf`, `ObjectSomeValuesFrom`) via `getSignature()`, not just `getNamedClasses()`. Added `reasoner.getEquivalentClasses()` as stage 2 for simple equivalent-class definitions. pizza-007 (CheeseyPizza equivalent definition involving Pizza) now returns `supported`.
- **R5: Individual-level DisjointClasses dispatch** — `ClaimVerificationService.verifyDisjointClasses()` now dispatches to `DifferentIndividuals` entailment check when both subject and object have `kind=individual`, instead of always using class-level `checkClassCompatibility()`. Includes same-individual pre-check (trivially contradicted). pizza-035 (France differentFrom Germany) now returns `supported`.
- **R6: Property hierarchy dispatch for ObjectPropertyAssertion** — `ClaimVerificationService.verifyObjectPropertyAssertion()` now dispatches to `SubObjectPropertyOf` entailment check when both subject and object have `kind=object_property` and `predicate=subPropertyOf`, instead of always using individual-level `checkRelationAssertion()`. pizza-037 (hasBase subPropertyOf hasIngredient) now returns `supported`.
- **R7: ObjectPropertyDomain complex domain extraction** — `ReasonerServiceImpl.checkAxiomEntailment()` "ObjectPropertyDomain" branch now extracts named classes from complex domain expressions via `getSignature()` (stage 2), and adds `reasoner.getObjectPropertyDomains()` with subclass direction check (stage 3: `SubClassOf(D, domain)`, not `SubClassOf(domain, D)`). pizza-046 (isBaseOf domain PizzaBase) now returns `supported`.

### Added

- **`ClaimType.fromJsonName(String)` static method** — Robust deserialization from JSON name using the `jsonName()` field, with `valueOf(uppercase)` fallback for backward compatibility. Returns `null` instead of throwing `IllegalArgumentException` for unknown names. Used by `McpServerAdapter.parseClaimFromMcpArgs()` with a null check that throws a descriptive error listing all supported claim types.

### Changed

- Version bump 0.8.2 → 0.8.3 across `McpServerAdapter.SERVER_VERSION`, CLI banner, `build.gradle.kts`, npm package, CI assertion, README/FEATURES (EN+ZH), transcript, and this CHANGELOG.
- `ExampleClaimFileVersionCheckTest` updated to assert v0.8.3 in transcript (pattern `V083`, method `verifyClaimTranscriptUsesV083`).

### Notes

- v0.8.3 is **backward-compatible** with v0.8.2 clients. All fixes are verdict corrections (previously-unknown claims now return `supported` or `out_of_scope`); no new external API changes.
- R4 stage 1 uses semantic simplification (extracting named classes from complex expressions via `getSignature()`). This matches test intent ("is class X mentioned in the equivalent definition of Y?") but is not strict OWL equivalence. See design.md D3 for trade-off analysis.
- Readonly tool count remains 56; no new external dependencies.

## 0.8.2 - 2026-07-10

### Added

- **Ontology caching (`OntologyCache`)** — New `OntologyCache` class in `ontology-owlapi` with `ConcurrentHashMap<String, CompletableFuture<CacheEntry>>` for thread-safe ontology caching. Uses mtime+size dual detection for cache invalidation. `CompletableFuture` ensures at most one thread loads a given ontology (Mondo: 36-211s) while other threads wait on the same future. Failed loads remove the failed future so next call retries.
- **`OntologyReloadListener` interface** — Placed in `ontology-owlapi` to break circular dependency. `onOntologyReloaded(OntologyId)` is called BEFORE new cache entry becomes visible (TOCTOU prevention). `ReasonerLifecycleManager` implements this interface to delegate to `shutdownReasoner()`, releasing native HermiT/ELK/Openllet resources on cache reload.
- **Shared `OntologyCache` injection** — `ReasonerServiceImpl` (4-arg constructor), `ConsistencyAnalysisService` (3-arg), and `SemanticDeepeningService` (2-arg) now accept a shared `OntologyCache`, enabling cross-service ontology reuse. Old constructors marked `@Deprecated` for backward compatibility.
- **`CliServiceFactory.getSharedOntologyCache()`** — Public accessor for benchmark warm-up; independent of `getReasonerService()` to avoid initialization order coupling.
- **Cache sharing integration tests** — 7 integration tests verifying cross-service ontology instance sharing, cache reload triggers adapter invalidation, no resource leak across multiple reloads.
- **Benchmark fixture setup** — 240-claim benchmark configs (pizza-80, hpo-60, mondo-60, sosa-40) with cache warm-up step and `-Xmx4g` JVM heap configuration.

### Fixed

- **workspaceName hardcode bug** — `ConsistencyAnalysisService` and `SemanticDeepeningService` previously hardcoded `"default"` workspace in `loadOntology()`, causing path resolution failures for non-default workspaces. Now uses the `workspaceName` from the injected `OntologyCache`.

### Changed

- Version bump 0.8.1 → 0.8.2 across `McpServerAdapter.SERVER_VERSION`, CLI banner, `build.gradle.kts`, npm package, CI assertion, examples README, and this CHANGELOG.
- `McpServerAdapter` constructor now creates a shared `OntologyCache` and injects it into all three services.
- `CliServiceFactory` uses lazy `getOntologyCache()` initializer shared across all services.

### Notes

- v0.8.2 is **backward-compatible** with v0.8.1 clients. `@Deprecated` constructors preserve original behavior for legacy callers.
- Cache warm-up strategy: call `getOrCreate()` for each ontology before processing the first claim, eliminating 36-211s first-load delays during benchmark execution.
- Readonly tool count remains 56; no new external dependencies.

## 0.8.1 - 2026-07-09

### Fixed

- **ISSUE-01: Global scope pre-check for external IRIs** — `ClaimVerificationService.verify` now performs a global scope pre-check before dispatching to type-specific verification. Previously, claims whose subject or object IRI was external to the loaded ontology (e.g. `http://example.org/external#Foo`) returned `UNKNOWN` from the type-specific verifier; they now return `OUT_OF_SCOPE` with `unknownReason = missing_or_out_of_scope_entities`. The pre-check honours the `isExemptFromScopePrecheck` exemption list (`ontology_scope`, `ontology_consistency`, `literal_validity`) and the built-in namespace whitelist (`xsd:`, `rdf:`, `rdfs:`, `owl:`).
- **ISSUE-02: Reasoner-driven entailment for object/data property domain and range** — `ReasonerServiceImpl.checkAxiomEntailment` now implements `ObjectPropertyDomain`, `ObjectPropertyRange`, `DataPropertyDomain`, and `DataPropertyRange` cases. Each case follows the asserted-first-then-`isEntailed`-fallback order, with IRIs resolved via the new `OntologyIriResolver` and a `precomputeInferences(CLASS_HIERARCHY)` precondition. `OWLReasonerAdapter.getUnderlyingReasoner()` was added to expose the raw reasoner; the legacy private bridge returning `null` is no longer used.
- **ISSUE-03: Complex class expressions in equivalent-class claims** — Added the `ClassExpression` sealed interface (6 permits: `NamedClass`, `ObjectSomeValuesFrom`, `ObjectAllValuesFrom`, `ObjectIntersectionOf`, `ObjectUnionOf`, `ObjectComplementOf`), `ClassExpressionBuilder` for converting records to OWL API objects, and `ReasonerServiceImpl.checkEquivalentClassesEntailment(OWLClassExpression, OWLClassExpression)` for the reasoner-driven entailment check. Nesting depth is capped at 3; unresolved IRIs raise `ENTITY_NOT_FOUND`; the deferred `data_existential`, `data_universal`, `cardinality_restriction`, and `data_intersection` types return `INVALID_CLAIM_SCHEMA` with an error message listing the 6 supported types.
- **ISSUE-04: `DifferentIndividuals` verification** — `ClaimVerificationService.verify` now routes `different_individuals` claims to `verifyDifferentIndividuals`, which delegates to `ReasonerServiceImpl.checkEntailment` (asserted-first-then-`isEntailed`-fallback). Counter-evidence `SameIndividual` is checked via the new `SameIndividual` axiom type, returning `CONTRADICTED` with `EvidenceItem(ROLE_COUNTER, kind=INFERRED_AXIOM, source="inferred_same_individual")`. 80-curated-claim accuracy impact: `pizza-035` (France/Germany, asserted) is now `SUPPORTED`.
- **ISSUE-05: `SubObjectPropertyOf` verification** — `ClaimVerificationService.verify` now routes `object_property_subproperty` claims to `verifySubPropertyOf`, which delegates to `ReasonerServiceImpl.checkEntailment` (asserted-first-then-`isEntailed`-fallback). A reverse-direction check returns `CONTRADICTED` when the super-property is entailed as a sub-property of the claimed sub-property. 80-curated-claim accuracy impact: `pizza-037` (hasBase/hasIngredient, asserted) is now `SUPPORTED`.

### Added

- Two new claim types: `DIFFERENT_INDIVIDUALS` and `OBJECT_PROPERTY_SUBPROPERTY`.
- Optional `object.expression` field on `ClaimEntity`, accepting the 6 supported `ClassExpression` types. Backward-compatible two-arg constructor preserved.
- `OWLReasonerAdapter.getUnderlyingReasoner()` — exposes the raw `OWLReasoner` to service-layer code.
- `OntologyIriResolver` — shared utility (in `ontology-owlapi`) that resolves full IRIs, prefixed names, and bare IRIs against the ontology's prefix format and signature.
- `ClassExpressionBuilder` — converts `ClassExpression` records to OWL API `OWLClassExpression` objects with IRI resolution and nesting-depth enforcement.
- `checkEquivalentClassesEntailment(OWLClassExpression, OWLClassExpression)` on `ReasonerService` — reasoner-driven entailment for complex expressions.
- `ObjectPropertyDomain` / `ObjectPropertyRange` / `DataPropertyDomain` / `DataPropertyRange` / `DifferentIndividuals` / `SameIndividual` cases in `checkAxiomEntailment`.
- `JVM` system property `OWL4AGENTS_HOME` is now consulted as a fallback for the inferred-class-hierarchy lookup (needed for `Process`-less JUnit runs of `V03AcceptanceSuite`).

### Changed

- Version bump 0.8.0 → 0.8.1 across `McpServerAdapter.SERVER_VERSION`, CLI banner, `build.gradle.kts`, npm package, CI assertion, examples README, and this CHANGELOG.
- `ReasonerServiceImpl.determineSource` now returns `"asserted"` (not `"explicit"`) for the `SubClassOf` branch to align with the v0.8.1 evidence-source naming.

### Notes

- v0.8.1 is **backward-compatible** with v0.8.0 clients. The `object.expression` field is optional; claims without it parse unchanged.
- 80-curated-claim accuracy improved from **75/80** to **80/80** (5 fix scenarios: `pizza-007`, `pizza-035`, `pizza-037`, `pizza-046`, `owl2bench-027`).
- Readonly tool count remains 56; no new external dependencies.
- The pre-check upgrade from `unknown` to `out_of_scope` for external IRIs is a contract change. v0.8.0 clients that relied on `unknown` for these claims must update to handle `out_of_scope`. The `unknownReason` is normalized to `missing_or_out_of_scope_entities` to remain compatible with the v0.5 workflow contract.

## 0.8.0 - 2026-06-29

### Fixed (v0.8.0 post-release retest 2026-07-03)

- **D-004 [BLOCKING] ClassCastException on boolean parameters** — 8 tools that accept `include_inferred` (and similar booleans) crashed with `ClassCastException` when callers sent the JSON boolean `true`/`false` instead of a string. The parameter parser now uses `String.valueOf(args.get(...))` so both `true` and `"true"` are accepted.
- **D-005 [BLOCKING] `ontology_benchmark_run` rejected inline `questions:` blocks** — The `ExperimentConfigParser` requires a `questionSetPath` (a JSONL file path), not an inline `questions:` array. The test fixture and the `test_all_tools_v3.ps1` integration script now generate a valid JSONL question set and a valid config YAML referencing it.
- **D-006 [BLOCKING] `entity_iri` vs `class_iri` / `property_iri` schema mismatch** — `McpToolRegistry` declared `class_iri` / `property_iri` / `individual_iri` for four tools, but the implementation reads `entity_iri`. The schemas now use `entity_iri` uniformly, matching the implementation.
- **D-007 [BLOCKING] Reasoner name case sensitivity** — `ReasonerLifecycleManager` matched the reasoner registry keys with `equals`, so callers passing `openllet` (lowercase) were rejected even though the canonical key is `Openllet`. Added `canonicalReasonerName()` for case-insensitive lookup.
- **D-008 [BLOCKING] NPE / JSON-RPC error when claim's `ontologyId` differs from the caller's `ontology_id`** — 4 claim-verification tools (`verify_claim`, `get_evidence_path`, `find_counterexamples`, `explain_unknown`) and `detect_missing_entities` previously passed the caller's `ontology_id` as a side-channel and forwarded the claim's own (possibly blank or foreign) `ontologyId` to the service, which then NPE'd inside the service layer. The top-level `ontology_id` is now authoritative: a new `withAuthoritativeOntologyId()` helper rebuilds the claim with the caller's value before invoking any service.
- **D-009 [BLOCKING] NPE in `QaEvaluationService` on null `Optional`** — Gson 2.13 sometimes deserializes a record component typed as `Optional<String>` as Java `null` (not `Optional.empty()`), and `line.reviewStatus().orElse(null)` then NPE'd. `QaEvaluationService.evaluate()` now treats a null `reviewStatus` as `Optional.empty()`.
- **D-010 [BLOCKING] `V03AcceptanceSuite` 2 failures (`Subclass claim is supported`, `Supported claim has an evidence path`)** — `ReasonerServiceImpl.checkStoredEntailment()` reads the workspace's `inferred-class-hierarchy.jsonl` only from `OWL4AGENTS_HOME` env var or `~/.owl4agents`, ignoring the constructor's `workspaceBasePath`. The JUnit `@TempDir` was therefore invisible. The lookup now also accepts the `OWL4AGENTS_HOME` system property (needed for `Process`-less JUnit), and the suite now pre-runs the reasoner so the file is on disk before `verify()` is called.

### Added

- **MCP Streamable HTTP transport on `GET /mcp`** — `HttpMcpServer` now handles `GET /mcp` with `Accept: text/event-stream` and upgrades the connection to a long-lived Server-Sent Events (SSE) stream. Each stream is bound to a `Mcp-Session-Id` (UUID v4) issued on `initialize`. Multiple concurrent streams per session are allowed (fan-out). The full protocol follows the MCP 2025-03-26 Streamable HTTP specification.
- **SSE-upgrade path on `POST /mcp`** — when the client sends `Accept: text/event-stream` and a valid `Mcp-Session-Id`, the JSON-RPC response is delivered as an `event: message` frame on the bound SSE stream and the POST itself returns `200 OK` with an empty body. If the session has no open SSE stream and the global SSE cap is reached, the server falls back to a plain JSON response with `X-Streamable-Http-Fallback: application/json`. The v0.7.x plain-JSON path is unchanged for `Accept: application/json` clients.
- **Session management** — new `McpSession` and `McpSessionManager` classes track per-session SSE stream sets, last-access timestamps, and global stream counts. Idle sessions are swept every minute using the configured TTL (default 30 minutes).
- **SSE heartbeats** — new `McpSseStream.writeHeartbeat()` emits an RFC 8895-style comment frame (`: ping\n\n`) every 15 seconds on every open stream to keep idle proxies and Trae IDE alive. A dedicated `ScheduledExecutorService` is used so heartbeats never block the reasoner or worker pools.
- **Three new CLI options on the `mcp` subcommand** (HTTP transport only):
  - `--max-sse-connections <n>` (default 100, must be ≥ 1) — global cap on concurrent SSE streams; the server refuses new streams with 503 + `Retry-After: 30` when the cap is reached.
  - `--session-ttl-minutes <m>` (default 30, must be ≥ 1) — idle session lifetime; sessions are swept every minute.
  - `--sse-heartbeat-seconds <s>` (default 15, must be ≥ 1) — heartbeat interval per stream.
  - All three options reject values < 1 at startup with a non-zero exit code and a diagnostic message.
- **`mcp-config --client trae` generator** — Trae IDE uses the MCP 2025-03-26 Streamable HTTP transport. The generator emits a single `mcpServers.owl4agents.url` field (byte-equivalent to `--client http`) and a committed fixture is provided at `examples/agent-mcp/configs/trae-mcp-config.json`. `examples/agent-mcp/README.md` now has a "Trae IDE (v0.8+)" section.
- **`Mcp-Session-Id` header on `initialize` and `POST /mcp`** — a v0.7.x plain `initialize` request now returns `Mcp-Session-Id: <uuid>` in the response headers. Subsequent `POST /mcp` requests must echo that header. The header is validated as a UUID v4 string; non-UUID values are rejected with 400.
- **Shutdown sequencing** — `HttpMcpServer.stop()` (and `close()`) follows a documented six-step sequence (cancel heartbeat scheduler → close all SSE streams → close `HttpServer` → drain worker pools → drain reasoner pool → clear `McpSessionManager`) that completes within 5 seconds under SIGTERM.
- **New test classes** — `HttpMcpServerSseTest` (32 cases covering accept negotiation, session id lifecycle, fallback headers, multi-stream fan-out, heartbeat, shutdown, cap, header validation, no-new-deps, version assertion) and `McpSessionManagerTest` (sweep expiry, concurrent create, UUID collision, active stream count, close idempotency).
- **`openspec/change` artifact for the v0.8 design** — the change is validated with `openspec validate --strict` and includes proposal, design, tasks, and four delta specs (mcp-streamable-http-transport, mcp-sse-session, mcp-cli, mcp-server-lifecycle).

### Fixed

- **`GET /mcp` no longer returns 405 for Trae IDE** — Trae IDE speaks the MCP 2025-03-26 Streamable HTTP transport. v0.7.x returned `405 Method Not Allowed` because it only registered `POST /mcp`; v0.8.0 registers a separate `SseGetHandler` that handles `GET + Accept: text/event-stream` and returns `405 + Allow: GET, POST` for any other `GET` variant.
- **`POST /mcp` no longer returns 501 when the client sends `Accept: text/event-stream` without an open SSE stream** — v0.7.x returned 501 ("SSE not yet implemented"); v0.8.0 falls back transparently to a plain-JSON response with an `X-Streamable-Http-Fallback: application/json` header.
- **v0.7.x plain-HTTP clients continue to work byte-for-byte** — the legacy `Accept: application/json` path is unchanged; the new SSE header parsing only activates when `text/event-stream` is present in the `Accept` header. A v0.7.x client gets a 200 OK with the same JSON-RPC body it would have gotten before.
- **Reasoner-using tool calls arriving over SSE are serialized on the same single-thread reasoner executor as the v0.7.x plain-HTTP path** — prevents reasoner races without changing the throughput contract documented in v0.7.1.

### Changed

- **Version bump** — `build.gradle.kts`, `tools/npm/package.json`, `modules/ontology-cli/src/main/java/org/owl4agents/cli/Owl4AgentsCli.java`, `.github/workflows/ci.yml` `--version` assertion, and `examples/agent-mcp/README.md` `initialize` example all now report `0.8.0`.
- **`McpServerAdapter.SERVER_VERSION` constant** — bumped from `0.7.0` to `0.8.0`; the `initialize` JSON-RPC response now reports `serverInfo.version = "0.8.0"`.

### Notes

- v0.8.0 is backward-compatible with v0.7.1 stdio and HTTP clients. The readonly tool count remains 56.
- No new external dependencies.
- The default `session-ttl-minutes` (30) and `sse-heartbeat-seconds` (15) match the v0.8 spec's defaults; operators who need longer sessions or faster heartbeats should override with the new CLI options.

## 0.7.1 - 2026-06-25

### Fixed

- `examples/agent-mcp/configs/http-mcp-config.json` is now committed (was missing in v0.7.0). The committed fixture declares `mcpServers.owl4agents.url = "http://127.0.0.1:8080/mcp"` and is consistent (field-level) with the new `mcp-config --client http` generator output.
- `examples/agent-mcp/README.md` now has a "HTTP Transport (v0.7+)" section covering listener startup (`--transport http --port 8080`), the committed HTTP client config, `curl` probes for `GET /` and `POST /mcp initialize`, the HTTP error matrix, and the stdio parity note. The `initialize` response example in the README now reports `serverInfo.version = "0.7.1"` (was stale `0.4.0`).
- `examples/agent-mcp/example.yaml` now lists the four config fixtures under `fixtures:` (was empty in v0.7.0) and adds two new validation commands: `mcp-config --client http` and a manual HTTP `mcp --transport http` probe step. The expected-output block now includes `mcpServers.owl4agents.url`.
- `examples/agent-mcp/transcripts/verify-claim-transcript.md` `initialize` response now reports `serverInfo.version = "0.7.1"` (was stale `0.4.0`).
- `tools/npm/package.json` `version` field is now `0.7.1` (was stale `0.6.0` from before the v0.7.0 release).
- `.github/workflows/ci.yml` `--version` assertion now expects `0.7.1` (was stale `0.4.0` from before the v0.7.0 release).

### Added

- `mcp-config --client http` (and `--client=http`) generator and the corresponding `--url <url>` override. The HTTP client config emits a single `mcpServers.owl4agents.url` field (no `command` / `args` / `env`), matching the v0.7 HTTP transport contract. Adds 3 new cases to `McpConfigCommandTest` (`HttpClientTests`).
- `mcp-config` now accepts `http` in its `SUPPORTED_CLIENTS` set; the unknown-client rejection diagnostic still names `generic`, `claude`, `cursor`, and now also `http` as supported.

### Notes

- v0.7.1 is a docs / example / version-reference fix release. The runtime behavior of v0.7.0 is unchanged. Readonly tool count remains 56.
- No new external dependencies.

## 0.7.0 - 2026-06-25

### Added

- Optional HTTP/JSON-RPC transport for the MCP server: `--transport http --host <host> --port <port>`, alongside the existing stdio transport (which remains the default and is byte-level unchanged from v0.6.0).
- New `modules/ontology-mcp` package class `HttpMcpServer` (built on `com.sun.net.httpserver.HttpServer`) exposing three endpoints: `POST /mcp` (JSON-RPC 2.0), `GET /info` (diagnostics), and `GET /` (banner).
- New CLI flags on the `mcp` subcommand: `--transport=stdio|http` (default `stdio`), `--host` (default `127.0.0.1`), `--port` (default `8080`), and the project-wide `--home=<dir>` override.
- Bounded worker pool: 8 core threads + 100-slot `LinkedBlockingQueue` + `AbortPolicy` for non-reasoner tools. Concurrent calls beyond the budget return `HTTP 500` with JSON-RPC `code = -32000`.
- Single-thread pool with `SynchronousQueue` + `AbortPolicy` for the 14 reasoner-using tools; the 2nd and later concurrent reasoner call is rejected with `HTTP 500` + `code = -32000`, message `"reasoner executor saturated"`.
- New unified JSON-RPC entry point `McpServerAdapter.handleJsonRpc(JsonObject)`; both stdio and HTTP transports route through it so wire-format field-level parity is guaranteed.
- Eager service initialization in `McpServerAdapter` constructor: 7 services (`reasonerService` → `consistencyAnalysisService` → `semanticDeepeningService` → `claimVerificationService` → `evidenceGroundingService` → `claimWorkflowService` → `evidenceContextBuilder`) are constructed in dependency order with `final` fields; the prior `getXxxService()` lazy-init accessors are removed.
- New unit tests: `HttpMcpServerTest` (12 cases covering HTTP behavior, error matrix, content-type/parse-error/SSE handling, and TC-25/TC-27 concurrency gates) and `McpServerAdapterTest` (6 cases covering the unified entry point, eager-init order, and parity).
- New `test/contracts/v07-acceptance/contracts.md` defining 34 acceptance gates (`V07-HTTP-*`, `V07-PARITY-*`, `V07-INIT-*`, `V07-CONC-*`, `V07-CLI-*`, `V07-VERSION-*`).
- Stress-test split: 10-concurrent reasoner test is tagged `@Tag("stress")` and runs in the v0.7 acceptance gate only, not in default `gradle test`.
- Runtime shutdown hook in `HttpMcpServer` to call `stop()` on `SIGTERM`/`SIGINT`.
- v0.7.0 OpenSpec change: `openspec/changes/add-v0-7-mcp-http-transport/` (proposal, design, tasks, specs, acceptance report).

### Changed

- `McpServerAdapter` refactored from lazy `getXxxService()` accessors to constructor-injected `final` fields (eager init).
- `McpCommand.runHttp` catches `BindException` (port-in-use, exit 78 per BSD sysexits.h `EX_CONFIG`), `InterruptedException` (exit 130), and `IOException` (exit 1); reflection-based `Class.forName(...).newInstance(...)` loading of `HttpMcpServer` was removed in favor of a direct `new HttpMcpServer(adapter)`.
- `McpServerIntegrationTest` refactored to use `McpServerAdapter.handleJsonRpc` so the stdio regression is asserted through the same routing entry point as the HTTP transport.
- `README.md` updated with a v0.7 quick-start section (HTTP transport), and the v0.7 roadmap block is marked delivered.
- `build.gradle.kts` version bumped to `0.7.0`; `Owl4AgentsCli` picocli `version` attribute set to `0.7.0`.

### Notes

- v0.7 readonly tool count remains at 56 (unchanged from v0.6 baseline).
- HTTP and stdio transports are **JSON field-level identical**, not byte-level identical. Transport-level framing (HTTP status codes, headers, empty-body semantics for notifications) is explicitly outside the parity contract.
- The v0.7 OpenSpec change is tracked in `openspec/changes/add-v0-7-mcp-http-transport/` and the runtime verification report is `openspec/changes/add-v0-7-mcp-http-transport/acceptance-report.md`.

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

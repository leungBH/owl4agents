# owl4agents

**Local OWL ontology runtime, reasoner integration, SPARQL query layer, and readonly MCP server for LLM agents.**

`owl4agents` turns a folder of OWL/RDF files into a queryable local knowledge base. It loads ontologies, runs OWL reasoners (HermiT / ELK / Openllet), executes SPARQL, and exposes the result as a 56-tool readonly MCP server that any LLM agent (Claude Desktop, Trae IDE, Cursor, ...) can plug into without ever leaving your machine.

> **v0.8** adds the MCP Streamable HTTP transport (SSE on `GET /mcp`, `Mcp-Session-Id` round-trip, content negotiation). The v0.7 plain-JSON HTTP transport is preserved as the no-regression baseline; stdio and wire-format parity are unchanged.
>
> **v0.8.4** (recommended) optimizes claim verification performance via 7 decisions: reasoner classification state tracking (skip redundant `precomputeInferences`), profile caching, per-request ontology single loading, `EntitySignatureCache` for O(1) entity signature lookups, asserted axiom indexing (SubClassOf + DisjointClasses), in-memory inferred hierarchy index, and `OntologyCache` 5s TTL window. Pizza hot path ~85ms → ~30-40ms. See [CHANGELOG.md](CHANGELOG.md) §"0.8.4".
>
> **v0.8.5** replaces structural proxy verdicts with exact consistency checks (`O ∪ {α} is inconsistent`). The 5-stage pipeline (scope → source consistency → entailment → exact consistency → verdict) produces semantically correct verdicts for all 15 test fixtures. Breaking change: `ClaimVerificationResult` schema v2 adds `executionStatus` (COMPLETED/TIMEOUT/ERROR) and makes `semanticVerdict` nullable. See [MIGRATION.md](MIGRATION.md) for the v1 → v2 migration guide.
>
> **v0.8.5 Known Limitations:** (1) Java interrupt may not reliably stop reasoners on timeout — `future.cancel(true)` interrupts the thread, but HermiT/ELK may continue running in the background; future work may use an independent JVM worker for reliable cancellation. (2) Windows `parkNanos` has ~1ms timer resolution — sub-millisecond timeouts may not work reliably; use `Duration.ZERO` for immediate timeout. (3) ELK reasoner (OWL 2 EL) silently ignores OWL 2 DL constructs like `NegativeObjectPropertyAssertion` — always specify HermiT explicitly for OWL 2 DL ontologies.
>
> **v0.8.3** fixes 7 semantic accuracy issues in claim verification (R1, R2, R4-R7: OOS pre-check, disjoint proxy, EquivalentClasses complex expressions, individual-level disjointness, property hierarchy, ObjectPropertyDomain complex domains; D7: ClaimType deserialization hardening) — resolving 9 error claims from the upgrade package. See [CHANGELOG.md](CHANGELOG.md) §"0.8.3".
>
> **v0.8.1** fixes 5 claim-verification accuracy defects from v0.8.0 (ISSUE-01…ISSUE-05) — see [CHANGELOG.md](CHANGELOG.md) §"0.8.1" and `doc/retrospectives/`. The 80-curated-claim accuracy gate moved from 75/80 → 80/80. Two new claim types (`different_individuals`, `object_property_subproperty`) and an optional `expression` field on `subject` / `object` for complex class expressions are now supported.

---

## Languages / 语言版本

- **English** (this file)
- [简体中文 README](README.zh-CN.md)
- Deep reference: [English FEATURES](FEATURES.md) | [功能参考(中文)](FEATURES.zh-CN.md)

---

## README vs FEATURES — which one to read

This repository has two documentation files, and they have different jobs:

| File | Audience | Length | What it covers |
|---|---|---|---|
| [README.md](README.md) (this file) | Everyone, especially new users | ~10 min read | What owl4agents is, 5-minute quick start, deployment, MCP client config, troubleshooting pointers. |
| [FEATURES.md](FEATURES.md) | Programmers who want to **use** owl4agents (CLI or MCP) | ~60 min read | The full reference. Every CLI command and every MCP tool, with real OWL files, real input/output JSON, and "when to use it" guidance. Start here if you plan to write code against owl4agents. |

**Rule of thumb:** if you want to install and try owl4agents, read this README. If you want to know what a particular tool *does* and what its response looks like, read [FEATURES.md](FEATURES.md).

> Looking for the strict protocol contract (error codes, HTTP semantics, JSON schema)? See [CHANGELOG.md](CHANGELOG.md) and the per-feature spec under `openspec/changes/archive/`.

---

## What owl4agents does

```
OWL / RDF / Turtle files
        │
        ▼
  owl4agents (Java 22 + OWL API + HermiT/ELK/Openllet + Jena ARQ)
        │
        ├── CLI  (47 commands: import, query, reason, verify-claim, …)
        └── MCP  (56 readonly tools over stdio, HTTP, or SSE — no writes)
```

- **Local-first**: all data in `~/.owl4agents/workspaces/<name>/`; no cloud, no network call.
- **Reproducible**: reasoner outputs are written to disk (`reasoning-report.json`, `inferred-class-hierarchy.jsonl`, ...), so two runs on the same ontology produce the same artifacts.
- **Auditable**: every MCP tool call is appended to `mcp-tool-calls.jsonl` (atomic JSON lines).
- **Readonly-safe by default**: the MCP server runs with `--readonly`, exposing only the 56 read/verify tools. Mutations (import, delete) only happen via the CLI.
- **Standard protocols**: MCP `2025-03-26` Streamable HTTP, JSON-RPC 2.0, SPARQL 1.1, OWL 2 (DL/EL/QL/RL).

---

## 5-minute quick start

This walks a CS bachelor through cloning the repo, building, importing a small ontology, and asking a question — on Windows PowerShell. (macOS / Linux are similar; swap `.\gradlew.bat` for `./gradlew`.)

```powershell
# 0. Prerequisites: Java 22 (set JAVA_HOME) and Node.js 18+.
java -version    # → 22.x
node --version   # → 18.x or newer

# 1. Build the runnable jar.
git clone https://github.com/leungBH/owl4agents.git
cd owl4agents
.\gradlew.bat :modules:ontology-cli:shadowJar

# 2. Initialize a local workspace (a folder under ~/.owl4agents/workspaces/default/).
node tools/npm/bin/owl4agents.js init

# 3. Import a small example ontology. This file ships with the repo.
node tools/npm/bin/owl4agents.js import `
    test/corpus/golden/v0.3-claim-verification.owl v03_demo

# 4. Classify it with ELK (the lightest reasoner, fine for the EL profile).
node tools/npm/bin/owl4agents.js reason v03_demo --reasoner elk

# 5. Look around: list imported ontologies, search, dump a class, run SPARQL.
node tools/npm/bin/owl4agents.js list
node tools/npm/bin/owl4agents.js search v03_demo Dog
node tools/npm/bin/owl4agents.js entity v03_demo "http://example.org/v0.3#Dog"
node tools/npm/bin/owl4agents.js query v03_demo `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }"
```

If you got bindings for `?s`, congratulations — the pipeline works end to end. From here:

- **Want to drive it from an LLM agent?** Skip to [MCP client configuration](#mcp-client-configuration).
- **Want to verify a structured claim against the ontology?** See [FEATURES.md §6 "Claim verification"](FEATURES.md).
- **Want to know what every CLI command does?** [FEATURES.md §4](FEATURES.md).
- **Want to know what every MCP tool returns?** [FEATURES.md §5](FEATURES.md).

---

## Example packs

The repository ships with four runnable example packs under `examples/`. Each
pack contains an `example.yaml` manifest, a `README.md`, and a small ontology
fixture so you can run it end-to-end without any extra setup:

- [examples/claim-verification/](examples/claim-verification/) — verify structured
  claims against a small ontology and see `supported` / `contradicted` /
  `unknown` / `out_of_scope` verdicts in action. Workspace: `claim-demo`.
- [examples/pizza-reasoning/](examples/pizza-reasoning/) — load the CO-ODE
  `pizza.owl` and exercise HermiT classification, property chains, and
  disjoint-class entailment. Workspace: `pizza-demo`.
- [examples/agent-mcp/](examples/agent-mcp/) — start the readonly MCP server
  over stdio or HTTP and connect a Trae IDE / Claude Desktop client to it.
- [examples/biomedical-grounding/](examples/biomedical-grounding/) — ground
  biomedical free-text claims against a BFO-style ontology and inspect the
  evidence chain. Workspace: `bio-demo`.

Each pack's `README.md` documents the exact `node tools/npm/bin/owl4agents.js
init … --workspace <name>` and `import` commands to run it.

---

## Architecture at a glance

```
+--------------------+        fork + exec         +----------------------------+
|  npm launcher      |  ──────────────────────▶  |  owl4agents.jar (Java 22)   |
|  (Node.js 18+)     |    forwards argv/IO        |                            |
+--------------------+                            |  ┌────────┐  ┌────────┐   |
        │                                         |  │ CLI    |  │ MCP    |   |
        │  $env:OWL4AGENTS_HOME = ...             |  │(Picocli|  │(JSON-  |   |
        ▼                                         |│  │ 47    │  │ RPC +  │   │|
~/.owl4agents/workspaces/                          |  │ cmds)  |  │ SSE)   |   |
└── default/                                       |  └───┬────┘  └───┬────┘   |
    ├── catalog.json                                |      │           │       |
    └── ontologies/                                 |      ▼           ▼       |
        └── v03_demo/                              |  ┌──────────────────┐   |
            ├── source/  (raw .owl)                |  │ ontology-service │   |
            ├── canonical/ (normalized)            |  └────┬─────────────┘   |
            ├── inferred/ (reasoner output)        |       │                 |
            └── reasoning-report.json              |       ▼                 |
                                                   |  OWL API · HermiT ·    |
                                                   |  ELK · Openllet · Jena |
                                                   +────────────────────────+
```

11 Gradle modules, grouped into three layers (storage / OWL-API / query / reasoner / retrieval / validation / benchmark for the core, ontology-cli and ontology-mcp for the entry points, ontology-distribution for the acceptance suite). See [FEATURES.md §2](FEATURES.md) for the module-level breakdown.

---

## MCP client configuration

`mcp-config` writes a ready-to-paste JSON for the major MCP clients. Generated configs always point at the in-repo npm launcher and set `OWL4AGENTS_HOME` for you.

```powershell
# Claude Desktop — write the config to the user default location.
node tools/npm/bin/owl4agents.js mcp-config --client claude

# Generic stdio MCP client (e.g. custom IDE plugin) — print to stdout.
node tools/npm/bin/owl4agents.js mcp-config --client generic

# Cursor.
node tools/npm/bin/owl4agents.js mcp-config --client cursor

# Trae IDE — produces a config whose URL ends in /mcp so Trae issues
# both POST /mcp and GET /mcp (SSE) against the v0.8 server.
node tools/npm/bin/owl4agents.js mcp-config --client trae

# Override the workspace root, e.g. point at D:\owl4agents-workspace.
node tools/npm/bin/owl4agents.js mcp-config --client claude `
    --workspace-home D:/owl4agents-workspace
```

> The MCP server is **readonly by default**. Use the CLI to import / delete ontologies, and the MCP server to query / verify.

For a deeper end-to-end walkthrough (start the server, run `tools/list`, run a tool, read the response) see [FEATURES.md §3 "Try the MCP server in 5 minutes"](FEATURES.md).

---

## Deployment for local agents

1. Clone this repository onto the machine that runs the agent.
2. Install Java 22 (set `JAVA_HOME`) and Node.js 18+.
3. Build the runnable jar: `.\gradlew.bat :modules:ontology-cli:shadowJar`.
4. Initialize a workspace and import ontologies with the CLI.
5. Point your MCP client at `node tools/npm/bin/owl4agents.js mcp --readonly`.

Full deployment recipe (including the Windows-only `java -jar` ACCESS_VIOLATION workaround, environment variables, and the systemd service template) is in [FEATURES.md §7](FEATURES.md).

---

## Requirements

- **Java 22** (with `JAVA_HOME` pointing at the JDK; on Windows avoid the Oracle `javapath` shim).
- **Node.js 18+** (for the npm launcher; not required for the embedded jar itself).
- **Windows / macOS / Linux** — the npm launcher abstracts platform differences.

On Windows, prefer the npm launcher or `gradlew run --args="..."` over `java -jar owl4agents.jar`; the latter has a known ACCESS_VIOLATION interaction with the OWL API native loader in some Windows environments.

---

## Verifying a fresh checkout

```powershell
.\gradlew.bat clean buildVerification
.\gradlew.bat :modules:ontology-cli:shadowJar
node tools/npm/test/launcher.test.js
node tools/npm/bin/owl4agents.js --version    # → 0.8.4
node tools/npm/bin/owl4agents.js --help
```

A green run reports `BUILD SUCCESSFUL`, `Results: 29 passed, 0 failed` for the npm launcher, and `--version` prints `0.8.4`. The full Gradle suite has 900+ unit tests (0 failures) covering the 80 curated claim accuracy gate (80/80) — see `build/reports/tests/test/index.html` after a run.

---

## Where to go next

- **New to OWL / RDF?** [FEATURES.md §1 "OWL and SPARQL in 5 minutes"](FEATURES.md) is a primer aimed at CS graduates.
- **Want to see real tool calls?** [FEATURES.md §3 "A real walkthrough"](FEATURES.md) loads a small ontology, runs reasoner, asks a SPARQL query, and verifies a claim — every step with actual output.
- **Building an agent?** [FEATURES.md §6 "Claim verification and evidence grounding"](FEATURES.md) shows how to wire `verify-claim` / `evidence-context` into an answer pipeline.
- **Hitting an error?** [FEATURES.md §9 "Troubleshooting"](FEATURES.md) lists the common `READONLY_VIOLATION`, `SPARQL_SAFETY_VIOLATION`, `ONTOLOGY_NOT_READY` and other errors with fixes.

## License

Apache-2.0. See [LICENSE](LICENSE).

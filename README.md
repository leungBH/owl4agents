# owl4agents

Local OWL ontology runtime, reasoner integration, SPARQL query layer, and readonly MCP server for LLM agents.

`owl4agents` is a local-first OWL/RDF ontology runtime for researchers and agent developers. It imports, manages, reasons over, queries, and retrieves structured semantic context from OWL ontologies, then exposes those capabilities through both CLI commands and an MCP server.

> Status: v0.4 benchmark examples and demo packs. v0.4 adds runnable example packs under `examples/` for claim verification, pizza reasoning, MCP agent integration, and biomedical grounding, plus automated example validation as a release gate.

## v0.4 Quick Start

### Requirements

- Windows, macOS, or Linux
- Java 22, with `JAVA_HOME` pointing to the JDK
- Node.js 18+ for the npm launcher and MCP client entry point

On Windows, prefer `JAVA_HOME` over the Oracle `javapath` shim. The npm launcher uses `JAVA_HOME\bin\java.exe` when it is available. Note: `java -jar owl4agents.jar` may crash with `ACCESS_VIOLATION` on some Windows environments — use `node npm/bin/owl4agents.js` or `.\gradlew.bat run --args="..."` instead.

### From Clone to Smoke Test

```powershell
# 1. Build and verify
.\gradlew.bat clean buildVerification
.\gradlew.bat :modules:ontology-cli:shadowJar

# 2. Check that your environment is ready
node npm/bin/owl4agents.js setup --check

# 3. Initialize workspace and import onboarding fixtures
node npm/bin/owl4agents.js setup --init

# 4. Run onboarding smoke test
node npm/bin/owl4agents.js smoke

# 5. Generate MCP client configuration
node npm/bin/owl4agents.js mcp-config --client claude
```

`setup --check` verifies Java, Gradle, source layout, workspace, npm launcher, and runtime jar. `setup --init` creates the workspace and imports the Pizza ontology and v0.3 claim-verification golden ontology. `smoke` runs a complete onboarding cycle: fixture import, ontology list, summary, reasoner list, classification, and claim verification. `mcp-config --client claude` prints a ready-to-paste JSON configuration for Claude Desktop.

All four commands are idempotent — rerunning them reports existing resources.

### Try in 3 Minutes — v0.4 Example Packs

After building the jar, run any of these examples from the repository root:

```powershell
# Claim verification — supported, contradicted, unknown, out_of_scope
node npm/bin/owl4agents.js import test/corpus/golden/v0.3-claim-verification.owl v0.3-claim-verification --workspace claim-demo
node npm/bin/owl4agents.js reason v0.3-claim-verification --workspace claim-demo
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json --workspace claim-demo --json

# Pizza reasoning — import, summary, classify
node npm/bin/owl4agents.js import test/corpus/smoke/pizza.owl pizza --workspace pizza-demo
node npm/bin/owl4agents.js summary pizza --workspace pizza-demo

# Biomedical grounding — disease hierarchy and claim verification
node npm/bin/owl4agents.js import test/corpus/golden/v0.4-biomedical-grounding.owl v0.4-biomedical-grounding --workspace bio-demo
node npm/bin/owl4agents.js reason v0.4-biomedical-grounding --workspace bio-demo
node npm/bin/owl4agents.js verify-claim v0.4-biomedical-grounding --claim test/fixtures/v0.4/claim-bio-supported.json --workspace bio-demo --json
```

See [examples/README.md](examples/README.md) for full step-by-step commands, expected output snippets, and troubleshooting.

### v0.4 Example Showcase

| Example | What it demonstrates | Interface | Fixture | Run time |
| --- | --- | --- | --- | --- |
| [claim-verification](examples/claim-verification/) | Supported, contradicted, unknown, and out_of_scope claim checking | CLI | `test/corpus/golden/v0.3-claim-verification.owl` | ~5 seconds |
| [pizza-reasoning](examples/pizza-reasoning/) | Ontology import, summary, hierarchy, restrictions, classification | CLI | `test/corpus/smoke/pizza.owl` | ~10 seconds |
| [agent-mcp](examples/agent-mcp/) | MCP client configuration, readonly startup, tool list, tool-call samples | MCP | None (starts MCP server) | ~5 seconds |
| [biomedical-grounding](examples/biomedical-grounding/) | Biomedical-style grounding using a project-owned golden fixture | CLI | `test/corpus/golden/v0.4-biomedical-grounding.owl` | ~5 seconds |

All examples use `node npm/bin/owl4agents.js` as the CLI entry point. See each example's README for prerequisites, commands, and expected output.

### Verify Release from Fresh Checkout

```powershell
.\gradlew.bat clean buildVerification
.\gradlew.bat :modules:ontology-cli:shadowJar
node npm/test/launcher.test.js
node npm/bin/owl4agents.js --version
node npm/bin/owl4agents.js --help
```

Build the runnable CLI jar:

```powershell
.\gradlew.bat :modules:ontology-cli:shadowJar
```

The runnable jar is generated at:

```text
modules/ontology-cli/build/libs/owl4agents.jar
```

### CLI from Source

Use the local npm launcher during v0.3.1 development:

```powershell
node npm/bin/owl4agents.js init
node npm/bin/owl4agents.js import test/corpus/smoke/pizza.owl pizza
node npm/bin/owl4agents.js list
node npm/bin/owl4agents.js summary pizza
node npm/bin/owl4agents.js search pizza Pizza
node npm/bin/owl4agents.js entity pizza "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza"
node npm/bin/owl4agents.js query pizza --ask "ASK { ?s ?p ?o }"
node npm/bin/owl4agents.js context pizza "Which toppings are related to pizza?"
node npm/bin/owl4agents.js list-reasoners
node npm/bin/owl4agents.js consistency pizza --reasoner hermit
node npm/bin/owl4agents.js classify pizza --reasoner auto
node npm/bin/owl4agents.js realize pizza --reasoner hermit
```

> **Note:** `reason`, `classify`, `realize`, and `consistency` may recompute reasoner state for the current command. The `reason` command persists `reasoning-report.json` and inferred artifacts under the ontology workspace, so `report` can read the latest report in a later CLI or MCP process. Commands that need inferred facts still require reasoning artifacts to exist for the ontology.

v0.3 claim verification commands:

```powershell
# Import and reason over the deterministic v0.3 claim-verification fixture
node npm/bin/owl4agents.js import test/corpus/golden/v0.3-claim-verification.owl v0.3-claim-verification
node npm/bin/owl4agents.js reason v0.3-claim-verification --reasoner hermit

# Verify supported, unknown, and out-of-scope claims
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-unknown.json
node npm/bin/owl4agents.js verify-claim v0.3-claim-verification --claim test/fixtures/v0.3/claim-real-out-of-scope.json

# Get evidence path for a verified claim
node npm/bin/owl4agents.js evidence v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json

# Explain why a claim is unknown
node npm/bin/owl4agents.js explain-unknown v0.3-claim-verification --claim test/fixtures/v0.3/claim-unknown.json

# Detect missing or ambiguous entities in a claim
node npm/bin/owl4agents.js missing-entities v0.3-claim-verification --claim test/fixtures/v0.3/claim-real-out-of-scope.json

# Counterexamples are available only when a claim verdict is contradicted
node npm/bin/owl4agents.js counterexamples v0.3-claim-verification --claim test/fixtures/v0.3/claim-supported.json
```

Or run the fat jar directly (Linux and macOS):

```bash
java -jar modules/ontology-cli/build/libs/owl4agents.jar init
java -jar modules/ontology-cli/build/libs/owl4agents.jar import test/corpus/smoke/pizza.owl pizza
java -jar modules/ontology-cli/build/libs/owl4agents.jar summary pizza
java -jar modules/ontology-cli/build/libs/owl4agents.jar consistency pizza --reasoner hermit
```

> **Windows limitation:** `java -jar owl4agents.jar` may crash with `ACCESS_VIOLATION` on some Windows environments. This is a known JVM/OWL API interaction issue. On Windows, prefer `node npm/bin/owl4agents.js <command>` or `.\gradlew.bat run --args="<command>"` instead. CI runs `java -jar` on Ubuntu without issues.

To keep a workspace outside the default user home location:

```powershell
$env:OWL4AGENTS_HOME="D:\owl4agents-workspace"
node npm/bin/owl4agents.js init
```

The default workspace is:

```text
~/.owl4agents/workspaces/default/
```

### Deploy for Local Agents

For v0.3, the supported deployment mode is source checkout plus local launcher. The npm `bin` entry is present so the local launcher can be exercised from this repository and packaged later, but publishing a self-contained npm registry package is planned for a later release.

1. Clone or copy this repository.
2. Install Java 22 and Node.js 18+.
3. Run `.\gradlew.bat clean buildVerification`.
4. Run `.\gradlew.bat :modules:ontology-cli:shadowJar`.
5. Import at least one ontology into the workspace.
6. Point your MCP client to `node npm/bin/owl4agents.js mcp --readonly`.

Example:

```powershell
$env:OWL4AGENTS_HOME="D:\owl4agents-workspace"
node npm/bin/owl4agents.js init
node npm/bin/owl4agents.js import D:\ontologies\pizza.owl pizza
node npm/bin/owl4agents.js mcp --readonly
```

### MCP Client Configuration

The recommended way to configure MCP clients is the `mcp-config` command, which generates a ready-to-paste JSON configuration with the correct command, args, and environment for your client. For a complete MCP demo with config snippets and a sanitized tool-call transcript, see [examples/agent-mcp/](examples/agent-mcp/).

```powershell
# Generate Claude Desktop config
node npm/bin/owl4agents.js mcp-config --client claude

# Generate generic config (any stdio-based MCP client)
node npm/bin/owl4agents.js mcp-config --client generic

# Generate Cursor config
node npm/bin/owl4agents.js mcp-config --client cursor

# Override workspace location
node npm/bin/owl4agents.js mcp-config --client claude --workspace-home D:/owl4agents-workspace

# Write config to file
node npm/bin/owl4agents.js mcp-config --client claude --out claude-mcp-config.json
```

> **Note:** `codex` and `codex-cli` config templates are deferred until their public client naming and configuration format stabilize. They are not advertised as supported clients in v0.3.1.

The generated config uses the source-checkout npm launcher and sets `OWL4AGENTS_HOME` to your workspace. Unknown client names are rejected with a supported-client list.

Alternatively, you can write the config manually. Use this form for MCP clients that accept a JSON server config, such as Claude Desktop, Cursor-style MCP configuration, or other local agent runtimes:

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "node",
      "args": [
        "D:/path/to/owl4agents/npm/bin/owl4agents.js",
        "mcp",
        "--readonly"
      ],
      "env": {
        "OWL4AGENTS_HOME": "D:/owl4agents-workspace"
      }
    }
  }
}
```

On Windows, if your client has trouble with stdin/stdout through Node, use the bundled command wrapper. This wrapper runs MCP directly through Java in classpath mode (`java -cp`), bypassing Node's stdin forwarding issues and the `java -jar` ACCESS_VIOLATION crash:

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "D:/path/to/owl4agents/bin/owl4agents-mcp.cmd",
      "args": ["--readonly"],
      "env": {
        "OWL4AGENTS_HOME": "D:/owl4agents-workspace"
      }
    }
  }
}
```

v0.3 MCP is readonly. Import ontologies with the CLI first, then let agents query, reason, verify structured claims, and retrieve evidence through MCP.

Useful v0.3 MCP tools include the inherited readonly query/reasoning surface plus the new claim verification surface. The v0.3 acceptance gate verifies 16 representative MCP tools: 11 inherited core/reasoning tools plus 5 new claim verification and evidence grounding tools.

| Tool | Purpose |
| --- | --- |
| `ontology_list` | List imported ontologies |
| `ontology_summary` | Return ontology IRI, profile, imports, and entity counts |
| `ontology_search_entities` | Search classes, properties, and individuals |
| `ontology_get_class_context` | Inspect class hierarchy, equivalent classes, disjoint classes, and restrictions |
| `ontology_get_object_property_context` | Inspect object property domain, range, inverse properties, and hierarchy |
| `ontology_get_data_property_context` | Inspect data property domain, range, datatype, and hierarchy |
| `ontology_get_individual_context` | Inspect individual types and assertions |
| `ontology_validate_sparql` | Validate a SPARQL query without execution |
| `ontology_sparql_select` | Execute readonly `SELECT` |
| `ontology_sparql_ask` | Execute readonly `ASK` |
| `ontology_sparql_construct` | Execute readonly `CONSTRUCT` |
| `ontology_sparql_describe` | Execute readonly `DESCRIBE` |
| `ontology_get_qa_context` | Build ontology-grounded context for an agent question |
| `ontology_list_reasoners` | List available reasoners and capabilities |
| `ontology_run_reasoner` | Run selected reasoning tasks |
| `ontology_check_consistency` | Check ontology consistency with a reasoner |
| `ontology_classify` | Compute inferred class hierarchy |
| `ontology_realize_instances` | Infer individual class memberships |
| `ontology_get_inferred_facts` | Return inferred facts for an entity or graph scope |
| `ontology_check_entailment` | Check whether a supported structured OWL axiom is entailed |
| `ontology_get_class_restrictions` | Inspect class restrictions |
| `ontology_get_property_characteristics` | Inspect property characteristics |
| `ontology_get_datatype_constraints` | Inspect datatype facets and constraints |
| `ontology_validate_literal` | Validate a literal against datatype and known constraints |
| `ontology_check_class_compatibility` | Check whether two classes can overlap or conflict |
| `ontology_check_individual_membership` | Check explicit or inferred individual class membership |
| `ontology_check_relation_assertion` | Check explicit or inferred object property assertions |

### v0.3 Claim Verification and Evidence Grounding Tools

| Tool | Purpose |
| --- | --- |
| `ontology_verify_claim` | Verify a structured claim and return verdict: `supported`, `contradicted`, `unknown`, or `out_of_scope` |
| `ontology_get_evidence_path` | Assemble evidence path with inferred facts and reasoning report for a verified claim |
| `ontology_find_counterexamples` | Find counterexamples for a contradicted claim |
| `ontology_explain_unknown` | Explain why a claim received an unknown verdict with reason category and suggested action |
| `ontology_detect_missing_entities` | Detect matched, ambiguous, missing, and out-of-scope entities in a claim |

## Why owl4agents

Large language model agents are good at natural language reasoning, but they often lack explicit, inspectable, and reproducible semantic grounding. OWL ontologies provide structured domain knowledge, constraints, class hierarchies, property semantics, and machine-checkable reasoning.

`owl4agents` aims to connect these two worlds:

```text
Domain OWL/RDF ontologies
  -> OWL API loading and normalization
  -> mature reasoners such as HermiT, ELK, Openllet
  -> entity search and semantic context retrieval
  -> CLI and MCP tools
  -> ontology-grounded LLM agents
```

The project is not trying to implement a new OWL reasoner. It is an integration and runtime layer around mature Semantic Web tooling, with special attention to local agent workflows and reproducible research.

## Target Users

- Researchers studying ontology-enhanced LLMs and agents
- Developers building domain knowledge enhanced agents
- Knowledge engineers managing local OWL/RDF ontologies
- Advanced users who want local agents to call ontology reasoning tools

## Planned Features

- Import and manage local OWL/RDF ontologies
- Run OWL reasoners such as HermiT, ELK, and Openllet
- Check consistency, classify ontologies, and infer instance types
- Search ontology entities by label, comment, IRI, and alias
- Build ontology-grounded QA context for LLM agents
- Expose ontology tools through CLI and MCP
- Keep all data local by default
- Record snapshots and audit logs for write operations
- Support research workflows such as context export, benchmark runs, and reasoner comparison
- Provide an npm launcher for simple installation while keeping the semantic runtime in Java

## Core Design Decisions

### Integrate before reinventing

`owl4agents` should prefer mature open-source libraries whenever they already solve a problem well. The project should focus on orchestration, consistent local workflows, MCP tool design, reproducibility, and agent-oriented context construction.

Do not rebuild these foundations unless there is a strong reason:

| Capability | Preferred open-source foundation |
| --- | --- |
| OWL loading, editing, serialization | OWL API |
| OWL 2 DL reasoning | HermiT |
| OWL 2 EL classification | ELK |
| Inconsistency explanation / Pellet-style workflows | Openllet |
| RDF graph handling and SPARQL | Apache Jena ARQ |
| Ontology workflow ideas | ROBOT |
| CLI framework | Picocli |
| MCP protocol integration | Project-local JSON-RPC stdin/stdout adapter |
| Packaging Java runtime | jlink / jpackage |

The local code should mostly provide adapters, stable service contracts, permission boundaries, workspace conventions, indexes, audit logs, and research-friendly context builders.

### Java semantic runtime

Java is the planned core runtime because the mature OWL ecosystem already lives there:

- OWL API for OWL 2 loading, editing, serialization, and profile checks
- HermiT for OWL 2 DL reasoning
- ELK for high-performance OWL 2 EL classification
- Openllet for Pellet-style DL reasoning and explanation workflows
- Apache Jena ARQ for RDF and SPARQL support
- Picocli for CLI implementation
- Project-local JSON-RPC stdin/stdout adapter for exposing MCP-compatible tools to local agents

### npm as installer, not reasoning runtime

The desired future user experience is:

```bash
npx -y owl4agents mcp
```

In v0.3, use the local launcher from a source checkout after building the runnable jar:

```bash
./gradlew :modules:ontology-cli:shadowJar
node npm/bin/owl4agents.js mcp --readonly
```

Node.js only acts as the launcher and distribution entry point. The actual ontology management, indexing, SPARQL, and MCP tool behavior runs inside the Java runtime.

### One service layer for CLI and MCP

CLI and MCP should share the same application service:

```text
OntologyService
  -> CLI Adapter
  -> MCP Adapter
  -> Optional HTTP Adapter
```

This keeps behavior, tests, errors, permissions, and reasoning results consistent across interfaces.

## Troubleshooting

### Missing fat jar

If `node npm/bin/owl4agents.js` exits with a "runtime not found" error, the runnable jar has not been built:

```powershell
.\gradlew.bat :modules:ontology-cli:shadowJar
```

The jar is generated at `modules/ontology-cli/build/libs/owl4agents.jar` (approximately 46 MB). The launcher looks for this jar relative to the npm script directory, so it must exist under the project root.

### Java not found or wrong version

owl4agents requires Java 22 (as configured in the Gradle toolchain). If `java -version` reports a different version or `java` is not on PATH:

1. Install JDK 22 and set `JAVA_HOME` to the JDK directory.
2. On Windows, prefer `JAVA_HOME\bin\java.exe` over the Oracle `javapath` shim, which may point to an older JDK.
3. Verify: `"$env:JAVA_HOME\bin\java.exe" -version` should report 22.

If `JAVA_HOME` is not set, the launcher falls back to `java` on PATH, then to the Gradle wrapper as a last resort (which does not support MCP stdin).

### Reasoner selection

- **HermiT**: OWL 2 DL reasoning - consistency, classification, realization. Does not support explanation.
- **ELK**: OWL 2 EL reasoning - fast classification and consistency for EL-profile ontologies. Does not support explanation.
- **Openllet**: Explanation-oriented workflows - inconsistency explanation, unsatisfiable class explanation. Supports all OWL 2 DL reasoning tasks.
- **auto**: Profile-based selection - OWL 2 EL -> ELK, OWL 2 DL -> HermiT, explanation requested -> Openllet.

### MCP stdio issues on Windows

The MCP protocol uses stdin/stdout for JSON-RPC. On Windows, Node's `execSync` with `stdio: 'inherit'` may not forward stdin correctly for MCP sessions. The bundled `bin/owl4agents-mcp.cmd` wrapper runs MCP directly through Java in classpath mode (`java -cp`), bypassing Node's forwarding issues and the `java -jar` ACCESS_VIOLATION crash. Use this wrapper when MCP clients have trouble connecting through the npm launcher.

### Launcher startup failures

The npm launcher follows a deterministic runtime discovery order:

1. `OWL4AGENTS_RUNTIME` env var (explicit override, supports `.js` scripts for testing)
2. `modules/ontology-cli/build/libs/owl4agents.jar` (shadow jar from Gradle build)
3. `modules/ontology-cli/build/libs/ontology-cli-all.jar` (fat jar)
4. `modules/ontology-distribution/build/libs/ontology-distribution.jar`
5. `JAVA_HOME\bin\java` with classpath mode
6. `java` on PATH
7. Gradle wrapper (last resort; MCP stdin not supported)

If none of these paths are available, the launcher exits with code 2 and prints actionable guidance including the `shadowJar` build command and Java version requirement.

### Setup check failures

`setup --check` validates six environment prerequisites. If any check fails:

- **Java not found**: Install Java 22 and set `JAVA_HOME`. See the "Java not found" section above.
- **Gradle wrapper missing**: Ensure `gradlew` or `gradlew.bat` exists in the project root. Clone the full repository rather than a partial copy.
- **Source layout missing**: The project expects `modules/` and `npm/` directories under the project root. Run from within the cloned repository.
- **Workspace not writable**: The default workspace is `~/.owl4agents/workspaces/default/`. Ensure the parent directory exists and is writable, or set `OWL4AGENTS_HOME` to a writable location.
- **npm launcher files missing**: Ensure `npm/bin/owl4agents.js` and `npm/package.json` exist under the project root.
- **Runtime jar missing**: Run `.\gradlew.bat :modules:ontology-cli:shadowJar` to build the fat jar. See the "Missing fat jar" section above.

`setup --check --dry-run` reports planned remediation actions without modifying any files.

### Smoke test failures

`smoke` runs a full onboarding cycle. Common failure causes:

- **Fixture import fails**: The smoke test imports `test/corpus/smoke/pizza.owl` and `test/corpus/golden/v0.3-claim-verification.owl`. If these files are missing, the smoke test fails. Clone the full repository or download the fixtures.
- **Workspace not writable**: `smoke` defaults to a temporary workspace. If the system temp directory is not writable, use `--workspace <path>` to specify an explicit home directory. The workspace will be created under `<path>/workspaces/default/`. If you pass a path that already ends with `workspaces/default`, the parent directory is used automatically.
- **Claim verification fails**: The smoke test verifies a supported claim and an out-of-scope claim. If the golden ontology or claim fixtures are missing or corrupted, verification fails. Check `test/fixtures/v0.3/claim-smoke-supported.json` and `test/fixtures/v0.3/claim-real-out-of-scope.json`.

### mcp-config failures

- **Unknown client**: `mcp-config --client unknown` exits nonzero and prints a list of supported clients (`claude`, `cursor`, `generic`).
- **Generated JSON has empty command**: Ensure the project root is discoverable (contains `build.gradle.kts` and `settings.gradle.kts`) so the launcher path can be resolved.
- **Write flags in generated config**: The generated config never includes `--allow-write`. If you see write flags, you are using a manually constructed config, not the output of `mcp-config`.

### Release asset verification

Download the jar, checksum, and release notes from the GitHub Actions artifact or release page:

> **Windows note:** The release jar is a runnable fat jar on Linux and macOS. On Windows, it may crash with `ACCESS_VIOLATION` when invoked as `java -jar owl4agents.jar`. Use the npm launcher (`node npm/bin/owl4agents.js`) instead.

```powershell
# Verify checksum
$hash = (Get-FileHash owl4agents.jar -Algorithm SHA256).Hash
$expected = (Get-Content owl4agents.jar.sha256 -Raw).Trim().Split()[0]
if ($hash -eq $expected) { "Checksum OK" } else { "Checksum MISMATCH" }
```

## Planned Architecture

```text
+------------------------------------------------------------------+
|                         npm package                              |
|                    owl4agents launcher                           |
+-------------------------------+----------------------------------+
                                |
                                v
+------------------------------------------------------------------+
|                    owl4agents Java Runtime                       |
|                                                                  |
|  +----------------------+      +-------------------------------+ |
|  | CLI Adapter          |      | MCP Server Adapter             | |
|  | Picocli              |      | JSON-RPC stdio adapter        | |
|  +----------+-----------+      +---------------+---------------+ |
|             |                                  |                 |
|             +----------------+-----------------+                 |
|                              v                                   |
|                 Ontology Application Service                     |
|                              |                                   |
|  +---------------------------+--------------------------------+  |
|  |                           |                                |  |
|  v                           v                                v  |
| OWL API Facade          Query / SPARQL Layer          Retrieval Layer
|  |                           |                                |  |
|  v                           v                                v  |
| Reasoner Layer          Validation Layer              QA Context Builder
|  |                           |                                |  |
|  +---------------------------+----------------+---------------+  |
|                                              v                  |
|                         Evidence / Provenance Layer             |
|                                              |                  |
|                                              v                  |
|                Workspace / Storage / Index / Snapshot / Logs    |
+------------------------------------------------------------------+
```

The architecture separates concerns so new features can be added without changing every interface:

- **OWL API Facade** handles ontology loading, saving, entity resolution, axioms, profiles, and format conversion.
- **Query / SPARQL Layer** handles RDF graph access through Apache Jena ARQ.
- **Reasoner Layer** handles HermiT, ELK, Openllet, and future reasoner adapters.
- **Retrieval Layer** handles entity matching, graph neighborhoods, and context ranking.
- **Validation Layer** checks entailment, compatibility, constraints, and claim support.
- **Evidence / Provenance Layer** records why a result was returned, which axioms or triples support it, and which reasoner or graph produced it.
- **Workspace / Storage Layer** keeps local files, indexes, inferred graphs, snapshots, and audit logs.

## Technology Stack

| Layer | Technology | Purpose |
| --- | --- | --- |
| Main runtime | Java 22 | Unified semantic runtime for CLI, MCP, reasoning, and indexing |
| Build tool | Gradle Kotlin DSL | Multi-module project management |
| OWL core | OWL API | OWL 2 loading, editing, saving, profile checks |
| Ontology workflow | ROBOT core | Reusable ideas and potential JVM integration for ontology workflows |
| DL reasoner | HermiT | OWL 2 DL consistency, classification, realization |
| EL reasoner | ELK | Fast classification for large OWL 2 EL ontologies |
| Explanation / compatibility | Openllet | Inconsistency explanation and Pellet-style capabilities |
| RDF / SPARQL | Apache Jena ARQ | RDF graph handling and SPARQL queries |
| CLI | Picocli | Java command-line interface |
| MCP | Project-local JSON-RPC stdin/stdout adapter | MCP-compatible server for local agent clients |
| Local storage | Filesystem + JSONL | Portable, inspectable workspace, indexes, logs |
| Distribution | npm launcher + jlink / jpackage | Simple npm entry point with self-contained Java runtime |
| Testing | JUnit 5 | Unit, integration, and reasoner comparison tests |

### Pinned Dependency Versions

v0.3 pins the Semantic Web and runtime dependencies that affect parser, reasoner, verification, launcher, and MCP compatibility:

| Component | Maven / tool coordinate | Version | Used by |
| --- | --- | --- | --- |
| Java toolchain | JDK | 22 | All Java modules |
| Gradle wrapper | `gradle-8.14-bin.zip` | 8.14 | Build and verification |
| OWL API | `net.sourceforge.owlapi:owlapi-distribution` | 5.1.20 | OWL loading, profiles, axioms, serialization |
| HermiT | `net.sourceforge.owlapi:org.semanticweb.hermit` | 1.4.5.519 | OWL 2 DL consistency, classification, realization |
| ELK OWL API | `io.github.liveontologies:elk-owlapi` | 0.6.0 | OWL 2 EL classification and consistency |
| Openllet OWL API | `com.github.galigator.openllet:openllet-owlapi` | 2.6.5 | Explanation-oriented DL workflows |
| Apache Jena | `org.apache.jena:apache-jena-libs` | 5.3.0 | RDF graph handling and SPARQL |
| Picocli | `info.picocli:picocli` / `picocli-codegen` | 4.7.7 | CLI commands and help |
| Gson | `com.google.code.gson:gson` | 2.13.1 | JSON-RPC and structured payloads |
| Shadow plugin | `com.github.johnrengelman.shadow` | 8.1.1 | Runnable fat jar generation |
| JUnit | `org.junit:junit-bom` | 5.12.2 | Unit, integration, and acceptance tests |
| Node.js | npm launcher runtime | 18+ | Local launcher and MCP client entry point |

The reasoner module excludes transitive OWL API dependencies from HermiT, ELK, and Openllet so the project uses the pinned OWL API 5.1.20 consistently. This avoids the HermiT/OWL API method mismatch found during v0.2 acceptance testing.

## Delivery Gate

`owl4agents` treats implementation, testing, build verification, and acceptance reporting as one delivery package. A version is not considered complete when code is merely written or tests merely exist. It is complete only after the required commands run successfully and a timestamped acceptance report is produced.

For every version or OpenSpec change, developers must provide:

- A reproducible build command that works from a fresh checkout.
- A reproducible test command that runs the required unit, integration, contract, and acceptance tests.
- A completed acceptance report with date/time, environment, fixture list, scenario status, failures, skipped scenarios, and re-test notes.
- Evidence that CLI and MCP behavior are checked through the same service contracts where both interfaces expose the same capability.
- Evidence that required fixtures are present and missing required fixtures fail the test run.

The default verification command should be:

```powershell
.\gradlew.bat clean buildVerification
```

To run the CLI or MCP launcher from source after a clean checkout, also build the runnable fat jar:

```powershell
.\gradlew.bat :modules:ontology-cli:shadowJar
```

The Gradle wrapper is part of the delivery contract. The repository must include `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` unless the project explicitly replaces Gradle with another documented build entry point.

### Release Checklist

Before releasing any version, verify each step:

1. **Build**: `.\gradlew.bat clean buildVerification` exits 0
2. **Shadow jar**: `.\gradlew.bat :modules:ontology-cli:shadowJar` produces `modules/ontology-cli/build/libs/owl4agents.jar`
3. **Unit and contract tests**: `.\gradlew.bat test` exits 0
4. **Launcher tests**: `node npm/test/launcher.test.js` exits 0 when launcher changes are included
5. **Smoke commands**: `node npm/bin/owl4agents.js --help`, `--version`, and `list-reasoners` all work
6. **v0.3 claim fixtures**: supported, contradicted, unknown, out-of-scope, malformed, unsupported-type, and unknown-ontology fixtures pass their expected gates
7. **v0.4 example validation**: each required example pack (claim-verification, pizza-reasoning, agent-mcp, biomedical-grounding) passes manifest, fixture, execution, sanitization, and drift checks
8. **Example manifest checks**: every `examples/*/example.yaml` contains required fields (id, title, description, interfaces, fixtures, commands, expectedOutputs, ciRequired, attribution) and fixture paths reference existing files
9. **README link checks**: root README links to every required example and MCP section references `examples/agent-mcp/`; no stale or broken links
10. **Fixture attribution checks**: `test/corpus/README.md` documents source, attribution, and license for every required fixture
11. **CLI/MCP parity**: overlapping v0.3 tools return equivalent verdicts, evidence shapes, and error codes
12. **Version alignment**: Gradle version, npm package.json version, Picocli `version` attribute, and MCP `serverInfo.version` all match
13. **Acceptance report**: Timestamped report under `reports/acceptance/` with environment, commands, gate results, defects, retest notes, skipped scenarios, and verdict
14. **Git hygiene**: required source, tests, contracts, fixtures, and examples are tracked; local reports, OpenSpec files, private ontologies, generated classes, build outputs, and `temp/examples/` are ignored
15. **Git tag**: Tag the release commit, for example `v0.4.0`
16. **Push**: Push the commit and tag to the remote

Acceptance reports are stored locally under:

```text
reports/acceptance/
```

This directory is intentionally ignored by Git so local test evidence and environment details are preserved without being published to GitHub.

### Definition of Done

A change or version cannot be marked done unless all of the following are true:

- The canonical build/test command exits successfully.
- Required acceptance fixtures are present; missing required fixtures fail the run instead of being silently skipped.
- Tests fail on placeholder success responses, empty MCP payloads, broken catalog readback, and unsupported command paths.
- CLI and MCP parity tests pass for overlapping readonly operations.
- npm launcher smoke tests pass for help output, version output, command forwarding, exit code semantics, runtime discovery, and MCP startup path verification.
- The acceptance report records all failures and skipped scenarios explicitly.
- The final report path and command output summary are included in the handoff.

## Planned Modules

```text
modules/
  ontology-core/
  ontology-owlapi/
  ontology-reasoner/
  ontology-query/
  ontology-retrieval/
  ontology-validation/
  ontology-provenance/
  ontology-storage/
  ontology-cli/
  ontology-mcp/
  ontology-distribution/

npm/
  package.json
  bin/owl4agents.js
  platform-packages/
```

| Module | Responsibility |
| --- | --- |
| `ontology-core` | Domain model, application service interfaces, result and error models |
| `ontology-owlapi` | OWL API wrapper for loading, saving, entity resolving, profile checks, axiom editing |
| `ontology-reasoner` | Unified adapters for HermiT, ELK, and Openllet |
| `ontology-query` | Apache Jena ARQ query execution, SPARQL validation, graph scope selection |
| `ontology-retrieval` | Entity search, graph neighborhood, hierarchy context, relation context, QA context construction |
| `ontology-validation` | Entailment checks, class compatibility, literal validation, claim verification, and evidence grounding |
| `ontology-provenance` | Source tracking, reasoning reports, support/contradiction traces (future: extended provenance) |
| `ontology-storage` | Local workspace, catalog, metadata, snapshots, indexes, audit logs |
| `ontology-cli` | Picocli-based command adapter |
| `ontology-mcp` | MCP-compatible JSON-RPC stdin/stdout tool adapter |
| `ontology-distribution` | jlink / jpackage packaging |
| `npm` | npm launcher and platform package entry points |

## Planned CLI

The first stable CLI should look like this:

```bash
owl4agents init
owl4agents import ./medical.owl --name medical
owl4agents list
owl4agents summary medical
owl4agents search medical "myocardial infarction"
owl4agents entity medical "ex:MyocardialInfarction"
owl4agents neighborhood medical "ex:MyocardialInfarction" --depth 2
owl4agents reason medical --reasoner auto
owl4agents consistency medical --reasoner hermit
owl4agents explain medical --entity ex:MyocardialInfarction
owl4agents context medical "What symptoms are associated with myocardial infarction?"
owl4agents export medical --format ttl --out ./medical.ttl
owl4agents mcp --workspace default --readonly
```

Command groups:

```text
workspace:
  init
  workspace list
  workspace use
  workspace remove

ontology:
  import
  list
  summary
  export
  diff
  snapshot
  rollback

entity:
  search
  entity
  classes
  properties
  individuals
  neighborhood

reasoning:
  reason
  consistency
  classify
  realize
  entailment
  explain

agent:
  context
  mcp
  inspect-tools

debug:
  doctor
  version
  env
```

## Planned MCP Tools

The initial MCP server should be read-only by default:

```bash
owl4agents mcp --readonly
```

The MCP surface should be broad enough for real agent workflows, but staged carefully. The long-term tool design is organized by semantic layer, not by implementation module. That keeps the protocol stable while internals evolve.

### Tool design principles

- Prefer precise tools for common agent reasoning tasks, not only generic SPARQL.
- Keep SPARQL available as an escape hatch for advanced graph queries.
- Separate read tools, reasoning tools, verification tools, and write tools.
- Return structured evidence whenever possible: source axioms, triples, inferred facts, graph scope, and reasoner metadata.
- Distinguish `supported`, `contradicted`, `unknown`, and `out_of_scope` instead of forcing a false boolean.
- Make write tools unavailable unless the server is started with `--allow-write`.

### Agent anti-hallucination loop

The intended agent workflow is:

```text
User question
  -> retrieve candidate entities and ontology context
  -> inspect class/property/individual semantics
  -> run SPARQL or reasoner checks when needed
  -> draft answer
  -> verify claims against ontology evidence
  -> return answer with supported claims, uncertainty, and evidence paths
```

The most important tools for reducing hallucinations are not only context retrieval tools. They are entailment, compatibility, constraint, and claim verification tools.

### Workspace and ontology tools

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_list` | v0.1 | List local ontologies in a workspace |
| `ontology_summary` | v0.1 | Return ontology metadata, profile, imports, and entity counts |
| `ontology_get_metadata` | v0.1 | Return ontology IRI, version IRI, source path, canonical path, and timestamps |
| `ontology_get_imports` | v0.2 | Inspect import closure and imported ontology metadata |
| `ontology_get_profile` | v0.1 | Return OWL profile information and profile violations |
| `ontology_list_graphs` | v0.1 to v0.2 | List queryable graph scopes (`explicit` in v0.1; adds `inferred`, `union` in v0.2) |

### Class tools

These tools cover taxonomy and class-level axioms.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_get_classes` | v0.1 | List or filter classes |
| `ontology_get_class_context` | v0.1 | Return labels, comments, parents, children, equivalent classes, disjoint classes, and restrictions |
| `ontology_get_subclasses` | v0.1 | Return direct or transitive subclasses |
| `ontology_get_superclasses` | v0.1 | Return direct or transitive superclasses |
| `ontology_get_equivalent_classes` | v0.1 | Return equivalent class axioms |
| `ontology_get_disjoint_classes` | v0.1 | Return disjoint class axioms |
| `ontology_get_class_restrictions` | v0.2 | Return `someValuesFrom`, `allValuesFrom`, cardinality, `hasValue`, and datatype restrictions |
| `ontology_check_class_compatibility` | v0.2 | Check whether two classes can overlap or are disjoint / unsatisfiable together |
| `ontology_get_unsat_classes` | v0.2 | Return unsatisfiable classes after reasoning |
| `ontology_explain_unsat_class` | v0.2 | Explain why a class is unsatisfiable |

### Object property tools

Object properties are essential for relation correctness. They prevent agents from inventing relations or reversing relation direction.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_get_object_properties` | v0.1 | List object properties |
| `ontology_get_object_property_context` | v0.1 | Return labels, comments, domain, range, hierarchy, inverse properties, and characteristics |
| `ontology_get_property_domain_range` | v0.1 | Return domain and range for object or data properties |
| `ontology_get_inverse_properties` | v0.1 | Return inverse object property axioms |
| `ontology_get_equivalent_properties` | v0.2 | Return equivalent property axioms |
| `ontology_get_disjoint_properties` | v0.2 | Return disjoint property axioms |
| `ontology_get_property_characteristics` | v0.2 | Return functional, inverse functional, transitive, symmetric, asymmetric, reflexive, and irreflexive flags |
| `ontology_get_property_chain_axioms` | v0.7 | Return property chain axioms |
| `ontology_find_relations_between_entities` | v0.2 | Find object property relations between two entities or individuals |

### Data property and datatype tools

Data properties help agents avoid wrong values, wrong units, invalid datatypes, and invented measurements.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_get_data_properties` | v0.1 | List data properties |
| `ontology_get_data_property_context` | v0.1 | Return labels, comments, domain, range, datatype, and hierarchy |
| `ontology_get_datatype_constraints` | v0.2 | Return datatype facets such as min/max, pattern, enumeration, and cardinality constraints |
| `ontology_get_data_property_assertions` | v0.2 | Return literal values for an individual |
| `ontology_validate_literal` | v0.2 | Validate a literal against datatype, range, and known constraints |
| `ontology_find_individuals_by_data_property` | v0.7 | Find individuals by data property value or value range |

### Individual and assertion tools

Individuals are where many agent claims become concrete and checkable.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_get_individuals` | v0.1 | List or filter individuals |
| `ontology_get_individual_context` | v0.1 | Return labels, comments, explicit types, inferred types, object property assertions, and data property assertions |
| `ontology_get_individual_types` | v0.1 | Return explicit and inferred class memberships |
| `ontology_get_object_property_assertions` | v0.2 | Return object property facts for an individual |
| `ontology_get_same_individuals` | v0.2 | Return `owl:sameAs` individuals |
| `ontology_get_different_individuals` | v0.2 | Return `owl:differentFrom` individuals |
| `ontology_check_individual_membership` | v0.2 | Check whether an individual belongs to a class |
| `ontology_check_relation_assertion` | v0.2 | Check whether a relation between two individuals is asserted or entailed |

### SPARQL and RDF tools

SPARQL should be part of the MCP design from the beginning, because many ontology users need direct graph queries beyond prebuilt context tools.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_sparql_select` | v0.1 | Run a read-only SPARQL `SELECT` query over explicit, inferred, or union graph |
| `ontology_sparql_ask` | v0.1 | Run a SPARQL `ASK` query |
| `ontology_sparql_construct` | v0.1 | Run a SPARQL `CONSTRUCT` query and return RDF triples |
| `ontology_sparql_describe` | v0.1 | Run a SPARQL `DESCRIBE` query for one or more resources |
| `ontology_validate_sparql` | v0.1 | Parse and validate a SPARQL query before execution |

SPARQL safety rules:

- v0.1 allows read-only query forms only: `SELECT`, `ASK`, `CONSTRUCT`, `DESCRIBE`
- Update operations such as `INSERT`, `DELETE`, `LOAD`, `CLEAR`, `CREATE`, `DROP`, and `MOVE` are blocked
- Query execution must support timeout and result limits
- File access from SPARQL must be disabled unless explicitly designed later

### Reasoning tools

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_list_reasoners` | v0.2 | List installed reasoners and capabilities |
| `ontology_run_reasoner` | v0.2 | Run selected reasoning tasks |
| `ontology_check_consistency` | v0.1 to v0.2 | Check ontology consistency (v0.2: extended with reasoner integration) |
| `ontology_classify` | v0.2 | Compute inferred class hierarchy |
| `ontology_realize_instances` | v0.2 | Infer individual class memberships |
| `ontology_check_entailment` | v0.2 | Check whether a supported structured OWL axiom is entailed |
| `ontology_get_inferred_facts` | v0.2 | Return inferred axioms or triples for an entity or graph scope |
| `ontology_explain_entailment` | v0.7 | Explain why a conclusion is entailed |
| `ontology_explain_inconsistency` | v0.2 | Explain inconsistent axioms |
| `ontology_get_reasoning_report` | v0.2 | Return the latest reasoning report |

### Validation and anti-hallucination tools

These are the tools that turn ontology access into answer verification.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_verify_claim` | v0.3 released | Verify one structured claim as `supported`, `contradicted`, `unknown`, or `out_of_scope` |
| `ontology_verify_answer` | v0.5 | Verify multiple claims extracted from an agent answer |
| `ontology_ground_claims` | v0.5 | Attach evidence paths, axioms, triples, and query results to claims |
| `ontology_find_counterexamples` | v0.3 released | Find facts or axioms that contradict a claim |
| `ontology_check_constraints` | v0.7 | Validate data with SHACL or ontology-derived constraints |
| `ontology_explain_unknown` | v0.3 released | Explain why a claim cannot be verified |
| `ontology_assess_answer_coverage` | v0.5 | Check whether an answer missed important classes, relations, restrictions, or known exceptions |

### Retrieval and QA context tools

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_search_entities` | v0.1 | Search classes, properties, and individuals |
| `ontology_get_entity_context` | v0.1 | Return labels, comments, class/property/individual context, and related facts |
| `ontology_get_graph_neighborhood` | v0.1 | Return local graph neighborhood around an entity |
| `ontology_get_qa_context` | v0.1 | Build ontology-grounded context for an agent question |
| `ontology_get_evidence_path` | v0.3 released | Return the path from matched entities to supporting facts |
| `ontology_get_scope` | v0.2 | Describe ontology domain coverage and known limitations |
| `ontology_detect_missing_entities` | v0.3 released | Find terms in a question that are not covered by the ontology |

### Editing, snapshot, and audit tools

These tools require `--allow-write`.

| Tool | Stage | Description |
| --- | --- | --- |
| `ontology_import` | v0.8 | Import an ontology into a workspace through write-enabled MCP |
| `ontology_export` | v0.8 | Export ontology in a selected format |
| `ontology_edit_axiom` | v0.8 | Apply structured ontology edits |
| `ontology_diff` | v0.8 | Show differences between snapshots or ontology versions |
| `ontology_snapshot` | v0.8 | Create a named snapshot |
| `ontology_rollback` | v0.8 | Roll back to a snapshot |
| `ontology_get_audit_log` | v0.8 | Inspect write operations and MCP tool calls |

Example MCP client configuration:

```json
{
  "mcpServers": {
    "owl4agents": {
      "command": "npx",
      "args": ["-y", "owl4agents", "mcp", "--readonly"]
    }
  }
}
```

## Local Workspace Design

The default workspace should be inspectable and reproducible:

```text
~/.owl4agents/
  config.yaml
  workspaces/
    default/
      workspace.yaml
      catalog.json
      ontologies/
        medical/
          source/original.owl
          canonical/ontology.owl
          inferred/
            hermit.owl
            elk.owl
            openllet.owl
            reasoning-report.json
          index/
            entities.jsonl
            labels.jsonl
            hierarchy.jsonl
            class-axioms.jsonl
            properties.jsonl
            property-axioms.jsonl
            individual-assertions.jsonl
            inferred-facts.jsonl
            evidence-paths.jsonl
          snapshots/
          metadata.json
      logs/
        cli-operations.jsonl
        mcp-tool-calls.jsonl
```

Storage principles:

- Original files are never overwritten
- Canonical files, inferred graphs, and indexes are rebuildable
- Write operations create snapshots
- Agent writes are audit logged
- MCP tools must not expose unrestricted file reads

## Research Workflow

`owl4agents` should help researchers compare agent behavior with and without ontology grounding.

For anti-hallucination work, tool results should encourage agents to answer with an explicit verification state:

```json
{
  "answer": "...",
  "claims": [
    {
      "text": "Myocardial infarction is a cardiovascular disease.",
      "status": "supported",
      "evidence": [
        {
          "type": "inferred_axiom",
          "value": "SubClassOf(ex:MyocardialInfarction ex:CardiovascularDisease)",
          "reasoner": "hermit"
        }
      ]
    },
    {
      "text": "The ontology proves every symptom of myocardial infarction.",
      "status": "unknown",
      "explanation": "The ontology has related symptom properties, but it does not contain a complete closure axiom for all symptoms."
    }
  ]
}
```

This is the practical goal: the agent should not merely receive more context. It should be able to check whether its claims are supported, contradicted, unknown, or outside the ontology scope.

Example experiment dimensions:

- No ontology context
- Explicit ontology context only
- Explicit + inferred ontology context
- HermiT vs ELK vs Openllet generated context
- Different context compression strategies
- Different entity retrieval and ranking methods

Planned context export:

```bash
owl4agents context-batch medical ./questions.jsonl \
  --reasoner hermit \
  --out ./contexts.jsonl
```

Example output:

```json
{
  "questionId": "q001",
  "question": "What symptoms are associated with myocardial infarction?",
  "matchedEntities": [],
  "context": "...",
  "reasoner": "hermit",
  "includeInferred": true
}
```

## Test Corpus

`owl4agents` uses a layered OWL/RDF test corpus. No single ontology covers all OWL 2 constructs, reasoning behavior, ABox assertions, SPARQL, realistic labels, and agent QA context, so the corpus is split by purpose.

### Corpus Layout

```text
test/corpus/
  smoke/
  golden/
  conformance/owl2/
  constructs/
  realworld/
  benchmarks/
  large/
```

| Suite | Purpose | Run frequency |
| --- | --- | --- |
| Smoke | Small public ontologies for import/search/context sanity checks | Every commit |
| Golden | Hand-made deterministic OWL feature tests with expected JSON | Every commit |
| Conformance | W3C OWL 2 semantics, entailment, consistency, and profile tests | Selected subset |
| Constructs | OWL language construct coverage, including properties and restrictions | Selected subset |
| Realworld | Public scientific ontology import/search/context tests | Nightly or release |
| Benchmarks | OWL2Bench, LUBM, and ORE-style performance and scale tests | Manual or benchmark |
| Large | GO/HPO/Mondo/Uberon/ChEBI full-size stress tests | Manual |

### Included Public Fixtures

The following small smoke fixtures have been downloaded locally:

| File | Purpose |
| --- | --- |
| `test/corpus/smoke/pizza.owl` | Class hierarchy, object properties, restrictions, equivalent/disjoint classes |
| `test/corpus/smoke/bfo.owl` | Upper ontology, imports/profile/top-level hierarchy smoke test |
| `test/corpus/benchmarks/lubm/univ-bench.owl` | University-domain classes, object properties, data properties, benchmark schema |
| `test/corpus/benchmarks/owl2bench/UNIV-BENCH-OWL2DL.owl` | OWL 2 DL construct-rich TBox for reasoner and axiom coverage |
| `test/corpus/benchmarks/owl2bench/UNIV-BENCH-OWL2EL.owl` | OWL 2 EL profile TBox for profile and EL reasoner tests |
| `test/corpus/benchmarks/owl2bench/UNIV-BENCH-OWL2QL.owl` | OWL 2 QL profile TBox for profile tests |
| `test/corpus/benchmarks/owl2bench/UNIV-BENCH-OWL2RL.owl` | OWL 2 RL profile TBox for profile tests |

### Golden Ontologies

The most stable acceptance tests should be hand-made golden ontologies. Each file should have a matching `.expected.json`.

```text
01-subclass-transitive.owl
02-equivalent-classes.owl
03-disjoint-classes-inconsistent.owl
04-unsatisfiable-class.owl
05-object-property-domain-range.owl
06-data-property-domain-range.owl
07-inverse-property.owl
08-transitive-property.owl
09-functional-object-property.owl
10-functional-data-property.owl
11-cardinality-restriction.owl
12-some-values-from.owl
13-all-values-from.owl
14-has-value.owl
15-same-individual.owl
16-different-individuals.owl
17-negative-object-property-assertion.owl
18-negative-data-property-assertion.owl
19-datatype-restriction.owl
20-import-closure.owl
```

### External Corpus Sources

| Source | Suggested location | Use |
| --- | --- | --- |
| W3C OWL 2 Test Case Repository | `test/corpus/conformance/owl2/` | Standard OWL 2 conformance, entailment, consistency |
| OWL2Bench | `test/corpus/benchmarks/owl2bench/` | OWL 2 construct coverage, ABox scale, query performance |
| LUBM | `test/corpus/benchmarks/lubm/` | ABox assertions, SPARQL, university-domain benchmark |
| GO basic | `test/corpus/realworld/go-basic.owl` | Large class hierarchy and labels |
| HPO | `test/corpus/realworld/hp.owl` | Phenotype search and biomedical QA context |
| OBI | `test/corpus/realworld/obi.owl` | Scientific investigation workflow context |
| ORE 2015 | `test/corpus/large/ore/` | Reasoner benchmark; large/manual |
| Mondo / Uberon / ChEBI | `test/corpus/large/` | Large biomedical stress tests; manual |

Use the helper script for supported downloads:

```powershell
.\scripts\download-test-corpus.ps1 -Suite smoke
.\scripts\download-test-corpus.ps1 -Suite realworld-small
.\scripts\download-test-corpus.ps1 -Suite benchmarks
.\scripts\download-test-corpus.ps1 -Suite all -IncludeLarge
```

Large downloads are opt-in because some resources are hundreds of MB or more.

## Roadmap

The roadmap is organized by user-visible capability. Version numbers start at v0.1 so each milestone can be treated as a publishable project state.

After the v0.1 to v0.3 releases, the roadmap intentionally prioritizes adoption before deeper feature expansion. Reasoning, claim verification, and evidence grounding now exist; the next releases should lower installation and MCP-configuration friction, provide benchmark examples that show the platform's value, and then expand into agent workflows, evaluation, ontology workflow integrations, and controlled write operations. Major feature releases should be followed by a small hardening release when the new surface affects CLI/MCP contracts, launcher behavior, evidence payloads, or acceptance gates.

### v0.1 Local Ontology Reader and Readonly MCP

Goal: make a local OWL/RDF ontology inspectable by both CLI and MCP.

Planned support:

- [x] Java 22 + Gradle multi-module skeleton
- [x] Integration checks for OWL API, HermiT, Apache Jena ARQ, Picocli, and the MCP-compatible JSON-RPC adapter
- [x] Local workspace and ontology catalog
- [x] OWL/RDF import through OWL API
- [x] Ontology summary: IRI, version IRI, imports, profile, entity counts
- [x] Entity search by IRI, prefixed name, label, comment, and alias
- [x] Class context: subclasses, superclasses, equivalent classes, disjoint classes
- [x] Object property context: domain, range, inverse properties, hierarchy
- [x] Data property context: domain, range, datatype, hierarchy
- [x] Individual context: explicit types, labels, comments, basic assertions
- [x] Readonly SPARQL tools: `SELECT`, `ASK`, `CONSTRUCT`, `DESCRIBE`
- [x] SPARQL validation, timeout, graph scope, and result limit controls
- [x] Basic QA context generation from class/property/individual context
- [x] CLI commands: `init`, `import`, `list`, `summary`, `search`, `entity`, `query`, `context`, `mcp`
- [x] Readonly MCP server exposing v0.1 tools
- [x] Initial npm launcher for local runtime invocation

### v0.2 Reasoning and Semantic Expansion

Goal: add mature OWL reasoning and expose inferred semantic structure.

Released support:

- [x] HermiT consistency checking, classification, and realization
- [x] ELK adapter for OWL 2 EL classification
- [x] Openllet adapter for explanation-oriented workflows
- [x] `auto` reasoner selection based on OWL profile and requested task
- [x] Inferred graph and inferred fact result models
- [x] Reasoning reports with reasoner, profile, timings, warnings, and inferred axiom counts
- [x] Unsatisfiable class detection
- [x] Structured entailment checks for supported OWL axiom types
- [x] Class compatibility checks based on disjointness and satisfiability
- [x] Individual realization and inferred individual types
- [x] Relation assertion checks for object properties
- [x] Data property literal validation against datatype and known constraints
- [x] Reasoner and v0.1 regression acceptance tests

### v0.2.1 Stabilization and Release Hardening

Goal: make v0.2 easier to install, debug, and maintain without expanding the feature surface.

Released support:

- [x] Review and classify Gradle deprecation warnings; all accepted (shadow plugin + OWL API deprecation + unchecked generics)
- [x] Improved launcher error messages with deterministic exit codes for missing runtime, missing jar, missing Java
- [x] `--version` support in npm launcher reading from package.json
- [x] Strict npm launcher smoke tests for help, version, runtime discovery, exit code preservation, and MCP startup path
- [x] Dependency matrix validation: OWL API 5.1.20, HermiT 1.4.5.519, ELK (liveontologies) 0.6.0, Openllet 2.6.5, Jena 5.3.0, Picocli 4.7.7, Gson 2.13.1
- [x] Release checklist covering build, `shadowJar`, launcher smoke tests, acceptance report, tag, and push
- [x] Troubleshooting for Java, `JAVA_HOME`, missing fat jar, reasoner selection, MCP stdio, and launcher failures
- [x] v0.1 and v0.2 regression gates remain green

### v0.3 Claim Verification and Evidence Grounding

Goal: turn ontology access into anti-hallucination verification tools.

Released support:

- [x] Structured claim schema for subclass, membership, relation, consistency, property domain/range, and literal validation claims
- [x] Claim-level verification built on v0.2 entailment, membership, relation, compatibility, literal validation, and scope primitives
- [x] `ontology_verify_claim` returning `supported`, `contradicted`, `unknown`, or `out_of_scope`
- [x] Evidence path generation for supported claims using source axioms, explicit triples, inferred facts, and reasoner metadata
- [x] Counterexample search for contradicted claims
- [x] Unknown explanation for missing entities, missing relations, unsupported profiles, or insufficient axioms
- [x] Missing entity detection with matched, ambiguous, missing, and out-of-scope classifications
- [x] Evidence truncation using `maxEvidence` option
- [x] CLI commands: `verify-claim`, `evidence`, `counterexamples`, `explain-unknown`, `missing-entities`
- [x] MCP tools: `ontology_verify_claim`, `ontology_get_evidence_path`, `ontology_find_counterexamples`, `ontology_explain_unknown`, `ontology_detect_missing_entities`
- [x] CLI and MCP parity for claim verification results
- [x] No free-text claim parsing in v0.3; agents must pass structured claims
- [x] No ontology write operations in v0.3

### v0.3.1 Usability and Release Hardening

Goal: let a new GitHub user run owl4agents and connect MCP in minutes.

Released support:

- [x] One-command local setup for Windows, macOS, and Linux source checkouts (`setup --check`, `setup --init`)
- [x] Launcher flow that can build, locate, or diagnose `owl4agents.jar` predictably (`setup --check` validates runtime jar)
- [x] MCP configuration generator for common local agent clients (`mcp-config --client claude/cursor/generic`)
- [x] First-run workspace initialization and smoke test (`setup --init`, `smoke`)
- [x] Example ontology bootstrap using Pizza and the v0.3 claim verification fixture (`setup --init` imports both)
- [x] Productized diagnostics for Java version, `JAVA_HOME`, missing jar, stale workspace state, and MCP stdio issues (`setup --check` covers all six checks)
- [x] GitHub Actions CI for build, unit tests, launcher smoke tests, and release-readiness checks
- [x] GitHub Release assets for runnable jar, checksum, and release notes
- [x] Public acceptance report template and automated release checklist
- [x] Keep v0.3.1 free of new OWL feature surface, SHACL, ROBOT integration, and write operations

### v0.4 Benchmark Examples and Demo Packs

Goal: make owl4agents easy to understand, demo, and promote through copy-pasteable scenarios before adding deeper product surface.

Released support:

- [x] `examples/` directory with runnable CLI and MCP demos
- [x] `examples/claim-verification` showing hallucination checking with supported, contradicted, unknown, and out_of_scope claims
- [x] `examples/pizza-reasoning` showing class hierarchy, restrictions, disjointness, and inferred facts
- [x] `examples/agent-mcp` showing Claude/Cursor/generic MCP configuration and sanitized tool-call transcript samples
- [x] `examples/biomedical-grounding` showing disease hierarchy and claim verification using project-owned golden fixture
- [x] Each example includes manifest (example.yaml), README, commands, claim JSON or references, expected output assertions, and run scripts
- [x] README front page links to "Try in 3 minutes" examples and showcase table
- [x] Demo validation tests (manifest, fixture, drift, sanitization) to keep examples from drifting
- [x] CI runs v0.4 example validation as a release gate
- [x] No CLI `examples`/`demo` discovery command — examples are script-driven (deferred to future release)
- [x] No write operations; examples remain reproducible and readonly
- [x] No large external ontology downloads required for default CI — biomedical fixture is project-owned

### v0.5 Agent Claim Workflow

Goal: move from individual tools to repeatable agent workflows for claim extraction, verification, and evidence-grounded answers.

Planned support:

- [ ] Natural-language-to-structured-claim handoff schema for agents, with examples but no hidden LLM dependency
- [ ] Batch claim verification for answer-level checking
- [ ] Evidence compact context format optimized for LLM consumption
- [ ] Agent self-check workflow: extract claims, verify claims, explain unknowns, cite evidence, and refuse out-of-scope claims
- [ ] Coverage assessment for missed entities, relations, restrictions, and known exceptions
- [ ] Answer verification reports with per-claim verdicts and evidence summaries
- [ ] CLI and MCP workflow templates for common agent loops
- [ ] Regression fixtures from real agent-generated claims
- [ ] Clear failure modes for malformed claims, unsupported natural-language extraction, and missing ontology scope
- [ ] Optional CLI `examples`/`demo` discovery command based on v0.4 example pack experience

Goal: move from individual tools to repeatable agent workflows for claim extraction, verification, and evidence-grounded answers.

Planned support:

- [ ] Natural-language-to-structured-claim handoff schema for agents, with examples but no hidden LLM dependency
- [ ] Batch claim verification for answer-level checking
- [ ] Evidence compact context format optimized for LLM consumption
- [ ] Agent self-check workflow: extract claims, verify claims, explain unknowns, cite evidence, and refuse out-of-scope claims
- [ ] Coverage assessment for missed entities, relations, restrictions, and known exceptions
- [ ] Answer verification reports with per-claim verdicts and evidence summaries
- [ ] CLI and MCP workflow templates for common agent loops
- [ ] Regression fixtures from real agent-generated claims
- [ ] Clear failure modes for malformed claims, unsupported natural-language extraction, and missing ontology scope

### v0.6 Research Evaluation and Benchmarks

Goal: prove and compare the value of ontology grounding with reproducible experiments.

Planned support:

- [ ] `context-batch` for question sets
- [ ] Context export JSONL format
- [ ] Benchmark runner for golden, Pizza, OWL2Bench, LUBM, and selected real-world corpus subsets
- [ ] Reasoner comparison reports
- [ ] Grounded vs ungrounded QA evaluation helper
- [ ] Hallucination reduction benchmark using structured claims
- [ ] Reproducible experiment configuration files
- [ ] Example benchmark packs for small, medium, inconsistent, OWL 2 EL, and OWL 2 DL ontologies
- [ ] Generated reports that can be attached to papers, issues, or release notes

### v0.7 Ontology Workflow Integrations

Goal: integrate optional ontology workflow tooling after installation, examples, and agent workflows are stable.

Planned support:

- [ ] Evaluate optional ROBOT integration for reusable ontology workflow operations and benchmark preprocessing
- [ ] Evaluate SHACL support for explicit constraint validation workflows
- [ ] Property chain inspection and advanced OWL axiom browsing
- [ ] Ontology preprocessing pipeline for imports, subsets, profile checks, and benchmark packs
- [ ] Import closure management and ontology diff planning
- [ ] Integration tests proving optional tools are detected, versioned, and fail gracefully when unavailable

### v0.8 Controlled Editing, Snapshots, and Audit

Goal: safely allow controlled ontology updates from CLI and MCP after readonly reasoning, verification, examples, and evaluation are stable.

Planned support:

- [ ] Controlled import/export workflows
- [ ] Structured ontology edit operations for classes, properties, individuals, labels, comments, and axioms
- [ ] Add/remove subclass, equivalent class, disjoint class, domain, range, and assertion axioms
- [ ] Snapshot generation before every write
- [ ] Diff between snapshots or ontology versions
- [ ] Rollback to a previous snapshot
- [ ] MCP `--allow-write` mode with explicit opt-in
- [ ] Dry-run mode for proposed edits
- [ ] Write audit log for CLI operations and MCP tool calls
- [ ] Security tests for readonly mode, write mode, file access limits, dry-run, and rollback behavior

### v1.0 Stable Release

Goal: provide a stable local ontology runtime for agent developers and researchers.

Planned support:

- [ ] Stable CLI command surface
- [ ] Stable MCP tool schemas
- [ ] Complete user documentation
- [ ] Complete developer documentation
- [ ] npm launcher package
- [ ] Platform runtime packages
- [ ] Windows, macOS, and Linux support
- [ ] Example datasets and ontologies
- [ ] Paper / experiment reproduction templates
- [ ] Release automation
- [ ] Backward compatibility policy for workspace format and MCP schemas

## Long-Term Ideas

- [ ] Optional HTTP API adapter over the same `OntologyService`
- [ ] Optional Jena TDB2-backed large RDF graph mode
- [ ] Better ontology context compression strategies
- [ ] Entity linking and alias expansion for noisy user questions
- [ ] Reasoning cache invalidation strategy
- [ ] Graph visualization export
- [ ] Integration examples for Claude Desktop, Cursor, and local agent frameworks
- [ ] Domain-specific benchmark packs, such as biomedical, legal, industrial, and research ontologies

## Repository Contents

```text
README.md
build.gradle.kts
settings.gradle.kts
gradlew / gradlew.bat
gradle/wrapper/
modules/
  ontology-core/
  ontology-owlapi/
  ontology-query/
  ontology-retrieval/
  ontology-storage/
  ontology-cli/
  ontology-mcp/
  ontology-distribution/
npm/
  bin/owl4agents.js
  test/launcher.test.js
bin/
  owl4agents-mcp.cmd
scripts/
  download-test-corpus.ps1
test/
  corpus/
  contracts/
```

Local OpenSpec, design notes, acceptance reports, and temporary retrospectives may exist in this workspace, but they are intentionally ignored by Git for public release workflows.

## License

Apache License 2.0. See [LICENSE](./LICENSE).

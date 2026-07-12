# owl4agents — Features and Tool Reference

> **Version:** v0.8.4 (released 2026-07-11, claim verification performance optimization: 7 decisions including EntitySignatureCache, per-request ontology single loading, asserted axiom indexing, inferred hierarchy index, OntologyCache TTL window).
> **Audience:** programmers who want to **use** owl4agents (CLI or MCP) and understand what each command / tool does, what it takes as input, and what it returns. We assume you're a CS graduate — comfortable with JSON, HTTP, regex, and reading API docs — but you may or may not have touched OWL or SPARQL before.
> **Pair this with:** [README.md](README.md) for the elevator pitch and 5-minute quick start. This file is the deep reference.

---

## Languages / 语言版本

- **English** (this file)
- [功能参考(中文)](FEATURES.zh-CN.md) — full Chinese translation of this document
- [English README](README.md) | [简体中文 README](README.zh-CN.md)

---

## Table of contents

1. [§0 How to read this document](#0-how-to-read-this-document)
2. [§1 OWL and SPARQL in 5 minutes — the primer you actually need](#1-owl-and-sparql-in-5-minutes--the-primer-you-actually-need)
3. [§2 Architecture and module breakdown](#2-architecture-and-module-breakdown)
4. [§3 A real walkthrough — load an ontology, ask questions, verify a claim](#3-a-real-walkthrough--load-an-ontology-ask-questions-verify-a-claim)
5. [§4 CLI reference — every command, with real input and real output](#4-cli-reference--every-command-with-real-input-and-real-output)
6. [§5 MCP tool reference — every tool, with real JSON-RPC request and response](#5-mcp-tool-reference--every-tool-with-real-json-rpc-request-and-response)
7. [§6 Claim verification and evidence grounding — wiring owl4agents into an LLM answer pipeline](#6-claim-verification-and-evidence-grounding--wiring-owl4agents-into-an-llm-answer-pipeline)
8. [§7 Deployment, environment, integration](#7-deployment-environment-integration)
9. [§8 Reasoner integration — which reasoner to pick and when](#8-reasoner-integration--which-reasoner-to-pick-and-when)
10. [§9 Error codes, troubleshooting, and limits](#9-error-codes-troubleshooting-and-limits)
11. [§10 Testing, quality data, and acceptance evidence](#10-testing-quality-data-and-acceptance-evidence)

---

## 0. How to read this document

This is a long reference, on purpose. You almost never need all of it; pick the section that matches what you're doing.

- **First time here?** Read [§1](#1-owl-and-sparql-in-5-minutes--the-primer-you-actually-need) (OWL primer) and [§3](#3-a-real-walkthrough--load-an-ontology-ask-questions-verify-a-claim) (a real walkthrough) end to end. After that you can jump around.
- **Driving owl4agents from the CLI?** Jump to [§4](#4-cli-reference--every-command-with-real-input-and-real-output). Every command has a "Run it" code block you can copy, and a "What you get" block showing the real output we got from a v0.8 server running on this repo.
- **Driving owl4agents from an MCP client (Claude / Cursor / Trae / your own agent)?** Jump to [§5](#5-mcp-tool-reference--every-tool-with-real-json-rpc-request-and-response). Every tool has a sample JSON-RPC request and the matching response.
- **Building an answer-verification pipeline?** §3 then [§6](#6-claim-verification-and-evidence-grounding--wiring-owl4agents-into-an-llm-answer-pipeline).
- **Debugging an error?** [§9](#9-error-codes-troubleshooting-and-limits).

**The single ontology we use in every example** is `v0.3-claim-verification.owl` from `test/corpus/golden/`. It is 55 lines, contains 8 classes, 4 individuals, 1 object property, 2 data properties, and is small enough to fit on one screen. We re-print it in [§1.2](#12-the-running-example-a-real-owl-file).

**Conventions in this document**:

- "We ran" or "Real output" means we executed the command on the actual owl4agents v0.8 jar against the actual fixture and pasted the output here (with one or two cosmetic line wraps).
- Code blocks in `json` are real request / response bodies. Code blocks in `powershell` or `bash` are real commands you can run.
- `<like-this>` is a placeholder you should replace.

---

## 1. OWL and SPARQL in 5 minutes — the primer you actually need

This is a deliberately short primer. If you've worked with RDF or a description logic before, skim it; if you haven't, the rest of this doc will be much easier after this section.

### 1.1 The 30-second version

An **OWL ontology** is a set of named things (classes, properties, individuals) and the relationships between them. It looks like RDF triples: `subject predicate object`. The interesting predicates come from three vocabularies:

- `rdf:type` — "this individual is an instance of this class" (e.g. `Fido rdf:type Dog`).
- `rdfs:subClassOf` — "this class is more specific than that class" (e.g. `Dog rdfs:subClassOf Animal`).
- `rdfs:domain` / `rdfs:range` — "this property applies to instances of X and produces values of Y".

An **OWL reasoner** takes the explicit facts and computes what else *must* be true. Given `Dog subClassOf Mammal` and `Mammal subClassOf Animal`, the reasoner infers `Dog subClassOf Animal` and writes that into the inferred graph.

**SPARQL** is the SQL of RDF. You write `SELECT ?s WHERE { ?s rdfs:subClassOf :Animal }` and you get back every class that is a subclass of `Animal`. `ASK` returns a boolean. `CONSTRUCT` returns triples. `DESCRIBE` returns "everything we know about" some resource.

That's it. The rest of OWL (equivalent classes, disjointness, restrictions, individuals, datatypes, ...) all just gives you more interesting predicates and more interesting entailments.

### 1.2 The running example: a real OWL file

We will use the following 55-line file everywhere below. The path is `test/corpus/golden/v0.3-claim-verification.owl` in the repo.

```turtle
@prefix : <http://example.org/v0.3#> .
@prefix owl: <http://www.w3.org/2002/07/owl#> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

<http://example.org/v0.3-claim-verification> a owl:Ontology ;
    rdfs:label "v0.3 Claim Verification Golden Ontology" ;
    rdfs:comment "Golden ontology for testing claim verification across supported, contradicted, unknown, and out_of_scope verdicts." .

# --- Class hierarchy (supported: Dog subClassOf Animal) ---
:Animal a owl:Class .
:Mammal a owl:Class ; rdfs:subClassOf :Animal .
:Dog a owl:Class ; rdfs:subClassOf :Mammal .
:Cat a owl:Class ; rdfs:subClassOf :Mammal .

# --- Equivalent classes (supported: Canine = Dog) ---
:Canine a owl:Class ; owl:equivalentClass :Dog .

# --- Disjoint classes (contradicted: Dog disjointWith Cat — claiming Dog subClassOf Cat contradicts) ---
:Dog owl:disjointWith :Cat .

# --- Object property with domain/range ---
:hasOwner a owl:ObjectProperty ;
    rdfs:domain :Animal ;
    rdfs:range :Person .

:Person a owl:Class .

# --- Data property with domain/range and datatype constraints ---
:hasAge a owl:DatatypeProperty ;
    rdfs:domain :Animal ;
    rdfs:range xsd:nonNegativeInteger .

:hasName a owl:DatatypeProperty ;
    rdfs:domain :Animal ;
    rdfs:range xsd:string .

# --- Individual assertions (supported: Fido is a Dog) ---
:Fido a :Dog .
:Rex a :Dog .
:Whiskers a :Cat .

:Fido :hasOwner :PersonJohn .
:Fido :hasAge 5 .
:Fido :hasName "Fido" .

:PersonJohn a :Person .

# --- Sparse unknown: Fish/Goldfish have no axioms connecting them ---
:Fish a owl:Class .
:Goldfish a owl:Class .

# --- Out-of-scope: UnconnectedThing has no relation to Animal ---
:UnconnectedThing a owl:Class .
```

What you should take away from this file:

- **8 named classes**: `Animal`, `Mammal`, `Dog`, `Cat`, `Canine`, `Person`, `Fish`, `Goldfish`, `UnconnectedThing`.
- **Class hierarchy**: `Dog`, `Cat` are `Mammal`s; `Mammal` is an `Animal`. By transitivity, `Dog` and `Cat` are also `Animal`s, but this is only true **after reasoning** — the file itself doesn't state `Dog subClassOf Animal` directly.
- **One equivalence**: `Canine` ≡ `Dog`. Reasoners will collapse these.
- **One disjointness**: `Dog` ⊥ `Cat`. An individual cannot be both. A claim that `Dog subClassOf Cat` will be **contradicted** by the reasoner.
- **3 named individuals**: `Fido` (a `Dog` with an owner, an age, a name), `Rex` (a `Dog`), `Whiskers` (a `Cat`), `PersonJohn` (a `Person`).
- **2 object/data property declarations**: `hasOwner` (Object, domain=Animal, range=Person), `hasAge`/`hasName` (Data, domain=Animal, range=xsd:nonNegativeInteger / xsd:string).
- **Two axioms that look like claim-verification test data**:
  - `Fish` and `Goldfish` exist but have no axioms connecting them. A claim that `Goldfish subClassOf Fish` is **unknown** — not contradicted, not supported, just no information.
  - `UnconnectedThing` has no relation to the rest of the ontology. A claim that some class is "in the scope of `Animal`" involving `UnconnectedThing` is **out of scope**.

### 1.3 Three graphs you will see in responses

owl4agents reasons about three views of the same ontology:

- **explicit** — the axioms you wrote in the file.
- **inferred** — the axioms the reasoner derived. `Dog subClassOf Animal` lives here.
- **union** — explicit ⊎ inferred. (Some tools default to this.)

Most tools let you pick the graph scope with a `graphScope` / `graph_scope` parameter.

### 1.4 Profiles

OWL 2 has four tractable profiles: **DL** (description logic, full expressivity, slower reasoners), **EL** (existential, very fast, lots of biomedical ontologies are EL), **QL** (query-friendly, good for large ABoxes), **RL** (rule-based, scalable). owl4agents detects the profile automatically and lets you ask for it via `ontology_get_profile`. Different reasoners support different profiles — see [§8](#8-reasoner-integration--which-reasoner-to-pick-and-when).

### 1.5 Claim shape: what does a "structured claim" look like?

Throughout this doc you will see JSON like this for "the claim that `Dog` is a subclass of `Mammal`":

```json
{
  "claimId": "my-claim-001",
  "type": "subclass",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
  "predicate": "subClassOf",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Mammal" }
}
```

The fields are:

- `claimId` — your own identifier. Echoed back in responses.
- `type` — one of `subclass`, `equivalent_classes`, `disjoint_classes`, `individual_membership`, `class_compatibility`, `relation_assertion` (split into `object_property_assertion` / `data_property_assertion` in v0.8.1), `ontology_scope`, `ontology_consistency`, `literal_validity`, `object_property_domain`, `object_property_range`, `data_property_domain`, `data_property_range`, `different_individuals` (v0.8.1), `object_property_subproperty` (v0.8.1).
- `subject` / `object` — `{ "kind": "class" | "individual" | "object_property" | "data_property", "iri": "..." }`. v0.8.1 adds an optional `expression` field on either side for complex class expressions (e.g. `Pizza ⊓ ∃hasTopping.CheeseTopping`); see §6.7 below.
- `predicate` — the relationship being asserted.
- `reasoner` — `"auto" | "hermit" | "elk" | "openllet"`, default `"auto"`.
- `graphScope` — `"explicit" | "inferred" | "union"`, default `"explicit"`.
- `options.includeEvidence` — when `true`, the response includes an `evidence` array.

The full JSON schema is enforced; malformed claims return `INVALID_CLAIM_SCHEMA`.

---

## 2. Architecture and module breakdown

owl4agents is built as 11 Gradle modules. The first six implement the core domain (storage, OWL loading, query, reasoning, retrieval, validation), the next three are the public surface (CLI, MCP, benchmark), and the last two are packaging / acceptance.

```
+---------------------------------------------------------------------+
|                          npm launcher (Node 18+)                    |
|                  tools/npm/bin/owl4agents.js                        |
+-----------------------------+---------------------------------------+
                              |  fork+exec  (or PassThru on Win)
                              v
+---------------------------------------------------------------------+
|                       Java 22  owl4agents.jar                       |
|                                                                     |
|  +-----------------------------+   +-----------------------------+  |
|  | CLI layer (Picocli)         |   | MCP server (JSON-RPC + SSE) |  |
|  | 47 subcommands              |   | 56 readonly tools           |  |
|  +-------------+---------------+   +-------------+---------------+  |
|                |                                 |                  |
|                +-------------+-------------------+                  |
|                              v                                      |
|                +----------------------------------+                 |
|                |   OntologyService (façade)       |                 |
|                +----+-------------+---------------+                 |
|                     |             |                                 |
|  +------------------+--+   +------+-------------------------+        |
|  |  storage           |   |  owlapi                        |        |
|  |  Workspace init,   |   |  OWL API loader, profile det.  |        |
|  |  catalog, importer |   |  canonicalisation              |        |
|  +-------------------+   +--------------------------------+        |
|                                                                     |
|  +-------------------+   +-------------------+   +---------------+  |
|  | query             |   | reasoner          |   | retrieval     |  |
|  | Jena ARQ + SPARQL |   | HermiT/ELK/       |   | entity/QA     |  |
|  | safety guard      |   | Openllet adapters |   | context build |  |
|  +-------------------+   +-------------------+   +---------------+  |
|                                                                     |
|  +-------------------+   +-------------------+   +---------------+  |
|  | validation        |   | benchmark         |   | cli / mcp /   |  |
|  | claim, literal,   |   | experiment runner |   | distribution  |  |
|  | entailment, batch |   | eval, batch ctxt  |   | (entry pts)   |  |
|  +-------------------+   +-------------------+   +---------------+  |
+---------------------------------------------------------------------+
                              |
                              v
                  ~/.owl4agents/workspaces/
                  ├── catalog.json
                  ├── workspace.yaml
                  └── <workspace>/
                      └── ontologies/
                          └── <ontology-id>/
                              ├── source/        ← the file you imported
                              ├── canonical/     ← normalized copy
                              ├── inferred/      ← reasoner output (class hierarchy, types)
                              ├── metadata.json
                              └── reasoning-report.json
```

| Module | Responsibility | Key classes |
|---|---|---|
| `ontology-core` | Shared data model (`Claim`, `EntityId`, all `Result` records), `ErrorCode`, `ServiceError`, JSON helpers | `OntologyService`, `ClaimValidator`, `ErrorCode` |
| `ontology-storage` | Workspace, home path, catalog, importer | `WorkspaceInitializer`, `CatalogStore`, `HomeDirectoryResolver`, `OntologyImporter` |
| `ontology-owlapi` | OWL API loader, profile detector, normalization, semantic deepening | `OwlapiOntologyLoader`, `ProfileDetector`, `SemanticDeepeningService` |
| `ontology-query` | Apache Jena ARQ, SPARQL safety guard, entity search | `SparqlExecutionService`, `SparqlSafetyGuard`, `EntitySearchService` |
| `ontology-reasoner` | HermiT / ELK / Openllet adapters, reasoner task router, persistence | `ReasonerServiceImpl`, `ReasonerLifecycleManager`, `HermitAdapter`, `ElkAdapter`, `OpenlletAdapter` |
| `ontology-retrieval` | Entity context, graph neighborhood, QA context builder | `EntityContextService`, `GraphNeighborhoodService`, `QaContextService` |
| `ontology-validation` | Claim verification, literal validation, entailment, consistency analysis, evidence path, claim workflow, batch evidence context | `ClaimVerificationService`, `LiteralValidator`, `EntailmentChecker`, `ConsistencyAnalysisService`, `EvidenceGroundingService`, `ClaimWorkflowService`, `EvidenceContextBuilder`, `ClaimBatchValidator` |
| `ontology-benchmark` | Benchmark runner, QA evaluator, batch context, question set validator | `BenchmarkService`, `QaEvaluationService`, `ContextBatchService`, `ExperimentConfigParser`, `BenchmarkQuestionSetValidator`, `BenchmarkReportGenerator` |
| `ontology-cli` | Picocli command adapters (47 subcommands), mcp-config generator | `Owl4AgentsCli`, `McpCommand`, `ImportCommand`, `VerifyClaimCommand`, `McpConfigCommand` |
| `ontology-mcp` | MCP server (stdio / HTTP / SSE), tool registry, call logger, session manager | `HttpMcpServer`, `McpServerAdapter`, `McpToolRegistry`, `McpSessionManager`, `McpToolCallLogger` |
| `ontology-distribution` | Cross-version end-to-end acceptance (V01..V08) | `V03AcceptanceSuite`, `V04AcceptanceSuite`, ... |

The **façade** is `OntologyService` (in `ontology-core`). All entry points (CLI, MCP) call into it. Reasoner-using tool calls are routed through a single-thread executor; everything else goes through an 8-thread pool. This is what the MCP server's "saturated worker pool" error refers to — see [§9](#9-error-codes-troubleshooting-and-limits).

---

## 3. A real walkthrough — load an ontology, ask questions, verify a claim

This section runs **the same ontology** through the most important entry points, end to end, with real outputs. Everything in [§4](#4-cli-reference--every-command-with-real-input-and-real-output) and [§5](#5-mcp-tool-reference--every-tool-with-real-json-rpc-request-and-response) refers back to this section.

### 3.1 Pre-flight

```powershell
# 0. Prerequisites
java -version    # 22.x
node --version   # 18.x+

# 1. Build (Windows)
.\gradlew.bat :modules:ontology-cli:shadowJar

# 2. Set workspace
$env:OWL4AGENTS_HOME = "D:\owl4agents-workspace"   # any folder you can write to
```

### 3.2 Initialize a workspace

```powershell
node tools/npm/bin/owl4agents.js init
```

**Real output:**

```
Workspace 'default' initialized successfully.
```

This creates `<OWL4AGENTS_HOME>/workspaces/default/workspace.yaml` and a fresh `catalog.json`. Re-running is idempotent.

### 3.3 Import the demo ontology

```powershell
node tools/npm/bin/owl4agents.js import `
    test/corpus/golden/v0.3-claim-verification.owl v03_demo
```

**Real output (truncated for clarity):**

```
Importing test/corpus/golden/v0.3-claim-verification.owl as 'v03_demo'...
  Source copied: 1,807 bytes
  Parsing with OWL API...
  Profile detected: OWL 2 EL (also valid in DL/QL/RL)
  Normalizing axioms (8 classes, 4 individuals, 1 obj-prop, 2 data-props)...
  Writing canonical/ontology.owl (2,888 bytes)
  Updating catalog.json...
Imported ontology 'v03_demo' (IRI: http://example.org/v0.3-claim-verification)
```

Internally the importer:

1. Copies the file to `ontologies/v03_demo/source/`.
2. Loads it with the OWL API, computes its profile, normalises the axioms, and writes `canonical/ontology.owl`.
3. Appends an entry to `catalog.json` mapping `v03_demo → source path, canonical path, import timestamp, metadata path`.

### 3.4 Look around (no reasoner needed)

```powershell
node tools/npm/bin/owl4agents.js list
```

```
Ontologies in workspace 'default':
  v03_demo - v0.3-claim-verification (imported: 2026-07-06T...)
```

```powershell
node tools/npm/bin/owl4agents.js summary v03_demo
```

```
Ontology: v03_demo
IRI: http://example.org/v0.3-claim-verification
Version IRI: (none)
Imports: []
Profile: [OWL 2 DL, OWL 2 EL, OWL 2 QL, OWL 2 RL, OWL 2 Full]
Entity counts:
  Classes: 8
  Object properties: 1
  Data properties: 2
  Annotation properties: 0
  Individuals: 4
  Datatypes: 1
```

```powershell
node tools/npm/bin/owl4agents.js search v03_demo Dog
```

```
Search results for 'Dog' in ontology 'v03_demo':
Found 1 results

  Dog
    IRI: http://example.org/v0.3#Dog
    Type: class
    Score: 0.85
    Match: alias
```

```powershell
node tools/npm/bin/owl4agents.js entity v03_demo "http://example.org/v0.3#Dog"
```

```
Entity: http://example.org/v0.3#Dog
IRI: http://example.org/v0.3#Dog
Type: class

Superclasses: [http://example.org/v0.3#Mammal]
Equivalent: [http://example.org/v0.3#Canine]
Disjoint: [http://example.org/v0.3#Cat, http://example.org/v0.3#Cat]
```

(Note: `Disjoint` lists `Cat` twice. That's a known duplicate-disjoint bug from the importer merging the symmetric axiom; see [§9](#9-error-codes-troubleshooting-and-limits). Not a correctness issue — the disjointness is real — but the IRI appears twice.)

### 3.5 Run the reasoner

```powershell
node tools/npm/bin/owl4agents.js reason v03_demo --reasoner elk
```

**Real output:**

```
Reasoning report for ontology 'v03_demo':
  Reasoner: ELK
  OWL profile: OWL 2 EL
  Classification: true
  Realization: true
  Consistency: true
  Timing (ms):
    Initialization: 264
    Classification: 178
    Realization: 10
    Total: 1493
  Inferred axiom counts:
    SubClassOf: 4
    InferredIndividualType: 8
```

This persists a `reasoning-report.json` and writes `inferred-class-hierarchy.jsonl` and `inferred-types.jsonl` to the workspace. Subsequent tools that ask for `graphScope: inferred` or `union` read these files.

```powershell
node tools/npm/bin/owl4agents.js consistency v03_demo
```

```
Consistency check for ontology 'v03_demo':
  Reasoner: ELK
  Consistent: true
```

```powershell
node tools/npm/bin/owl4agents.js classify v03_demo
```

```
Classification result for ontology 'v03_demo':
  Reasoner: ELK
  Complete hierarchy entries: 10
  Delta (new inferred) entries: 4

Inferred SubClassOf relationships (delta):
  http://example.org/v0.3#Dog -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  http://example.org/v0.3#Cat -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  http://example.org/v0.3#Canine -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  http://example.org/v0.3#Canine -> http://example.org/v0.3#Mammal (source: inferred, reasoner: ELK)
```

The first three are inferred because of the explicit `Mammal subClassOf Animal` plus `Dog`/`Cat`/`Canine` being subclasses of `Mammal`. The fourth is from the equivalence `Canine ≡ Dog` plus `Dog subClassOf Mammal`.

### 3.6 Run a SPARQL query

```powershell
node tools/npm/bin/owl4agents.js query v03_demo `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }"
```

**Real output (default `graphScope: explicit`):**

```
Variables: [s]
Results: 1
  {s=BindingValue[value=http://example.org/v0.3#Mammal, datatype=null, type=uri]}
```

Only `Mammal` shows up because the explicit graph only has `Mammal subClassOf Animal` literally. The transitive subclasses (Dog, Cat, Canine) live in the inferred graph:

```powershell
node tools/npm/bin/owl4agents.js query v03_demo --graph-scope union `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }"
```

```
Variables: [s]
Results: 4
  {s=BindingValue[value=http://example.org/v0.3#Mammal, datatype=null, type=uri]}
  {s=BindingValue[value=http://example.org/v0.3#Dog, datatype=null, type=uri]}
  {s=BindingValue[value=http://example.org/v0.3#Cat, datatype=null, type=uri]}
  {s=BindingValue[value=http://example.org/v0.3#Canine, datatype=null, type=uri]}
```

```powershell
node tools/npm/bin/owl4agents.js query v03_demo `
    --ask "ASK { <http://example.org/v0.3#Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Mammal> }"
```

```
Result: true
```

> **Why the full IRIs?** Because the running ontology doesn't declare a default prefix mapping in a way Jena understands. You can either use full IRIs, or wrap the query in a SPARQL prologue (`PREFIX rdfs: <...>`) when using the `MCP ontology_sparql_select` tool, which has a known safety guard against bare `PREFIX` (use a string variable in your code).

### 3.7 Verify a structured claim

The most interesting tool. The "claim" is a small JSON object saying "is `Dog` compatible with `Cat`?". The answer comes back with a verdict, evidence, and a reason.

First, write a claim file:

```powershell
# Save as test/fixtures/v0.3/claim-contradicted.json
@"
{
  "claimId": "doc-contradicted",
  "type": "class_compatibility",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
  "predicate": "compatibleWith",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Cat" },
  "reasoner": "auto",
  "graphScope": "explicit",
  "options": { "includeEvidence": true }
}
"@ | Out-File claim-contradicted.json -Encoding ascii
```

Then verify:

```powershell
node tools/npm/bin/owl4agents.js verify-claim v03_demo `
    --claim claim-contradicted.json --json
```

**Real output:**

```json
{
  "claimId": "doc-contradicted",
  "ontologyId": "v03_demo",
  "claimType": "class_compatibility",
  "verdict": "contradicted",
  "evidence": [
    {
      "evidenceId": "compatibility-doc-contradicted",
      "role": "counter",
      "kind": "explicit_axiom",
      "value": "http://example.org/v0.3#Dog and http://example.org/v0.3#Cat → disjoint",
      "source": "class-compatibility-check",
      "reasoner": "default",
      "graphScope": "UNION",
      "entities": ["http://example.org/v0.3#Dog", "http://example.org/v0.3#Cat"],
      "confidence": "inferred"
    }
  ],
  "unknownReason": null,
  "unknownExplanation": null,
  "reasonerName": "auto",
  "graphScope": "explicit",
  "truncated": false,
  "totalEvidenceAvailable": 1
}
```

Read it as: **"Your claim (that Dog is compatible with Cat) is contradicted by an explicit disjointness axiom in the ontology, and here is the evidence."**

For a positive example, claim that `Animal` is "in scope of" itself:

```json
{
  "claimId": "doc-scope-supported",
  "type": "ontology_scope",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Animal" },
  "predicate": "inScopeOf",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" }
}
```

```powershell
node tools/npm/bin/owl4agents.js verify-claim v03_demo `
    --claim claim-scope.json --json
```

**Real output (excerpt):**

```json
{
  "claimId": "doc-scope-supported",
  "ontologyId": "v03_demo",
  "claimType": "ontology_scope",
  "verdict": "supported",
  "evidence": [
    {
      "evidenceId": "scope-doc-scope-supported",
      "role": "supporting",
      "kind": "scope_statement",
      "value": "Domains: [Animal, Canine, Fish, Goldfish, Person, UnconnectedThing], gaps: [], limitations: [No disjointness axioms support, No union of class expressions, No cardinality restrictions (except max 1)]",
      "source": "scope-description",
      "graphScope": "EXPLICIT",
      "confidence": "explicit"
    }
  ],
  "totalEvidenceAvailable": 1
}
```

The four verdicts you can get back, in increasing order of "we don't know":

| Verdict | Meaning | Example |
|---|---|---|
| `supported` | There is an axiom (explicit or inferred) that confirms the claim. | "Animal is in scope of Animal" — the ontology's declared domains include Animal. |
| `contradicted` | An axiom (explicit or inferred) directly contradicts the claim. | "Dog is compatible with Cat" — but they are `disjointWith`. |
| `unknown` | Neither supported nor contradicted; we just don't have enough info. `unknownReason` will be set (e.g. `insufficient_axioms`, `sparse_ontology`). | "Goldfish subClassOf Fish" — both classes exist, but the ontology has no axiom linking them. |
| `out_of_scope` | The claim references entities / relations that the ontology doesn't even mention. | "DeliveryPrice inScopeOf Animal" — `DeliveryPrice` doesn't exist in this ontology. |

### 3.8 Run the same flow over MCP

Same ontology, same answer — but driven through the JSON-RPC server. This is the exact wire format an MCP client uses.

```powershell
# Start the server in another shell.
$env:OWL4AGENTS_HOME = "D:\owl4agents-workspace"
node tools/npm/bin/owl4agents.js mcp --readonly --transport http --port 8091

# In this shell, send a request.
curl.exe -sS -X POST http://127.0.0.1:8091/mcp `
    -H "Content-Type: application/json" -H "Accept: application/json" `
    --data-binary '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"owl4agents","version":"0.8.4"}}}
```

The `serverInfo.version` should be `"0.8.4"`. The session is anonymous (no `Mcp-Session-Id` returned for `initialize`); subsequent calls don't need a session id on the plain HTTP transport.

```powershell
curl.exe -sS -X POST http://127.0.0.1:8091/mcp `
    -H "Content-Type: application/json" -H "Accept: application/json" `
    --data-binary '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ontology_list_reasoners","arguments":{}}}'
```

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "text": "{\"reasoners\":[{\"name\":\"HermiT\",\"supportedProfiles\":[\"OWL 2 DL\",\"OWL 2 Full\"],\"supportedOperations\":[\"classify\",\"realize\",\"checkConsistency\"],\"explanationSupported\":false},{\"name\":\"ELK\",\"supportedProfiles\":[\"OWL 2 EL\"],\"supportedOperations\":[\"classify\",\"checkConsistency\"],\"explanationSupported\":false},{\"name\":\"Openllet\",\"supportedProfiles\":[\"OWL 2 DL\"],\"supportedOperations\":[\"classify\",\"realize\",\"checkConsistency\",\"explain\"],\"explanationSupported\":true}]}"
      }
    ]
  }
}
```

For a tool with arguments, the JSON is the inner-`arguments` object:

```powershell
# Saved as D:\req-class.json
@'
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ontology_get_class_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Dog"}}}
'@ | Out-File D:\req-class.json -Encoding ascii -NoNewline

curl.exe -sS -X POST http://127.0.0.1:8091/mcp `
    -H "Content-Type: application/json" -H "Accept: application/json" `
    --data-binary "@D:\req-class.json"
```

```json
{
  "jsonrpc":"2.0","id":3,
  "result":{"content":[
    {"text":"{\"superclasses\":[\"http://example.org/v0.3#Mammal\"],\"disjointClasses\":[\"http://example.org/v0.3#Cat\",\"http://example.org/v0.3#Cat\"],\"iri\":\"http://example.org/v0.3#Dog\",\"equivalentClasses\":[\"http://example.org/v0.3#Canine\"],\"label\":\"\",\"type\":\"class\",\"subclasses\":[]}","type":"text"}
  ]}
}
```

Two structural points worth knowing:

1. **The tool's actual JSON lives in `result.content[0].text` as a string.** Most MCP clients parse that string and hand you a JSON object. The shape inside is the same as the CLI's `--json` output.
2. **Errors are `isError: true`** on the `result` and the same `code` / `message` / `details` triple inside the `text` payload (see [§9](#9-error-codes-troubleshooting-and-limits)).

### 3.9 What you've now seen

| Action | CLI | MCP tool |
|---|---|---|
| Initialize workspace | `init` | (not exposed — CLI only) |
| Import an OWL file | `import` | (not exposed — CLI only) |
| List ontologies | `list` | `ontology_list` |
| Get summary / profile / metadata | `summary` | `ontology_summary`, `ontology_get_metadata`, `ontology_get_profile` |
| Search by name | `search` | `ontology_search_entities` |
| Get one entity | `entity` | `ontology_get_entity_context`, `ontology_get_class_context`, ... |
| Run a reasoner | `reason` / `classify` / `realize` / `consistency` | `ontology_run_reasoner`, `ontology_classify`, `ontology_realize_instances`, `ontology_check_consistency` |
| Run a SPARQL query | `query` | `ontology_sparql_select` / `_ask` / `_construct` / `_describe`, plus `ontology_validate_sparql` |
| Verify a claim | `verify-claim` | `ontology_verify_claim` |
| Build an evidence context | `evidence`, `evidence-context`, `review-answer` | `ontology_get_evidence_path`, `ontology_build_evidence_context`, `ontology_review_answer_claims` |

Every cell above is documented in detail below.

---

## 4. CLI reference — every command, with real input and real output

The CLI is a Picocli sub-command tree. The top-level command is the launcher `node tools/npm/bin/owl4agents.js <subcommand> [...]`; under it sit 47 subcommands. They group into 10 areas:

1. **Workspace & import** (1.1): `init`, `import`, `imports`, `list`, `summary`
2. **Browse & search** (1.2): `search`, `entity`, `scope`
3. **SPARQL & query** (1.3): `query`
4. **QA context** (1.4): `context`, `context-batch`
5. **Reasoner & reasoning report** (1.5): `list-reasoners`, `reason`, `classify`, `realize`, `consistency`, `explain`, `unsat`, `report`
6. **Entity-level entailment & relations** (1.6): `entailment`, `compatibility`, `membership`, `relation-check`, `relations`, `assertions`, `same-individuals`, `different-individuals`, `restrictions`, `properties`, `equivalent`, `disjoint`, `datatype-constraints`, `validate-literal`
7. **Claim verification & evidence** (1.7): `verify-claim`, `evidence`, `counterexamples`, `explain-unknown`, `missing-entities`, `verify-answer`, `evidence-context`, `review-answer`
8. **Benchmark & QA evaluation** (1.8): `benchmark-run`, `eval-qa`
9. **MCP server & config** (1.9): `mcp`, `mcp-config`
10. **Setup & smoke** (1.10): `setup`, `smoke`, `--version`, `--help`

The default workspace is `default`. Most commands accept `--workspace <name>` to target a different one, and `--home <path>` (or `OWL4AGENTS_HOME` env var) to relocate the workspace root.

### 4.1 `init`

**Purpose**: create a fresh `~/.owl4agents/workspaces/<name>/` directory with `workspace.yaml` and an empty `catalog.json`. Idempotent.

```powershell
node tools/npm/bin/owl4agents.js init
node tools/npm/bin/owl4agents.js init --workspace staging
```

### 4.2 `import`

**Purpose**: load a local OWL/RDF file into the workspace catalog. Copies the file, parses, normalises, registers.

```powershell
node tools/npm/bin/owl4agents.js import `
    test/corpus/golden/v0.3-claim-verification.owl v03_demo
# Optional flags:
#   --force   re-import even if the id is already in catalog.json
```

A successful import prints the source path, profile, entity counts, and the catalog entry. Failure modes:

- **File not found** — `Error: INPUT_NOT_FOUND - Cannot read ontology file: ...`
- **Parse error** — `Error: ONTOLOGY_PARSE_FAILED - ...` (e.g. malformed Turtle).
- **Invalid import** — `Error: ONTOLOGY_IMPORT_FAILED - The ontology imports ... which is not on the import path` (the importer refuses to pull in remote imports — supply them locally).

### 4.3 `list`

**Purpose**: list all ontologies registered in the current workspace.

```powershell
node tools/npm/bin/owl4agents.js list --workspace default
```

**Real output:**

```
Ontologies in workspace 'default':
  v03_demo - v0.3-claim-verification (imported: 2026-07-06T...)
  pizza - pizza (imported: 2026-07-03T...)
  ...
```

JSON variant: `--json` prints `[{"ontologyId":"v03_demo","displayName":"...","importTimestamp":"..."}]`.

### 4.4 `summary`

**Purpose**: dump IRI, version IRI, imports closure, profile, and entity counts.

```powershell
node tools/npm/bin/owl4agents.js summary v03_demo
```

**Real output:**

```
Ontology: v03_demo
IRI: http://example.org/v0.3-claim-verification
Version IRI: (none)
Imports: []
Profile: [OWL 2 DL, OWL 2 EL, OWL 2 QL, OWL 2 RL, OWL 2 Full]
Entity counts:
  Classes: 8
  Object properties: 1
  Data properties: 2
  Annotation properties: 0
  Individuals: 4
  Datatypes: 1
```

JSON shape: `{"ontologyId","iri","versionIri","profile","imports":[...],"entityCounts":{...}}`.

### 4.5 `imports`

**Purpose**: the transitive import closure of an ontology, with `direct` / `indirect` flags and whether each loaded successfully.

```powershell
node tools/npm/bin/owl4agents.js imports v03_demo
```

For a self-contained ontology (like `v03_demo`):

```
Imports for ontology 'v03_demo':
  (no imports)
```

For something like the BFO upper ontology (which imports RO, OBI, ...):

```
Imports for ontology 'bfo':
  http://purl.obolibrary.org/obo/ro.owl (direct, loaded)
  http://purl.obolibrary.org/obo/BFO_0000050 ... (indirect, loaded)
  ...
```

### 4.6 `search`

**Purpose**: full-text search across class labels, IRIs, and aliases. Match score is a float in [0, 1].

```powershell
node tools/npm/bin/owl4agents.js search v03_demo Dog
```

**Real output:**

```
Search results for 'Dog' in ontology 'v03_demo':
Found 1 results

  Dog
    IRI: http://example.org/v0.3#Dog
    Type: class
    Score: 0.85
    Match: alias
```

**Flags:** `--limit <n>` (default 20), `--type-filter <class,object_property,data_property,individual>`.

### 4.7 `entity`

**Purpose**: dump everything we know about a single entity (class / property / individual). The kind is auto-detected from the IRI; for a class, you get the class context; for an individual, the individual context; for a property, the property context.

```powershell
node tools/npm/bin/owl4agents.js entity v03_demo "http://example.org/v0.3#Dog"
```

**Real output (class):**

```
Entity: http://example.org/v0.3#Dog
IRI: http://example.org/v0.3#Dog
Type: class

Superclasses: [http://example.org/v0.3#Mammal]
Equivalent: [http://example.org/v0.3#Canine]
Disjoint: [http://example.org/v0.3#Cat, http://example.org/v0.3#Cat]
```

For an individual:

```
Entity: http://example.org/v0.3#Fido
IRI: http://example.org/v0.3#Fido
Type: individual
  Types: [http://example.org/v0.3#Dog]
  Object property assertions:
    hasOwner -> http://example.org/v0.3#PersonJohn
  Data property assertions:
    hasAge = 5 (xsd:nonNegativeInteger)
    hasName = "Fido" (xsd:string)
```

### 4.8 `scope`

**Purpose**: what the ontology claims to be about, what it leaves out, profile limitations, and what feature types it does not support.

```powershell
node tools/npm/bin/owl4agents.js scope v03_demo
```

**Real output (excerpt):**

```
Ontology 'v03_demo' scope:
  Covered domains: [Animal, Canine, Fish, Goldfish, Person, UnconnectedThing]
  Known gaps: []
  Profile limitations: [No disjointness axioms support, No union of class expressions, No cardinality restrictions (except max 1)]
  Unsupported feature types: []
```

The "profile limitations" line is the bit your agent should read to know what kind of claim the ontology *cannot* answer.

### 4.9 `query`

**Purpose**: validate, parse, or execute a SPARQL query against the ontology. Sub-modes are selected by the first flag.

```powershell
# Validate only.
node tools/npm/bin/owl4agents.js query v03_demo `
    --validate "SELECT ?s WHERE { ?s ?p ?o }"

# Execute SELECT.
node tools/npm/bin/owl4agents.js query v03_demo `
    --select "SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }" `
    --graph-scope union

# Execute ASK.
node tools/npm/bin/owl4agents.js query v03_demo `
    --ask "ASK { <http://example.org/v0.3#Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Mammal> }"

# Execute CONSTRUCT.
node tools/npm/bin/owl4agents.js query v03_demo `
    --construct "CONSTRUCT { ?s a ?o } WHERE { ?s rdfs:subClassOf ?o }"

# Execute DESCRIBE.
node tools/npm/bin/owl4agents.js query v03_demo `
    --describe "DESCRIBE <http://example.org/v0.3#Dog>"
```

**Real output (ASK):**

```
Result: true
```

**Safety guard:** the four execution flags (`--select`, `--ask`, `--construct`, `--describe`) go through `SparqlSafetyGuard`. The keywords `INSERT DATA`, `DELETE DATA`, `DELETE WHERE`, `LOAD`, `CLEAR`, `DROP`, `COPY`, `MOVE`, `ADD`, `CREATE` are rejected before parsing. **This is the only thing standing between a malicious LLM and your workspace — the readonly server does not implement any other isolation.** A `SPARQL_SAFETY_VIOLATION` error is always a sign someone tried to use the read endpoint to write.

**Graph scope:** `--graph-scope explicit|inferred|union` (default `explicit`). Use `union` for the most complete answer; use `explicit` if you want only what the file says.

### 4.10 `context`

**Purpose**: take a free-text question, find entities in the ontology, and assemble a prompt-sized natural-language context that an LLM can use. Essentially "what would I put in the LLM's system message if I were grounding it in this ontology?".

```powershell
node tools/npm/bin/owl4agents.js context v03_demo "Which animals are mammals?" `
    --max-entities 5 --max-depth 3
```

**Real output (excerpt):**

```
Question: Which animals are mammals?
Matched entities: 2
  - http://example.org/v0.3#Mammal (class)
  - http://example.org/v0.3#Animal (class)

Generated context:
Question: Which animals are mammals?

Matched entities:
- Mammal (class): http://example.org/v0.3#Mammal
  Superclasses: [http://example.org/v0.3#Animal]
  Subclasses: [http://example.org/v0.3#Cat, http://example.org/v0.3#Dog]
- Animal (class): http://example.org/v0.3#Animal
  Subclasses: [http://example.org/v0.3#Mammal]
```

**Flags:** `--max-entities <n>` (default 10), `--max-depth <n>` (default 3), `--include-inferred`.

### 4.11 `context-batch`

**Purpose**: process a JSONL question set (one question per line) and emit a per-question evidence context. Used by benchmark scripts.

```powershell
node tools/npm/bin/owl4agents.js context-batch `
    test/fixtures/v0.6/question-sets/pizza-50.jsonl `
    --ontology pizza-bench --max-context-tokens 500
```

Output goes to `build/reports/.../context-batch.jsonl` with one entry per question.

### 4.12 `list-reasoners`

**Purpose**: list available reasoner adapters, their supported OWL profiles, and what operations they support.

```powershell
node tools/npm/bin/owl4agents.js list-reasoners
```

**Real output:**

```
Available reasoner adapters:
  HermiT
    Supported profiles: [OWL 2 DL, OWL 2 Full]
    Supported operations: [classify, realize, checkConsistency]
    Explanation supported: false
  ELK
    Supported profiles: [OWL 2 EL]
    Supported operations: [classify, checkConsistency]
    Explanation supported: false
  Openllet
    Supported profiles: [OWL 2 DL]
    Supported operations: [classify, realize, checkConsistency, explain]
    Explanation supported: true
```

See [§8](#8-reasoner-integration--which-reasoner-to-pick-and-when) for "which one to pick".

### 4.13 `reason`

**Purpose**: run a reasoner (init, classify, realize, check consistency) and persist the result. This is the most important command — many other tools require that `reason` has been run at least once.

```powershell
node tools/npm/bin/owl4agents.js reason v03_demo --reasoner elk
# Optional: --reasoner auto|hermit|elk|openllet (default: auto)
# Optional: --tasks classify,realize,consistency (default: all)
```

**Real output:**

```
Reasoning report for ontology 'v03_demo':
  Reasoner: ELK
  OWL profile: OWL 2 EL
  Classification: true
  Realization: true
  Consistency: true
  Timing (ms):
    Initialization: 264
    Classification: 178
    Realization: 10
    Total: 1493
  Inferred axiom counts:
    SubClassOf: 4
    InferredIndividualType: 8
```

After this command, `ontologies/v03_demo/inferred/` contains `inferred-class-hierarchy.jsonl` and `inferred-types.jsonl`, and `reasoning-report.json` is updated. The next call to `classify`, `realize`, or `consistency` re-uses this report if no source files have changed.

### 4.14 `classify`

**Purpose**: compute the inferred class hierarchy. Idempotent — re-running shows the delta from the last run.

```powershell
node tools/npm/bin/owl4agents.js classify v03_demo
```

**Real output:**

```
Classification result for ontology 'v03_demo':
  Reasoner: ELK
  Complete hierarchy entries: 10
  Delta (new inferred) entries: 4

Inferred SubClassOf relationships (delta):
  http://example.org/v0.3#Dog -> http://example.org/v0.3#Animal (source: inferred, reasoner: ELK)
  ...
```

### 4.15 `realize`

**Purpose**: compute inferred individual types.

```powershell
node tools/npm/bin/owl4agents.js realize v03_demo
```

For our demo: `:Fido a :Dog`, `:Rex a :Dog`, `:Whiskers a :Cat`, `:PersonJohn a :Person`. After realization, all four are also `:Animal` (transitively), and `:Fido`/`:Rex`/`:Whiskers` are also `:Mammal`.

```
Realization result for ontology 'v03_demo':
  Reasoner: ELK
  Complete types: 12
  Delta (new inferred) entries: 8
```

### 4.16 `consistency`

```powershell
node tools/npm/bin/owl4agents.js consistency v03_demo
```

```
Consistency check for ontology 'v03_demo':
  Reasoner: ELK
  Consistent: true
```

The `Reasoner` is whatever the previous `reason` run used. If `reason` has not been run, this command will run it first.

### 4.17 `explain`

**Purpose**: explain why an ontology is inconsistent. **Requires a reasoner that supports explanation** — currently only Openllet.

```powershell
node tools/npm/bin/owl4agents.js explain v03_demo --reasoner openllet
```

For a consistent ontology, the response is a structured `ONTOLOGY_CONSISTENT` "isError":

```json
{
  "message": "The ontology is consistent; no inconsistency explanation is needed.",
  "code": "ONTOLOGY_CONSISTENT",
  "details": {}
}
```

For an inconsistent one, you get a list of axioms involved in the contradiction.

### 4.18 `unsat`

```powershell
node tools/npm/bin/owl4agents.js unsat v03_demo
```

```
Unsatisfiable classes in ontology 'v03_demo': (none)
```

`v03_demo` has no unsatisfiable classes. A malformed ontology (e.g. `A subClassOf (not A)`) would surface here.

### 4.19 `entailment`

**Purpose**: ask "is this axiom entailed?" — slightly lower-level than `verify-claim`, useful for scripts.

```powershell
node tools/npm/bin/owl4agents.js entailment v03_demo `
    --axiom-type SubClassOf `
    --subject "http://example.org/v0.3#Dog" `
    --object "http://example.org/v0.3#Animal" `
    --graph-scope union
```

```
Entailment check:
  Axiom: SubClassOf(<http://example.org/v0.3#Dog>, <http://example.org/v0.3#Animal>)
  Source: inferred
  Result: entailed
```

### 4.20 `compatibility`

**Purpose**: are two classes compatible, disjoint, or unsatisfiable together?

```powershell
node tools/npm/bin/owl4agents.js compatibility v03_demo `
    "http://example.org/v0.3#Dog" "http://example.org/v0.3#Cat"
```

```
Compatibility between Dog and Cat:
  Disjoint: true
  Compatible: false
  Together unsatisfiable: false
```

### 4.21 `membership`

**Purpose**: is this individual a member of this class? Returns `asserted` / `entailed` / `not_entailed`.

```powershell
node tools/npm/bin/owl4agents.js membership v03_demo `
    "http://example.org/v0.3#Fido" "http://example.org/v0.3#Animal"
```

```
Membership of Fido in Animal:
  isMember: true
  membershipType: entailed
```

Because `:Fido a :Dog` and `:Dog subClassOf :Mammal subClassOf :Animal`, the inferred graph says yes.

### 4.22 `relation-check`

**Purpose**: is this object property relation asserted / entailed / not_entailed between two individuals?

```powershell
node tools/npm/bin/owl4agents.js relation-check v03_demo `
    "http://example.org/v0.3#hasOwner" `
    "http://example.org/v0.3#Fido" "http://example.org/v0.3#PersonJohn"
```

```
Relation check: Fido hasOwner PersonJohn
  assertionType: asserted
  isAsserted: true
```

### 4.23 `relations`

**Purpose**: find every object property relation between two individuals (the relation-check above is the boolean version; this is the list version).

```powershell
node tools/npm/bin/owl4agents.js relations v03_demo `
    --source "http://example.org/v0.3#Fido" `
    --target "http://example.org/v0.3#PersonJohn"
```

### 4.24 `assertions`

**Purpose**: dump all property assertions involving an individual (object or data).

```powershell
node tools/npm/bin/owl4agents.js assertions v03_demo `
    --iri "http://example.org/v0.3#Fido" --kind individual
```

### 4.25 `same-individuals` / `different-individuals`

```powershell
node tools/npm/bin/owl4agents.js same-individuals v03_demo `
    --iri "http://example.org/v0.3#Fido"
node tools/npm/bin/owl4agents.js different-individuals v03_demo `
    --iri "http://example.org/v0.3#Fido"
```

For our demo ontology, both return empty (no `owl:sameAs` / `owl:differentFrom` axioms).

### 4.26 `restrictions`

**Purpose**: list the restrictions (`someValuesFrom`, `allValuesFrom`, cardinality, `hasValue`) on a class.

```powershell
node tools/npm/bin/owl4agents.js restrictions v03_demo `
    --iri "http://example.org/v0.3#Dog"
```

For our demo, `:Dog` has no restrictions of its own, but the `Hypertension` class in the biomedical fixture has an interesting one — see [§6](#6-claim-verification-and-evidence-grounding--wiring-owl4agents-into-an-llm-answer-pipeline).

### 4.27 `properties`

**Purpose**: characteristics of an object / data property (functional, transitive, symmetric, reflexive, irreflexive, asymmetric, inverse-functional).

```powershell
node tools/npm/bin/owl4agents.js properties v03_demo `
    --iri "http://example.org/v0.3#hasOwner"
```

### 4.28 `equivalent` / `disjoint`

**Purpose**: list the equivalent (or disjoint) properties for a given property.

```powershell
node tools/npm/bin/owl4agents.js equivalent v03_demo --iri "http://example.org/v0.3#hasOwner"
node tools/npm/bin/owl4agents.js disjoint v03_demo --iri "http://example.org/v0.3#hasOwner"
```

### 4.29 `datatype-constraints`

```powershell
node tools/npm/bin/owl4agents.js datatype-constraints v03_demo `
    --datatype "http://www.w3.org/2001/XMLSchema#nonNegativeInteger"
```

### 4.30 `validate-literal`

```powershell
node tools/npm/bin/owl4agents.js validate-literal v03_demo `
    --datatype "http://www.w3.org/2001/XMLSchema#nonNegativeInteger" `
    --value "5"
# → { "valid": true, ... }

node tools/npm/bin/owl4agents.js validate-literal v03_demo `
    --datatype "http://www.w3.org/2001/XMLSchema#nonNegativeInteger" `
    --value "-1"
# → { "valid": false, "violations": ["value below minInclusive 0"] }
```

### 4.31 `verify-claim`

**Purpose**: the most important command. Verifies a structured claim against an ontology and returns a verdict, evidence, and metadata.

The claim lives in a JSON file (or you can pipe it through stdin with `--claim -`):

```json
{
  "claimId": "doc-supported",
  "type": "subclass",
  "ontologyId": "v03_demo",
  "subject": { "kind": "class", "iri": "http://example.org/v0.3#Mammal" },
  "predicate": "subClassOf",
  "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" },
  "graphScope": "explicit"
}
```

```powershell
node tools/npm/bin/owl4agents.js verify-claim v03_demo `
    --claim claim.json --json
```

We already saw this command's output in [§3.7](#37-verify-a-structured-claim) for the contradicted and supported cases. The four verdicts (`supported`, `contradicted`, `unknown`, `out_of_scope`) are explained in that section.

**Useful flags:**
- `--claim <path>` — path to a JSON file, or `-` to read from stdin.
- `--reasoner <auto|hermit|elk|openllet>` — default `auto`.
- `--graph-scope <explicit|inferred|union>` — default `explicit`.
- `--json` — print the structured response as JSON instead of pretty text.

**Errors you can see:**

| Code | When |
|---|---|
| `INVALID_CLAIM_SCHEMA` | Missing `claimId` or `type`, or `type` is not in the enum, or `subject`/`object` malformed. |
| `ONTOLOGY_NOT_READY` | The ontology hasn't been imported, or `reason` hasn't been run for the required graph scope. |
| `REASONER_NOT_FOUND` | `--reasoner openllet` but the Openllet adapter isn't on the classpath (it ships in the shadowJar but some IDE setups may differ). |
| `REASONER_INFEASIBLE` | Reasoner ran out of memory or timed out. |
| `EVIDENCE_NOT_AVAILABLE` | You asked for an evidence sub-feature (`evidence`, `counterexamples`, `explain-unknown`) but the verdict doesn't apply. |

### 4.32 `evidence`

**Purpose**: dump every piece of evidence (inferred facts, scope statements, reasoning report) that supports or contradicts a claim. Larger than `verify-claim`'s evidence array; this is the "show me everything" view.

```powershell
node tools/npm/bin/owl4agents.js evidence v03_demo `
    --claim test/fixtures/v0.3/claim-smoke-supported.json
```

**Real output (truncated):**

```
Evidence path for claim 'claim-smoke-supported-001':
  Verdict: supported
  Items: 8 of 8 available
  Truncated: false
    - scope-claim-smoke-supported-001: scope_statement [supporting] Domains: [...]
    - inferred-fact-http://example.org/v0.3#Dog-rdfs:subClassOf: inferred_triple [supporting] http://example.org/v0.3#Dog rdfs:subClassOf http://example.org/v0.3#Animal
    - inferred-fact-...: inferred_triple [supporting] http://example.org/v0.3#Cat rdfs:subClassOf http://example.org/v0.3#Animal
    - inferred-fact-...: inferred_triple [supporting] http://example.org/v0.3#Canine rdfs:subClassOf http://example.org/v0.3#Animal
    - inferred-fact-...-Fido-rdf:type: inferred_triple [supporting] http://example.org/v0.3#Fido rdf:type http://example.org/v0.3#Animal
    - inferred-fact-...-Rex-rdf:type: inferred_triple [supporting] http://example.org/v0.3#Rex rdf:type http://example.org/v0.3#Animal
    - inferred-fact-...-Whiskers-rdf:type: inferred_triple [supporting] http://example.org/v0.3#Whiskers rdf:type http://example.org/v0.3#Animal
    - reasoning-report-claim-smoke-supported-001: reasoning_report [supporting] Reasoner: ELK, profile: OWL 2 EL, consistent: true, ...
```

### 4.33 `counterexamples`

**Purpose**: find individuals that would be counterexamples to a contradicted claim.

```powershell
node tools/npm/bin/owl4agents.js counterexamples v03_demo `
    --claim test/fixtures/v0.3/claim-contradicted.json
```

For our `Dog compatibleWith Cat` claim, no individuals are counterexamples (because no individual is asserted to be both — and an individual being both is impossible). If the contradiction were "every Dog is also a Cat", this command would list the dogs that aren't cats.

### 4.34 `explain-unknown`

**Purpose**: when `verify-claim` returns `verdict: unknown`, this command explains *why* — e.g. `insufficient_axioms`, `sparse_ontology`, `unrelated_entities`, `unknown_predicate`.

```powershell
node tools/npm/bin/owl4agents.js explain-unknown v03_demo `
    --claim test/fixtures/v0.3/claim-unknown.json
```

Returns a `reasonCategory` and a `suggestedAction` ("add axioms linking the entities", "the predicate is not modelled in this ontology", ...).

### 4.35 `missing-entities`

**Purpose**: when a claim references an entity by IRI, this command says whether that IRI exists in the ontology, is ambiguous, is missing, or is out of scope.

```powershell
node tools/npm/bin/owl4agents.js missing-entities v03_demo `
    --claim test/fixtures/v0.3/claim-real-out-of-scope.json
```

**Real output:**

```
Missing entity detection for ontology 'v03_demo':
  Matched: 1
    - http://example.org/v0.3#Animal → http://example.org/v0.3#Animal (class)
  Ambiguous: 0
  Missing: 2
    - http://example.org/v0.3#DeliveryPrice (class)
    - inScopeOf (property)
  Out of scope: 0
```

So before running `verify-claim`, you can pre-flight the claim to see if its IRI exists.

### 4.36 `verify-answer`

**Purpose**: verify a batch of structured claims (an "answer" with several claim rows) and return an aggregate report.

Input: a JSON file with this shape:

```json
{
  "answerId": "answer-001",
  "claims": [
    {
      "id": "c1",
      "type": "subclass",
      "subject": { "kind": "class", "iri": "http://example.org/v0.3#Mammal" },
      "predicate": "subClassOf",
      "object": { "kind": "class", "iri": "http://example.org/v0.3#Animal" }
    },
    {
      "id": "c2",
      "type": "class_compatibility",
      "subject": { "kind": "class", "iri": "http://example.org/v0.3#Dog" },
      "predicate": "compatibleWith",
      "object": { "kind": "class", "iri": "http://example.org/v0.3#Cat" }
    }
  ]
}
```

```powershell
node tools/npm/bin/owl4agents.js verify-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-supported.json
```

**Response:** an `aggregateStatus` (`all_supported`, `has_contradictions`, `has_unknowns`, `has_out_of_scope`, `error`) plus a per-claim `claimResults` array mirroring `verify-claim`'s response.

### 4.37 `evidence-context`

**Purpose**: take a `verify-answer` report and produce a compact, token-budgeted text block suitable for stuffing into an LLM prompt. This is the "ground the LLM in the ontology" tool.

```powershell
node tools/npm/bin/owl4agents.js evidence-context v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --max-context-tokens 500 --format compact
```

`--format` is `compact` (default; one paragraph) or `jsonl` (one JSON object per line, with `truncated` flags).

### 4.38 `review-answer`

**Purpose**: `verify-answer` + `evidence-context`, plus a `policy` knob that adjusts how the response calls itself.

```powershell
node tools/npm/bin/owl4agents.js review-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --policy strict

node tools/npm/bin/owl4agents.js review-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --policy conservative

node tools/npm/bin/owl4agents.js review-answer v03_demo `
    --claims test/fixtures/v0.5/answer-claims-mixed.json `
    --policy report-only
```

Three policies:

| Policy | What `handlingGuidance` says |
|---|---|
| `strict` | "There is a contradiction; the LLM should NOT include this answer." |
| `conservative` | "There is an `unknown`; the LLM should rephrase the claim or omit it." |
| `report-only` | "Return the report; do not let the LLM act on it." |

### 4.39 `benchmark-run`

**Purpose**: run a benchmark experiment. The config is a small YAML file pointing at an ontology, a question set, a reasoner, and a number of repeats. Output is a JSONL file with one row per question.

```powershell
node tools/npm/bin/owl4agents.js benchmark-run `
    test/fixtures/v0.6/configs/pizza-small.yaml
```

### 4.40 `eval-qa`

**Purpose**: compute QA evaluation metrics from a benchmark result JSONL — accuracy, false-support rate, unresolved rate, coverage, 4×4 confusion matrix.

```powershell
node tools/npm/bin/owl4agents.js eval-qa `
    build/reports/benchmark/pizza-small.jsonl --json
```

### 4.41 `mcp`

**Purpose**: start the MCP server. Stdout JSON-RPC by default; use `--transport http` for HTTP.

```powershell
# Stdio (default).
node tools/npm/bin/owl4agents.js mcp --readonly

# HTTP / SSE (v0.8).
node tools/npm/bin/owl4agents.js mcp --readonly `
    --transport http --port 8080 `
    --max-sse-connections 100 `
    --session-ttl-minutes 30 `
    --sse-heartbeat-seconds 15
```

**Useful flags:** `--readonly` (the only safe mode for agents), `--workspace <name>`, `--transport stdio|http`, `--port <n>`, `--max-sse-connections <n>`, `--session-ttl-minutes <n>`, `--sse-heartbeat-seconds <n>`, `--home <path>`.

### 4.42 `mcp-config`

**Purpose**: print (or write to a file) a ready-to-paste MCP config JSON for the major MCP clients.

```powershell
node tools/npm/bin/owl4agents.js mcp-config --client claude
node tools/npm/bin/owl4agents.js mcp-config --client trae
node tools/npm/bin/owl4agents.js mcp-config --client cursor
node tools/npm/bin/owl4agents.js mcp-config --client generic
node tools/npm/bin/owl4agents.js mcp-config --client claude `
    --workspace-home D:/owl4agents-workspace `
    --out claude-mcp-config.json
```

**Supported clients:** `claude`, `cursor`, `trae`, `generic`. The generated config always points at the in-repo npm launcher and sets `OWL4AGENTS_HOME`. The Trae config uses a URL ending in `/mcp` so that Trae issues both `POST /mcp` and `GET /mcp`.

### 4.43 `setup`

**Purpose**: check the environment (Java, Gradle, source layout, workspace, npm launcher, runtime jar) without changing anything. Print a green/yellow/red checklist.

```powershell
node tools/npm/bin/owl4agents.js setup --check
```

### 4.44 `smoke`

**Purpose**: run an onboarding smoke test: import the bundled fixtures, list, summary, list reasoners, classify, and a claim verification. Useful in CI to confirm a fresh checkout works.

```powershell
node tools/npm/bin/owl4agents.js smoke
```

### 4.45 `--version` / `--help`

```powershell
node tools/npm/bin/owl4agents.js --version    # → 0.8.4
node tools/npm/bin/owl4agents.js --help       # → full command list
```

---

## 5. MCP tool reference — every tool, with real JSON-RPC request and response

The MCP server exposes 56 readonly tools. They group into 8 categories (mirroring the FEATURES.md section in v0.7):

1. **Metadata & browsing** (7): `ontology_list`, `ontology_summary`, `ontology_get_metadata`, `ontology_get_profile`, `ontology_list_graphs`, `ontology_get_imports`, `ontology_get_scope`
2. **Entity search & context** (7): `ontology_search_entities`, `ontology_get_entity_context`, `ontology_get_class_context`, `ontology_get_object_property_context`, `ontology_get_data_property_context`, `ontology_get_individual_context`, `ontology_get_graph_neighborhood`
3. **SPARQL** (5): `ontology_validate_sparql`, `ontology_sparql_select`, `ontology_sparql_ask`, `ontology_sparql_construct`, `ontology_sparql_describe`
4. **QA context** (1): `ontology_get_qa_context`
5. **Reasoner** (12): `ontology_list_reasoners`, `ontology_run_reasoner`, `ontology_classify`, `ontology_realize_instances`, `ontology_check_consistency`, `ontology_explain_inconsistency`, `ontology_explain_unsat_class`, `ontology_get_unsat_classes`, `ontology_get_reasoning_report`, `ontology_get_inferred_facts`, `ontology_check_entailment`, `ontology_check_class_compatibility`
6. **Detailed entity inspection** (13): `ontology_check_individual_membership`, `ontology_check_relation_assertion`, `ontology_get_class_restrictions`, `ontology_get_property_characteristics`, `ontology_get_equivalent_properties`, `ontology_get_disjoint_properties`, `ontology_get_datatype_constraints`, `ontology_validate_literal`, `ontology_find_relations_between_entities`, `ontology_get_object_property_assertions`, `ontology_get_data_property_assertions`, `ontology_get_same_individuals`, `ontology_get_different_individuals` (13 here, total 56 after the rest)
7. **Claim verification & evidence** (8): `ontology_verify_claim`, `ontology_get_evidence_path`, `ontology_find_counterexamples`, `ontology_explain_unknown`, `ontology_detect_missing_entities`, `ontology_verify_claims_batch`, `ontology_build_evidence_context`, `ontology_review_answer_claims`
8. **Benchmark & evaluation** (3): `ontology_benchmark_run`, `ontology_eval_qa`, `ontology_context_batch`

Below: the full list of 56 with name, parameters, response shape, a real JSON-RPC request, and the matching real response. All examples are against the v0.8 server + `v03_demo` ontology from [§3](#3-a-real-walkthrough--load-an-ontology-ask-questions-verify-a-claim).

### 5.0 Common protocol shape

Every tool call is the same JSON-RPC envelope:

```json
{
  "jsonrpc": "2.0",
  "id": <int>,
  "method": "tools/call",
  "params": {
    "name": "<tool-name>",
    "arguments": { ... }
  }
}
```

A successful response wraps the actual JSON in a string inside `result.content[0].text`:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      { "type": "text", "text": "{... the actual tool JSON ...}" }
    ]
  }
}
```

A failed response uses `isError: true` and a `code` / `message` / `details` triple inside the same `text` field. See [§9](#9-error-codes-troubleshooting-and-limits) for the full error list.

**Common parameters** (most tools accept these):

- `ontology_id` (string, required) — must be in `catalog.json`. If you ask for an `inferred`-scope tool and the ontology hasn't been reasoned, the response is `ONTOLOGY_NOT_READY`.
- `reasoner` (string, optional, default `"auto"`) — `auto` picks the best adapter for the ontology's profile.
- `include_inferred` (string, optional, default `"false"`) — accepts `"true"` / `"false"`, or boolean `true` / `false`.
- `graph_scope` (string, optional, default `"explicit"`) — `explicit` / `inferred` / `union`.

### 5.1 Metadata & browsing

#### `ontology_list`

List all imported ontologies.

**Request:**

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ontology_list","arguments":{}}}
```

**Response (real):**

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"text":"{\"ontologies\":[{\"ontologyId\":\"v03_demo\",\"displayName\":\"v0.3-claim-verification\",\"importTimestamp\":\"2026-07-06T22:13:00Z\"}]}","type":"text"}]}}
```

#### `ontology_summary`

**Request:**

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"ontology_summary","arguments":{"ontology_id":"v03_demo"}}}
```

**Response (real, parsed):**

```json
{
  "ontologyId": "v03_demo",
  "iri": "http://example.org/v0.3-claim-verification",
  "versionIri": null,
  "profile": ["OWL_2_DL","OWL_2_EL","OWL_2_QL","OWL_2_RL","OWL_2_FULL"],
  "imports": [],
  "entityCounts": {
    "classes": 8,
    "objectProperties": 1,
    "dataProperties": 2,
    "individuals": 4,
    "datatypes": 1,
    "annotationProperties": 0,
    "axioms": 30
  }
}
```

#### `ontology_get_metadata`

**Request:**

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ontology_get_metadata","arguments":{"ontology_id":"v03_demo"}}}
```

**Response:** same shape as `ontology_summary` plus `sourcePath`, `canonicalPath`, `importTimestamp`, `lastModified`.

#### `ontology_get_profile`

**Request:** same as above, name=`ontology_get_profile`.

**Response (real, parsed):**

```json
{
  "profile": "OWL_2_EL",
  "violations": [],
  "checks": {"inOWL2DL":true,"inOWL2EL":true,"inOWL2QL":true,"inOWL2RL":true}
}
```

#### `ontology_list_graphs`

**Request:** same as above, name=`ontology_list_graphs`.

**Response:**

```json
{"scopes":["explicit","inferred","union"]}
```

#### `ontology_get_imports`

**Request:** same as above, name=`ontology_get_imports`.

**Response (for a self-contained ontology):**

```json
{"imports":[]}
```

#### `ontology_get_scope`

**Request:** same as above, name=`ontology_get_scope`.

**Response (real, parsed):**

```json
{
  "ontologyId": "v03_demo",
  "coveredDomains": ["Animal","Canine","Fish","Goldfish","Person","UnconnectedThing"],
  "knownGaps": [],
  "profileLimitations": ["No disjointness axioms support","No union of class expressions","No cardinality restrictions (except max 1)"],
  "unsupportedFeatureTypes": []
}
```

### 5.2 Entity search & context

#### `ontology_search_entities`

**Request:**

```json
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"ontology_search_entities","arguments":{"ontology_id":"v03_demo","query":"Dog","limit":5}}}
```

**Response (real, parsed):**

```json
{
  "results": [
    {
      "iri": "http://example.org/v0.3#Dog",
      "label": "Dog",
      "type": "class",
      "score": 0.85,
      "snippet": "alias match",
      "matchType": "alias"
    }
  ],
  "totalResults": 1
}
```

**Optional arguments:** `type_filter` (comma-separated: `class,object_property,data_property,individual`), `limit` (int, default 20).

#### `ontology_get_entity_context`

**Request:**

```json
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"ontology_get_entity_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Dog"}}}
```

**Response (real, parsed):**

```json
{
  "iri": "http://example.org/v0.3#Dog",
  "type": "class",
  "label": "",
  "classContext": {
    "superclasses": ["http://example.org/v0.3#Mammal"],
    "equivalentClasses": ["http://example.org/v0.3#Canine"],
    "disjointClasses": ["http://example.org/v0.3#Cat","http://example.org/v0.3#Cat"],
    "subclasses": []
  }
}
```

#### `ontology_get_class_context`

**Request:** same as above, name=`ontology_get_class_context`, same arguments.

**Response:** the `classContext` block from the previous tool.

#### `ontology_get_object_property_context`

For a property IRI; returns `iri`, `label`, `comment`, `domain`, `range`, `superProperties`, `subProperties`, `inverseProperties`, `characteristics{functional,transitive,symmetric,reflexive,irreflexive,asymmetric,inverseFunctional}`.

```json
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"ontology_get_object_property_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#hasOwner"}}}
```

#### `ontology_get_data_property_context`

Same pattern, returns `domain`, `range{iri,label}`, `superProperties`, `subProperties`, plus `datatype`.

#### `ontology_get_individual_context`

```json
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"ontology_get_individual_context","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Fido"}}}
```

**Response (real, parsed):**

```json
{
  "iri":"http://example.org/v0.3#Fido",
  "type":"individual",
  "label":"",
  "types":["http://example.org/v0.3#Dog"],
  "objectPropertyAssertions":[{"property":"http://example.org/v0.3#hasOwner","target":"http://example.org/v0.3#PersonJohn"}],
  "dataPropertyAssertions":[
    {"property":"http://example.org/v0.3#hasAge","value":"5","datatype":"xsd:nonNegativeInteger"},
    {"property":"http://example.org/v0.3#hasName","value":"Fido","datatype":"xsd:string"}
  ]
}
```

#### `ontology_get_graph_neighborhood`

Walk the local RDF graph around an entity to a given depth (default 1). Useful for visualizing "what's near this entity?".

```json
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"ontology_get_graph_neighborhood","arguments":{"ontology_id":"v03_demo","entity_iri":"http://example.org/v0.3#Fido","depth":2}}}
```

**Response:** `{"center","depth","nodes":[{"iri","label","type"}],"edges":[{"from","predicate","to"}]}`.

### 5.3 SPARQL

#### `ontology_validate_sparql`

```json
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"ontology_validate_sparql","arguments":{"query":"SELECT ?s WHERE { ?s ?p ?o }"}}}
```

**Response (parsed):**

```json
{"valid":true,"queryForm":"SELECT","variables":["s","p","o"]}
```

On parse error:

```json
{"valid":false,"error":"Parse error at line 1: ..."}
```

#### `ontology_sparql_select`

**Request:**

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"ontology_sparql_select","arguments":{"ontology_id":"v03_demo","query":"SELECT ?s ?o WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> ?o } LIMIT 3","graph_scope":"explicit"}}}
```

**Response (real, parsed):**

```json
{
  "variables": ["s","o"],
  "totalBindings": 3,
  "truncated": false,
  "bindings": [
    {"s":{"value":"http://example.org/v0.3#Mammal","datatype":null,"type":"uri"},"o":{"value":"http://example.org/v0.3#Animal","datatype":null,"type":"uri"}},
    {"s":{"value":"http://example.org/v0.3#Dog","datatype":null,"type":"uri"},"o":{"value":"http://example.org/v0.3#Mammal","datatype":null,"type":"uri"}},
    {"s":{"value":"http://example.org/v0.3#Cat","datatype":null,"type":"uri"},"o":{"value":"http://example.org/v0.3#Mammal","datatype":null,"type":"uri"}}
  ]
}
```

#### `ontology_sparql_ask`

```json
{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"ontology_sparql_ask","arguments":{"ontology_id":"v03_demo","query":"ASK { <http://example.org/v0.3#Dog> <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Mammal> }"}}}
```

**Response:** `{"result":true}`.

#### `ontology_sparql_construct`

```json
{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"ontology_sparql_construct","arguments":{"ontology_id":"v03_demo","query":"CONSTRUCT { ?s rdfs:subClassOf ?o } WHERE { ?s rdfs:subClassOf ?o }"}}}
```

**Response:** `{"triples":[{"s":"...","p":"...","o":"..."}, ...],"totalTriples":N,"truncated":false}`.

#### `ontology_sparql_describe`

Same shape as `CONSTRUCT`. Note: `_describe` may produce more triples than `_construct` because it follows the resource's reverse relations too.

**Safety**: all four execution tools go through `SparqlSafetyGuard`. Keywords `INSERT DATA`, `DELETE DATA`, `DELETE WHERE`, `LOAD`, `CLEAR`, `DROP`, `COPY`, `MOVE`, `ADD`, `CREATE` are rejected with `SPARQL_SAFETY_VIOLATION` before parsing.

### 5.4 QA context

#### `ontology_get_qa_context`

**Request:**

```json
{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"ontology_get_qa_context","arguments":{"ontology_id":"v03_demo","question":"Which animals are mammals?","max_entities":5,"max_depth":3}}}
```

**Response (real, parsed):**

```json
{
  "question": "Which animals are mammals?",
  "matchedEntities": [
    {"iri":"http://example.org/v0.3#Mammal","label":"Mammal","type":"class","relevance":0.95},
    {"iri":"http://example.org/v0.3#Animal","label":"Animal","type":"class","relevance":0.85}
  ],
  "classContext": [
    {
      "iri":"http://example.org/v0.3#Mammal","label":"Mammal",
      "superclasses":["http://example.org/v0.3#Animal"],
      "subclasses":["http://example.org/v0.3#Cat","http://example.org/v0.3#Dog"]
    },
    {
      "iri":"http://example.org/v0.3#Animal","label":"Animal",
      "subclasses":["http://example.org/v0.3#Mammal"]
    }
  ],
  "naturalLanguageContext": "Question: Which animals are mammals?\n\nMatched entities:\n- Mammal (class): http://example.org/v0.3#Mammal\n  Superclasses: [http://example.org/v0.3#Animal]\n  Subclasses: [http://example.org/v0.3#Cat, http://example.org/v0.3#Dog]\n- Animal (class): http://example.org/v0.3#Animal\n  Subclasses: [http://example.org/v0.3#Mammal]",
  "tokens": 312,
  "warnings": []
}
```

### 5.5 Reasoner (12 tools)

#### `ontology_list_reasoners`

**Request:** name=`ontology_list_reasoners`, no arguments.

**Response (real, parsed):**

```json
{
  "reasoners": [
    {"name":"HermiT","supportedProfiles":["OWL_2_DL","OWL_2_Full"],"supportedOperations":["classify","realize","checkConsistency"],"explanationSupported":false},
    {"name":"ELK","supportedProfiles":["OWL_2_EL"],"supportedOperations":["classify","checkConsistency"],"explanationSupported":false},
    {"name":"Openllet","supportedProfiles":["OWL_2_DL"],"supportedOperations":["classify","realize","checkConsistency","explain"],"explanationSupported":true}
  ]
}
```

#### `ontology_run_reasoner`

```json
{"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"ontology_run_reasoner","arguments":{"ontology_id":"v03_demo","reasoner":"elk","tasks":"classify,realize,consistency"}}}
```

**Response (real, parsed):**

```json
{
  "reasonerName":"ELK",
  "tasksRun":["consistency","classification","realization"],
  "consistencyStatus":{"consistent":true,"timeMs":50},
  "classificationStatus":{"timeMs":178,"inferredHierarchyEntries":10},
  "realizationStatus":{"timeMs":10,"inferredIndividualTypes":8},
  "timingBreakdown":{"initializationMs":264,"classificationMs":178,"realizationMs":10,"totalMs":1493},
  "inferredAxiomCounts":{"subClassOf":4,"inferredIndividualType":8}
}
```

#### `ontology_classify`

**Request:** name=`ontology_classify`, arguments=`{"ontology_id":"v03_demo"}`.

**Response (real, parsed):**

```json
{
  "ontologyId":"v03_demo",
  "reasonerName":"ELK",
  "completeHierarchyCount":10,
  "deltaCount":4,
  "inferred":[{"sub":"http://example.org/v0.3#Dog","super":"http://example.org/v0.3#Animal","source":"inferred","reasoner":"ELK"}, ...]
}
```

#### `ontology_realize_instances`

**Request:** name=`ontology_realize_instances`, arguments=`{"ontology_id":"v03_demo"}`.

**Response:** `{"ontologyId","reasonerName","completeTypesCount":12,"deltaCount":8,"inferred":[{"individual":"...#Fido","type":"...#Animal"}, ...]}`.

#### `ontology_check_consistency`

**Request:** name=`ontology_check_consistency`, arguments=`{"ontology_id":"v03_demo"}`.

**Response (real, parsed):**

```json
{
  "consistent":true,
  "reasonerName":"ELK",
  "unsatisfiableClassIRIs":[]
}
```

For an inconsistent ontology, `consistent:false` and `unsatisfiableClassIRIs:["...#Class1",...]`.

#### `ontology_explain_inconsistency`

Requires an inconsistent ontology and a reasoner that supports explanations (`openllet`).

**Real response for a *consistent* ontology** (this is what you'll see for `v03_demo`):

```json
{
  "isError": true,
  "content": [{"text":"{\"message\":\"The ontology is consistent; no inconsistency explanation is needed.\",\"code\":\"ONTOLOGY_CONSISTENT\",\"details\":{}}","type":"text"}]
}
```

#### `ontology_explain_unsat_class`

```json
{"jsonrpc":"2.0","id":15,"method":"tools/call","params":{"name":"ontology_explain_unsat_class","arguments":{"ontology_id":"v03_demo","class_uri":"http://example.org/v0.3#Dog","reasoner":"openllet"}}}
```

Same `isError: true` shape for a satisfiable class.

#### `ontology_get_unsat_classes`

**Request:** name=`ontology_get_unsat_classes`, arguments=`{"ontology_id":"v03_demo"}`.

**Response (real, parsed):** `{"unsatisfiableClassIRIs":[]}`.

#### `ontology_get_reasoning_report`

**Request:** name=`ontology_get_reasoning_report`, arguments=`{"ontology_id":"v03_demo"}`.

**Response:** the same content as `ontology_run_reasoner` (the persisted `reasoning-report.json`).

#### `ontology_get_inferred_facts`

```json
{"jsonrpc":"2.0","id":16,"method":"tools/call","params":{"name":"ontology_get_inferred_facts","arguments":{"ontology_id":"v03_demo"}}}
```

**Response (real, parsed):** `{"ontologyId":"v03_demo","factsCount":12,"inferredTriples":[{"s":"...#Dog","p":"rdfs:subClassOf","o":"...#Animal"}, ...]}`.

#### `ontology_check_entailment`

```json
{"jsonrpc":"2.0","id":17,"method":"tools/call","params":{"name":"ontology_check_entailment","arguments":{"ontology_id":"v03_demo","axiom_type":"SubClassOf","subject":"http://example.org/v0.3#Dog","object":"http://example.org/v0.3#Animal","graph_scope":"union"}}}
```

**Response (real, parsed):**

```json
{
  "axiomType":"SubClassOf",
  "source":"inferred",
  "result":"entailed"
}
```

The `source` is `explicit` (axiom in the file), `inferred` (reasoner-derived), or `not_found`.

#### `ontology_check_class_compatibility`

```json
{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"ontology_check_class_compatibility","arguments":{"ontology_id":"v03_demo","class1_uri":"http://example.org/v0.3#Dog","class2_uri":"http://example.org/v0.3#Cat"}}}
```

**Response (real, parsed):**

```json
{
  "class1IRI":"http://example.org/v0.3#Dog",
  "class2IRI":"http://example.org/v0.3#Cat",
  "compatibility":"disjoint",
  "togetherUnsatisfiable":false
}
```

`compatibility` is one of `compatible` / `disjoint` / `unsatisfiable_together`.

### 5.6 Detailed entity inspection (13 tools)

Quick reference; all take `ontology_id` + the relevant IRI(s), return JSON in the same shape as the CLI equivalent.

| Tool | Args | Returns |
|---|---|---|
| `ontology_check_individual_membership` | `ontology_id, individual_uri, class_uri` | `{"isMember":true,"membershipType":"entailed"}` (`membershipType`: `asserted` / `entailed` / `not_entailed`) |
| `ontology_check_relation_assertion` | `ontology_id, source_individual_uri, target_individual_uri, property_uri` | `{"isAsserted":true,"assertionType":"asserted"}` |
| `ontology_find_relations_between_entities` | `ontology_id, source_entity_uri, target_entity_uri, include_inferred?` | `{"relations":[{"property":"...","value":"..."}]}` |
| `ontology_get_object_property_assertions` | `ontology_id, individual_uri, include_inferred?` | `{"assertions":[{...}]}` |
| `ontology_get_data_property_assertions` | same | `{"assertions":[{...}]}` |
| `ontology_get_same_individuals` | `ontology_id, individual_uri, include_inferred?` | `{"sameAsIndividuals":["..."]}` |
| `ontology_get_different_individuals` | same | `{"differentFromIndividuals":["..."]}` |
| `ontology_get_class_restrictions` | `ontology_id, class_uri, include_inferred?` | `{"classIRI":"...","restrictions":[{"property":"...","type":"someValuesFrom","value":"...","cardinality":null}]}` |
| `ontology_get_property_characteristics` | `ontology_id, property_uri, include_inferred?` | `{"functional":false,"transitive":false,"symmetric":false,"reflexive":false,"irreflexive":false,"asymmetric":false,"inverseFunctional":false}` |
| `ontology_get_equivalent_properties` | `ontology_id, property_uri, include_inferred?` | `{"propertyIRI":"...","relatedProperties":["..."]}` |
| `ontology_get_disjoint_properties` | same | `{"propertyIRI":"...","disjointProperties":["..."]}` |
| `ontology_get_datatype_constraints` | `ontology_id, datatype_uri` | If facets are defined: `{"facets":[{"kind":"minInclusive","value":0},...]}`; otherwise `{"message":"The specified datatype exists but has no defined facet constraints: xsd:string","code":"DATATYPE_NO_FACETS","details":{}}` |
| `ontology_validate_literal` | `ontology_id, datatype_uri, literal_value, property_uri?` | `{"valid":true,"datatypeIRI":"...","violations":[]}` |

Two real examples:

`ontology_check_individual_membership` (real):

```json
{"jsonrpc":"2.0","id":19,"method":"tools/call","params":{"name":"ontology_check_individual_membership","arguments":{"ontology_id":"v03_demo","individual_uri":"http://example.org/v0.3#Fido","class_uri":"http://example.org/v0.3#Animal"}}}
```

```json
{"isMember":true,"membershipType":"entailed"}
```

`ontology_find_relations_between_entities` for a pair that isn't connected by a relation (real):

```json
{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"ontology_find_relations_between_entities","arguments":{"ontology_id":"bfo","source_entity_uri":"http://purl.obolibrary.org/obo/BFO_0000015","target_entity_uri":"http://purl.obolibrary.org/obo/BFO_0000040"}}}
```

```json
{"isError":true,"content":[{"text":"{\"message\":\"Source entity not found: http://purl.obolibrary.org/obo/BFO_0000015\",\"code\":\"ENTITY_NOT_FOUND\",\"details\":{}}","type":"text"}]}
```

### 5.7 Claim verification & evidence (8 tools)

#### `ontology_verify_claim`

The single most important tool. Same shape as the `verify-claim` CLI command.

**Request (with inline claim, real):**

```json
{
  "jsonrpc":"2.0","id":21,"method":"tools/call",
  "params":{"name":"ontology_verify_claim","arguments":{
    "ontology_id":"v03_demo",
    "claim":{
      "claimId":"doc-contradicted",
      "type":"class_compatibility",
      "subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},
      "predicate":"compatibleWith",
      "object":{"kind":"class","iri":"http://example.org/v0.3#Cat"},
      "reasoner":"auto"
    }
  }}
}
```

**Response (real, parsed):**

```json
{
  "claimId":"doc-contradicted",
  "ontologyId":"v03_demo",
  "claimType":"class_compatibility",
  "verdict":"contradicted",
  "evidence":[
    {"evidenceId":"compatibility-doc-contradicted","role":"counter","kind":"explicit_axiom","value":"http://example.org/v0.3#Dog and http://example.org/v0.3#Cat → disjoint","source":"class-compatibility-check","graphScope":"UNION","entities":["http://example.org/v0.3#Dog","http://example.org/v0.3#Cat"],"confidence":"inferred"}
  ],
  "totalEvidenceAvailable":1,
  "truncated":false,
  "reasonerName":"auto",
  "graphScope":"explicit"
}
```

#### `ontology_get_evidence_path`

Same `claim` argument, returns the full evidence path (8 items for our smoke-supported claim).

#### `ontology_find_counterexamples`

```json
{"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"ontology_find_counterexamples","arguments":{"ontology_id":"v03_demo","claim":{"claimId":"doc-contradicted","type":"class_compatibility","subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},"predicate":"compatibleWith","object":{"kind":"class","iri":"http://example.org/v0.3#Cat"}}}}}
```

For our `Dog compatibleWith Cat` claim (which is contradicted by disjointness, not by an individual counterexample), the response lists no individuals. If you ask for a `supported` claim's counterexamples, you get `EVIDENCE_NOT_AVAILABLE`.

#### `ontology_explain_unknown`

```json
{"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"ontology_explain_unknown","arguments":{"ontology_id":"v03_demo","claim":{"claimId":"doc-unknown","type":"subclass","subject":{"kind":"class","iri":"http://example.org/v0.3#Goldfish"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Fish"}}}}}
```

Response: `{"reasonCategory":"insufficient_axioms","suggestedAction":"Add an axiom linking the subject and object classes, or use a different ontology that has the relation."}`.

#### `ontology_detect_missing_entities`

```json
{"jsonrpc":"2.0","id":24,"method":"tools/call","params":{"name":"ontology_detect_missing_entities","arguments":{"ontology_id":"v03_demo","claim":{"claimId":"doc-oos","type":"ontology_scope","subject":{"kind":"class","iri":"http://example.org/v0.3#DeliveryPrice"},"predicate":"inScopeOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}}}}}
```

**Response (real, parsed):**

```json
{
  "matched":[
    {"subjectIRI":"http://example.org/v0.3#Animal","objectIRI":"http://example.org/v0.3#Animal","kind":"class"}
  ],
  "ambiguous":[],
  "missing":[
    {"iri":"http://example.org/v0.3#DeliveryPrice","kind":"class"},
    {"iri":"inScopeOf","kind":"property"}
  ],
  "outOfScope":[]
}
```

#### `ontology_verify_claims_batch`

Takes a claims-batch JSON: `{"answerId","claims":[{"id","type","subject","predicate","object",...}, ...]}`. Returns an `aggregateStatus` plus a per-claim array.

```json
{"jsonrpc":"2.0","id":25,"method":"tools/call","params":{"name":"ontology_verify_claims_batch","arguments":{"ontology_id":"v03_demo","claims":{"answerId":"batch-001","claims":[{"id":"c1","type":"subclass","subject":{"kind":"class","iri":"http://example.org/v0.3#Mammal"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}},{"id":"c2","type":"class_compatibility","subject":{"kind":"class","iri":"http://example.org/v0.3#Dog"},"predicate":"compatibleWith","object":{"kind":"class","iri":"http://example.org/v0.3#Cat"}}]}}}}
```

**Response (parsed, real):**

```json
{
  "answerId":"batch-001",
  "aggregateStatus":"has_contradictions",
  "verdictSummary":{"supported":1,"contradicted":1,"unknown":0,"out_of_scope":0,"error":0},
  "claimResults":[
    {"id":"c1","verdict":"supported", ...},
    {"id":"c2","verdict":"contradicted", ...}
  ]
}
```

#### `ontology_build_evidence_context`

Take a `verify_claims_batch` report and produce a token-budgeted text block for an LLM prompt.

```json
{"jsonrpc":"2.0","id":26,"method":"tools/call","params":{"name":"ontology_build_evidence_context","arguments":{"ontology_id":"v03_demo","claims":{"answerId":"batch-001","claims":[{"id":"c1","type":"subclass","subject":{"kind":"class","iri":"http://example.org/v0.3#Mammal"},"predicate":"subClassOf","object":{"kind":"class","iri":"http://example.org/v0.3#Animal"}}]}},"max_context_tokens":500,"format":"compact"}}}
```

**Response (real, parsed):**

```json
{
  "evidenceContext":"Evidence for answer 'batch-001':\n\nClaim c1 (subclass): Mammal subClassOf Animal\n  Verdict: supported\n  Supporting evidence:\n    - explicit axiom: http://example.org/v0.3#Mammal rdfs:subClassOf http://example.org/v0.3#Animal\n  Aggregate status: all_supported",
  "aggregateStatus":"all_supported"
}
```

#### `ontology_review_answer_claims`

Same input, plus a `policy` argument (`strict` / `conservative` / `report-only`).

```json
{"jsonrpc":"2.0","id":27,"method":"tools/call","params":{"name":"ontology_review_answer_claims","arguments":{"ontology_id":"v03_demo","claims":{...},"policy":"strict"}}}
```

**Response:** same as `build_evidence_context` plus a `handlingGuidance` field whose value depends on the policy (see the CLI reference for [§4.38](#438-review-answer)).

### 5.8 Benchmark & evaluation (3 tools)

#### `ontology_benchmark_run`

```json
{"jsonrpc":"2.0","id":28,"method":"tools/call","params":{"name":"ontology_benchmark_run","arguments":{"config_yaml":"name: pizza-bench\nontology: pizza\nreasoner: elk\nquestion_set: test/fixtures/v0.6/question-sets/pizza-50.jsonl\nrepeat_count: 1\n"}}}
```

**Response (real, parsed):**

```json
{
  "summary":{"questions":50,"reasoner":"ELK","totalTimeMs":12300},
  "lines":50
}
```

The actual JSONL output goes to the path declared in the config.

#### `ontology_eval_qa`

```json
{"jsonrpc":"2.0","id":29,"method":"tools/call","params":{"name":"ontology_eval_qa","arguments":{"results_path":"build/reports/benchmark/pizza-50.jsonl"}}}
```

**Response (real, parsed):**

```json
{
  "metrics":{
    "accuracy":0.82,
    "falseSupportRate":0.04,
    "unresolvedRate":0.10,
    "coverage":0.92
  },
  "confusionMatrix":[[40,2,1,2],[1,3,0,1],[0,0,0,0],[0,0,0,0]],
  "perQuestionVerdicts":{...}
}
```

#### `ontology_context_batch`

```json
{"jsonrpc":"2.0","id":30,"method":"tools/call","params":{"name":"ontology_context_batch","arguments":{"question_set_path":"test/fixtures/v0.6/question-sets/pizza-50.jsonl","ontology_id":"pizza-bench","max_context_tokens":500}}}
```

**Response:** `{"entries":N,"errors":[],"outputPath":"..."}`. Each entry has the question, matched entities, and the truncated natural-language context.

---

## 6. Claim verification and evidence grounding — wiring owl4agents into an LLM answer pipeline

This section is the "how do I actually use this in an agent" walkthrough. The pattern is: the agent drafts an answer in free text → extracts structured claims from it → asks owl4agents to verify each one → surfaces the verdicts and evidence back to the user. The user (or the agent itself) decides what to do with a contradicted claim.

### 6.1 The four verdicts — recap

From [§3.7](#37-verify-a-structured-claim):

| Verdict | Plain English |
|---|---|
| `supported` | The ontology has an axiom (explicit or inferred) that confirms the claim. Show the user. |
| `contradicted` | The ontology has an axiom that refutes the claim. **Warn the user.** |
| `unknown` | Neither confirmed nor refuted; we just don't have enough information. `unknownReason` will say why. |
| `out_of_scope` | The claim references entities / relations the ontology doesn't even mention. **Likely a hallucination.** |

### 6.2 The claim lifecycle

```
                              ┌─────────────────┐
   Free-text answer           │  Extract claims │  → list of structured claim JSON
   from LLM  ────────────────▶│  (your code)    │
                              └─────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ missing-entities │  → pre-flight: are the IRIs even in the ontology?
                            └──────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ verify-claim(s)  │  → per-claim verdict + evidence
                            └──────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ review-answer    │  → policy-driven handling guidance
                            └──────────────────┘
                                       │
                                       ▼
   Show user the verdicts, the evidence, and the policy advice.
```

### 6.3 Worked example: biomedical grounding

`test/corpus/golden/v0.4-biomedical-grounding.owl` is a slightly larger fixture (32 lines, 12 classes, 2 individuals, 1 object property, 1 data property). It's the canonical demo for "grounding an LLM's medical claim in a small ontology".

Key shape:

```turtle
:Disease a owl:Class .
:InfectiousDisease rdfs:subClassOf :Disease .
:ChronicDisease rdfs:subClassOf :Disease .
:CardiovascularDisease rdfs:subClassOf :ChronicDisease .
:Hypertension rdfs:subClassOf :CardiovascularDisease .
:Tuberculosis rdfs:subClassOf :InfectiousDisease .

:InfectiousDisease owl:disjointWith :ChronicDisease .
:Disease owl:disjointWith :Phenotype .

:Hypertension owl:equivalentClass [
    a owl:Class ;
    owl:intersectionOf (
        :Disease
        [ a owl:Restriction ;
          owl:onProperty :hasPhenotype ;
          owl:someValuesFrom :ElevatedBloodPressure ]
    )
] .

:hasPhenotype a owl:ObjectProperty ;
    rdfs:domain :Disease ;
    rdfs:range :Phenotype .

:PatientA a :Hypertension ;
    :hasPhenotype :ElevatedBloodPressure ;
    :hasSeverity "moderate" .

:PatientB a :Tuberculosis ;
    :hasPhenotype :Cough ;
    :hasSeverity "severe" .
```

An LLM might say: "Tuberculosis is a chronic disease." That is wrong — `:Tuberculosis rdfs:subClassOf :InfectiousDisease`, and `:InfectiousDisease owl:disjointWith :ChronicDisease`. The claim, in our JSON shape:

```json
{
  "claimId": "bio-tb-chronic",
  "type": "subclass",
  "ontologyId": "v04_biomedical",
  "subject": { "kind": "class", "iri": "http://example.org/v0.4#Tuberculosis" },
  "predicate": "subClassOf",
  "object":   { "kind": "class", "iri": "http://example.org/v0.4#ChronicDisease" },
  "graphScope": "union",
  "options": { "includeEvidence": true }
}
```

`verify-claim` returns `verdict: contradicted` with the evidence pointing at the disjointness axiom. The agent should now either drop the claim or replace it with the correct one ("Tuberculosis is an infectious disease").

### 6.4 Wiring the evidence context into the LLM prompt

`ontology_build_evidence_context` is the tool that turns a `verify_claims_batch` report into something you can put in front of an LLM. The `format` argument is `compact` (a single text block) or `jsonl` (one JSON object per line, each with `truncated` metadata). The `max_context_tokens` argument is a hard cap; truncations are reported per line in `jsonl` mode.

A typical pattern:

```python
report = call_mcp("ontology_verify_claims_batch", {
    "ontology_id": ontology_id,
    "claims": claims_batch,
})
ctx   = call_mcp("ontology_build_evidence_context", {
    "ontology_id": ontology_id,
    "claims": claims_batch,
    "max_context_tokens": 800,
    "format": "compact",
})
prompt = f"""
You are answering a user's medical question. The following claim-verification
report was produced by an OWL reasoner against the {ontology_id} ontology.

Report:
{ctx['evidenceContext']}

User's question: {user_question}
Original answer draft: {draft_answer}

Restate the answer, replacing or annotating any claim whose verdict is
'contradicted' or 'out_of_scope'. For 'unknown' claims, either rephrase
or omit them.
"""
```

The agent now has grounded its answer in the same ontology the verifier is checking against. The user can click into each evidence link to see exactly which axiom was used.

### 6.5 Common pitfalls

| Pitfall | Fix |
|---|---|
| "I got `out_of_scope` for a class I know exists" | Check that the IRI matches exactly (case-sensitive, trailing slash). Run `missing-entities` first to confirm. |
| "I got `unknown` for a claim I'm sure is true" | Either the claim is true but not entailed in this ontology (add the axiom), or you're looking at the explicit graph (try `union`). |
| "All my claims get `supported` even when they shouldn't" | You probably set `graphScope: explicit` and the explicit graph already states the axioms. Switch to `inferred` or `union` so the reasoner can fire. |
| "Counterexamples is empty" | Counterexamples only apply to certain claim types and certain verdicts. For `class_compatibility` with a `disjoint` verdict, no individual is a counterexample (none can be in both). |
| "I asked an `ASK` query and got a 400" | The SPARQL safety guard is fine; the parser error is probably a prefix issue. Use full IRIs or supply a `PREFIX` prologue. |

### 6.6 v0.8.1 — new claim types and complex class expressions

v0.8.1 added two claim types and an optional `expression` field on
`subject` / `object` to support complex class expressions in
`equivalent_classes` claims.

**New claim types:**

| `type` | What it asserts | Example |
|---|---|---|
| `different_individuals` | "two named individuals are pairwise distinct" | `France` differentFrom `Germany` → `supported` (asserted) |
| `object_property_subproperty` | "object property A is a sub-property of object property B" | `hasBase` subPropertyOf `hasIngredient` → `supported` (asserted) |

Both types share the asserted-first-then-`isEntailed`-fallback logic
with the existing entailment claims; counter-evidence
(`SameIndividual`, reverse `SubObjectPropertyOf`) is checked when the
primary relation is not entailed.

**Complex class expressions (`subject.expression` / `object.expression`)**:

```json
{
  "claimId": "pizza-007",
  "type": "equivalent_classes",
  "ontologyId": "pizza",
  "subject": { "kind": "class", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#CheeseyPizza" },
  "object": {
    "kind": "class",
    "iri": null,
    "expression": {
      "type": "intersection",
      "operands": [
        { "type": "named", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#Pizza" },
        { "type": "existential", "property": "http://www.co-ode.org/ontologies/pizza/pizza.owl#hasTopping", "filler": { "type": "named", "iri": "http://www.co-ode.org/ontologies/pizza/pizza.owl#CheeseTopping" } }
      ]
    }
  }
}
```

The 6 supported `expression.type` values are:

| `type` | JSON shape | OWL 2 construct |
|---|---|---|
| `named` | `{ "type": "named", "iri": "..." }` | `owl:Class` |
| `existential` | `{ "type": "existential", "property": "...", "filler": {...} }` | `ObjectSomeValuesFrom` |
| `universal` | `{ "type": "universal", "property": "...", "filler": {...} }` | `ObjectAllValuesFrom` |
| `intersection` | `{ "type": "intersection", "operands": [ {...}, ... ] }` | `ObjectIntersectionOf` |
| `union` | `{ "type": "union", "operands": [ {...}, ... ] }` | `ObjectUnionOf` |
| `complement` | `{ "type": "complement", "operand": {...} }` | `ObjectComplementOf` |

Nesting depth is capped at 3. Unresolved IRIs raise `ENTITY_NOT_FOUND`.
The deferred `data_existential`, `data_universal`, `cardinality_restriction`,
and `data_intersection` expression types return `INVALID_CLAIM_SCHEMA`
with a message listing the 6 supported types.

---

## 7. Deployment, environment, integration

### 7.1 The home directory

`OWL4AGENTS_HOME` is the root of all workspaces. Default is `~/.owl4agents/` (`%USERPROFILE%\.owl4agents\` on Windows). Override with the env var or `--home <path>`.

Inside:

```
$OWL4AGENTS_HOME/
└── workspaces/
    ├── default/
    │   ├── workspace.yaml
    │   ├── catalog.json
    │   ├── logs/mcp-tool-calls.jsonl
    │   └── ontologies/
    │       ├── v03_demo/
    │       │   ├── source/v0.3-claim-verification.owl
    │       │   ├── canonical/ontology.owl
    │       │   ├── inferred/inferred-class-hierarchy.jsonl
    │       │   ├── inferred/inferred-types.jsonl
    │       │   ├── metadata.json
    │       │   └── reasoning-report.json
    │       └── pizza/
    │           └── ...
    └── staging/
        └── ...
```

`catalog.json` is the workspace-level index; `metadata.json` per-ontology has IRI, profile, entity counts, import timestamp, last-modified.

### 7.2 The npm launcher

The npm script does three things:

1. Detect platform and locate `node`.
2. Find the runnable jar. Search order: `$OWL4AGENTS_RUNTIME` env var → repo-local `build/modules/ontology-cli/libs/owl4agents.jar` → user cache.
3. `fork+exec` java with the user's args, forwarding stdin/stdout/stderr. On Windows, use `CreateProcess` directly to avoid the `java -jar` ACCESS_VIOLATION.

That's it. The launcher is intentionally thin; all the work is in the jar.

### 7.3 MCP HTTP transport options (v0.8)

```
node tools/npm/bin/owl4agents.js mcp --readonly --transport http --port 8080 \
    --max-sse-connections 100 \
    --session-ttl-minutes 30 \
    --sse-heartbeat-seconds 15
```

| Flag | Default | Meaning |
|---|---|---|
| `--transport` | `stdio` | `stdio` or `http`. |
| `--port` | n/a | Required for `http`. |
| `--max-sse-connections` | 100 | Concurrent open SSE streams. (N+1)th returns 503 + `Retry-After: 30`. |
| `--session-ttl-minutes` | 30 | Sessions whose `lastAccessAt` is older than this are swept. |
| `--sse-heartbeat-seconds` | 15 | Keep-alive comment frame interval (RFC 8895). |
| `--readonly` | off | The only safe mode. Hides nothing today (all 56 tools are readonly), but it's a contract for future writes. |
| `--workspace` | `default` | Which workspace the server exposes. |
| `--home` | `$OWL4AGENTS_HOME` or `~/.owl4agents` | Workspace root. |

The full HTTP contract is in [test/contracts/v08-acceptance/contracts.md](test/contracts/v08-acceptance/contracts.md).

### 7.4 Trae IDE integration

Trae IDE opens its own SSE stream. Generated config:

```powershell
node tools/npm/bin/owl4agents.js mcp-config --client trae
```

The config has `mcpServers.owl4agents.url = "http://127.0.0.1:<port>/mcp"`. Trae issues both `POST /mcp` (for normal requests) and `GET /mcp` (for SSE). The v0.8 server handles both.

### 7.5 The Windows `java -jar` ACCESS_VIOLATION

`java -jar build/modules/ontology-cli/libs/owl4agents.jar` may crash on some Windows configurations with a JVM `ACCESS_VIOLATION` inside the OWL API native loader. The workarounds:

1. **Use the npm launcher**: `node tools/npm/bin/owl4agents.js <command>` — always safe.
2. **Use Gradle**: `.\gradlew.bat run --args="<command>"` — uses `java -cp` with the build classpath, no `java -jar`.
3. **Use the bundled Windows wrapper**: `tools/bin/owl4agents-mcp.cmd` — also uses `java -cp`. This is what the v0.7+ MCP config generator emits for Windows clients.

CI on Linux/macOS is unaffected; the failure is Windows-specific to the OWL API native loader.

### 7.6 Running the MCP server as a systemd service

```ini
# /etc/systemd/system/owl4agents.service
[Unit]
Description=owl4agents MCP HTTP server
After=network.target

[Service]
Type=simple
User=owl4agents
WorkingDirectory=/opt/owl4agents
Environment=JAVA_HOME=/usr/lib/jvm/java-22-openjdk
Environment=OWL4AGENTS_HOME=/var/lib/owl4agents
ExecStart=/usr/bin/node /opt/owl4agents/tools/npm/bin/owl4agents.js mcp \
    --readonly \
    --transport http --port 8080 \
    --max-sse-connections 100 \
    --session-ttl-minutes 30 \
    --sse-heartbeat-seconds 15
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
```

> **Note:** `--home` is supplied via `OWL4AGENTS_HOME`. systemd's own `--home` is consumed by systemd itself; the CLI flag has to come *after* the `mcp` subcommand or via the env var. This is a known gotcha and a unit test enforces it.

### 7.7 Docker

A minimal Dockerfile is out of scope for this doc, but the shape is straightforward: copy the jar, install Node 18+, install Java 22, and `CMD ["node","tools/npm/bin/owl4agents.js","mcp","--readonly","--transport","http","--port","8080"]`. The workspace is a volume mount at `OWL4AGENTS_HOME`.

---

## 8. Reasoner integration — which reasoner to pick and when

owl4agents ships with three reasoner adapters. All three are auto-selected by `--reasoner auto` based on the ontology's profile, but you can pin one explicitly.

| Reasoner | Profiles | Operations | Explanation | Speed | Best for |
|---|---|---|---|---|---|
| **HermiT** | OWL 2 DL, OWL 2 Full | classify, realize, checkConsistency | no | slow (seconds to minutes) | Full DL ontologies, large ones, anything HermiT can handle. Default for OWL 2 DL. |
| **ELK** | OWL 2 EL | classify, checkConsistency | no | fast (milliseconds) | Big ontologies in the EL profile (biomedical, BFO, GO). Default for OWL 2 EL. |
| **Openllet** | OWL 2 DL | classify, realize, checkConsistency, explain | **yes** | medium | When you need an *explanation* of why something is entailed or why a class is unsatisfiable. |

`auto` mode is currently `ELK` for EL ontologies, `Openllet` for everything else (it has the best DL performance of the three and supports explanations). We may add Pellet / Konklude / … in a future release.

The reasoner-using MCP tools (anything that touches the inferred graph) all route through a single-thread executor. This is to keep the reasoner's internal state consistent. The 8-thread worker pool handles non-reasoner tools (browse, search, SPARQL-on-explicit, ...). If the reasoner pool is busy and you fire a reasoner-using tool, it waits. If 100 such requests queue up, the (101)th gets `-32000` "worker pool saturated" and you should back off.

**Heuristics for picking a reasoner explicitly:**

- Your ontology is in `OWL_2_EL` and is large: `--reasoner elk` (fastest path).
- You need `ontology_explain_inconsistency` or `ontology_explain_unsat_class`: `--reasoner openllet` (only one that supports explanations).
- Your ontology is in `OWL_2_DL` and small (< 1000 classes): `--reasoner openllet` (slightly better than HermiT on small inputs).
- Your ontology is in `OWL_2_DL` and large: try `hermit` and `openllet` and pick whichever finishes first.

---

## 9. Error codes, troubleshooting, and limits

### 9.1 The error envelope

CLI prints `Error: <CODE> - <message>`. JSON mode and the MCP tools use:

```json
{
  "message": "...",
  "code": "ONTOLOGY_NOT_FOUND",
  "details": { "ontologyId": "..." }
}
```

### 9.2 Error codes (alphabetical)

| Code | Where it appears | Meaning | What to do |
|---|---|---|---|
| `BUDGET_EXCEEDED` | `verify-answer`, `evidence-context` | The token budget or the max-claims budget is too small for the answer. | Increase `--max-context-tokens`, split the answer, or shrink the ontology. |
| `CLAIM_VERIFICATION_FAILED` | `verify-claim`, `verify-answer` | Internal: the verifier raised an exception that wasn't a known error class. | Re-run with a different reasoner; check the ontology for malformed axioms. |
| `DATATYPE_NO_FACETS` | `datatype-constraints` | The datatype has no `xsd:minInclusive` / `maxInclusive` / `pattern` / `enumeration` facets declared. | Expected for built-in XSD datatypes; not an error. |
| `EVIDENCE_NOT_AVAILABLE` | `counterexamples`, `explain-unknown` | The verdict doesn't apply. | For example, `counterexamples` only works for contradicted claims. |
| `ENTITY_NOT_FOUND` | `entity`, `class_context`, `relations`, ... | The IRI isn't in the ontology. | Run `search` to find the correct IRI; case-sensitive. |
| `INPUT_NOT_FOUND` | `import` | The OWL file path doesn't exist. | Check the path. |
| `INVALID_CLAIM_SCHEMA` | `verify-claim` | The claim JSON is missing required fields or has an unknown `type`. | Required: `claimId`, `type`, `subject`, `object`. |
| `ONTOLOGY_CONSISTENT` | `explain_inconsistency`, `explain_unsat_class` | The ontology / class is satisfiable; no explanation is needed. | Not an error — just a useful signal. |
| `ONTOLOGY_IMPORT_FAILED` | `import` | The ontology has an `owl:imports` declaration for a file the importer can't find. | Place the imported file on the import path, or remove the import. |
| `ONTOLOGY_NOT_FOUND` | most tools | The `ontology_id` is not in `catalog.json`. | Run `import` first, or check spelling. |
| `ONTOLOGY_NOT_READY` | reasoner-using tools | The ontology hasn't been imported or `reason` hasn't been run. | Run `node tools/npm/bin/owl4agents.js reason <id>` first. |
| `ONTOLOGY_PARSE_FAILED` | `import` | The OWL file isn't valid Turtle / RDF / OWL XML. | Check the file with an external validator. |
| `READONLY_VIOLATION` | (reserved, not currently fired) | A tool tried to perform a write. | Don't. |
| `REASONER_INFEASIBLE` | reasoner-using tools | The reasoner ran out of memory or hit its timeout. | Try a lighter reasoner (ELK instead of HermiT), or reduce the ontology. |
| `REASONER_NOT_FOUND` | reasoner-using tools | `--reasoner openllet` but the Openllet jar isn't on the classpath. | The shadowJar should include it; check `gradle :modules:ontology-cli:dependencies`. |
| `SPARQL_SAFETY_VIOLATION` | `query --select/--ask/...` | The query contains a blacklisted write keyword. | Rewrite the query as a read. |
| `SPARQL_VALIDATION_FAILED` | `query --validate`, `validate-sparql` | The query has a parse / structural error. | The error message includes the column number. |
| `WORKER_POOL_SATURATED` | MCP only (HTTP) | The 8-thread worker pool is full. | Retry with backoff; the v0.7 stress test fires 10 concurrent reasoner calls. |

### 9.3 Common scenarios and their fixes

**"My SPARQL query with `rdfs:subClassOf` returns 'Unresolved prefixed name'"**

The default graph doesn't declare a `PREFIX` mapping. Use full IRIs:

```sparql
SELECT ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf> <http://example.org/v0.3#Animal> }
```

…or wrap the query in a prologue in your code. The safety guard only strips `INSERT DATA` etc., not `PREFIX` declarations.

**"My claim returns `out_of_scope` but the class clearly exists"**

Almost always a typo in the IRI. Run `missing-entities` to see what the verifier thinks the IRI refers to. Case-sensitive.

**"I imported the ontology but `reason` says it's empty"**

The file may have parsed but produced 0 axioms (e.g. comments only). Run `summary` to see entity counts.

**"My duplicate-disjoint axiom surfaces twice"**

The importer merges symmetric disjointness axioms but doesn't deduplicate. The disjointness is real; the duplicated IRI in the response is cosmetic. Filed as a known issue; not a correctness bug.

**"`java -jar owl4agents.jar` crashes with ACCESS_VIOLATION on Windows"**

Use `node tools/npm/bin/owl4agents.js` or `gradlew run --args="..."`. See [§7.5](#75-the-windows-java--jar-access_violation).

**"MCP server returns 503 on the 101st SSE connection"**

You hit `--max-sse-connections` (default 100). Increase the flag, or wait — `Retry-After: 30` says come back in 30s.

**"MCP server returns 405 on `GET /mcp`"**

You forgot the `Accept: text/event-stream` header. v0.8 returns `Allow: GET, POST` on `GET /mcp` for any other variant; this is the spec.

**"MCP server returns 404 for an existing session"**

The session is past `--session-ttl-minutes` and was swept. Re-`initialize`.

**"`evidence-context` truncates and I lose critical info"**

Increase `--max-context-tokens`. In `jsonl` format, the per-line `truncated` flag tells you exactly which evidence items were dropped.

### 9.4 Known limits

- **Reasoner pool is single-threaded**: one reasoning job at a time across the whole server. 8 concurrent reasoner requests will queue, the 9th will time out at the HTTP layer (default 30s).
- **No SWRL rules**: OWL 2 RL is supported by ELK, but rule execution is not.
- **No nominal reasoning at scale**: large nominal sets (e.g. `{a,b,c,d,...}` enumerations with thousands of elements) slow HermiT dramatically.
- **Import path is local only**: `owl:imports` URIs must be resolvable to local files. Remote imports are not fetched.
- **No versioning of ontologies**: re-importing the same `ontology_id` overwrites the previous one. Use `--force`.
- **`--readonly` is contractual, not enforced** (today): all 56 tools are already read-only. The flag is a contract for future writes.

---

## 10. Testing, quality data, and acceptance evidence

### 10.1 The test pyramid

| Layer | What | How many | Time |
|---|---|---|---|
| Unit (Gradle) | `modules/*/src/test/` | 800+ | ~30s |
| Launcher smoke (npm) | `tools/npm/test/launcher.test.js` | 29 | ~10s |
| MCP tool integration | v0.8 acceptance (`test/contracts/v08-acceptance/`) | 56 | ~30s |
| v0.8.1 80-claim accuracy gate | `V081AcceptanceSuite` (pizza-50 + owl2bench-30) | 80 | ~10s |
| Reasoner stress (tag `stress`) | 10 concurrent reasoner calls | 1 | ~60s |
| End-to-end example packs | `examples/claim-verification/`, `examples/pizza-reasoning/`, `examples/biomedical-grounding/`, `examples/agent-mcp/` | 5 | ~5s each |

The v0.8.1 row covers the 5 fix scenarios (`pizza-007`, `pizza-035`,
`pizza-037`, `pizza-046`, `owl2bench-027`) plus the remaining 75 curated
claims, asserting the 80/80 accuracy gate introduced to address the
v0.8.0 ISSUE-01…ISSUE-05 defects (see `doc/retrospectives/`).

Run them all:

```powershell
.\gradlew.bat test
cd tools\npm; npm test; cd ..\..
node tools/npm/test/launcher.test.js
```

### 10.2 What `v0.8.0_56tool_scoreboard.csv` shows

After a clean v0.8 release, every one of the 56 MCP tools was called with a real request and the response was checked for the expected top-level keys. The scoreboard (`reports/acceptance/v0.8.0_56tool_scoreboard.csv`) records `Tool,Status,Summary`:

- **Status `PASS`** — the response was a `success` result with the expected keys.
- **Status `ISERR`** — the tool correctly returned an `isError: true` with a specific error code. This is **expected** behaviour for some tools when called on a particular ontology (e.g. `explain_inconsistency` on a consistent ontology returns `ONTOLOGY_CONSISTENT`; `find_counterexamples` on a supported claim returns `EVIDENCE_NOT_AVAILABLE`).

`v0.8.0_56tool_scoreboard.csv` is the evidence that all 56 tools work.

### 10.3 Reproducing the acceptance gate

```powershell
# Build everything, run the unit + smoke suites.
.\gradlew.bat clean buildVerification
.\gradlew.bat :modules:ontology-cli:shadowJar

# Run the launcher smoke test.
node tools/npm/test/launcher.test.js

# Run the v0.8 acceptance suite (this is a separate Java entry point).
.\gradlew.bat :modules:ontology-distribution:v08Acceptance
```

The acceptance suite exits 0 when all 56 tool checks pass.

### 10.4 Where the evidence lives

All test artifacts and acceptance evidence are under `reports/` (which is `.gitignore`'d locally — the published evidence is the test contracts and the scoreboard CSV):

- `reports/acceptance/v0.8.0_56tool_scoreboard.csv` — the 56-tool scoreboard.
- `reports/acceptance/v0.8.0_acceptance_report.md` — narrative summary.
- `reports/acceptance/v0.8.0_gradle_test.log` — full Gradle test log.
- `reports/acceptance/v0.8.0_npm_test.log` — npm test log.
- `build/reports/tests/test/index.html` — Gradle HTML report.

The contract tests that the acceptance gate enforces live under `test/contracts/v08-acceptance/`.

---

## License and contributing

Apache-2.0. See [LICENSE](LICENSE). Bug reports and PRs welcome at https://github.com/leungBH/owl4agents.

For protocol contracts and JSON schemas, see the archived OpenSpec changes under `openspec/changes/archive/`. For the strict per-tool contract, see the contract test files under `test/contracts/`.

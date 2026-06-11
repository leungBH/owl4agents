# owl4agents Project Conventions

> Single source of truth for directory structure, naming, and file organization.
> All AI agents (Claude, Codex, Trae, etc.) MUST read this file before making structural changes.

## Directory Structure

```text
owl4agents/
├── modules/              # Gradle multi-module source (DO NOT rename or move)
│   ├── ontology-core/       Domain model, service interfaces, result/error models
│   ├── ontology-owlapi/     OWL API wrapper (load, save, entity resolve, profile checks)
│   ├── ontology-reasoner/   Reasoner adapters (HermiT, ELK, Openllet)
│   ├── ontology-query/      Apache Jena ARQ SPARQL execution and validation
│   ├── ontology-retrieval/  Entity search, graph neighborhood, QA context
│   ├── ontology-validation/ Entailment checks, claim verification, evidence grounding
│   ├── ontology-benchmark/  Experiment config, benchmark runner, QA evaluation, reports
│   ├── ontology-storage/    Local workspace, catalog, metadata, snapshots
│   ├── ontology-cli/        Picocli CLI adapter (external interface)
│   ├── ontology-mcp/        MCP JSON-RPC stdin/stdout adapter (external interface)
│   └── ontology-distribution/ jlink / jpackage packaging
│
├── tools/                # All external interfaces, launchers, and utility scripts
│   ├── skills/             Cross-agent SOP packs (claim verification, evidence, scope)
│   ├── npm/                npm launcher package (Node.js → Java bridge)
│   │   ├── bin/               owl4agents.js (main entry point)
│   │   └── test/              launcher tests
│   ├── bin/                Platform launch scripts (.cmd wrappers)
│   └── scripts/            Build/test utility scripts (PS1, SH)
│
├── test/                 # ALL test-related content lives here
│   ├── contracts/          Test contract definitions (JSON/YAML)
│   ├── corpus/             Test ontologies (smoke, golden, conformance, benchmarks)
│   ├── fixtures/           Test fixture data, versioned (v0.3, v0.4, v0.5, v0.6)
│   ├── reports/            Acceptance reports (gitignored — local only)
│   │   └── acceptance/       Timestamped acceptance test reports
│   └── owl_files/          Private OWL inputs (gitignored)
│
├── examples/             # Runnable demo packs (pizza, claim-verification, biomedical, agent-mcp)
│
├── docs/                 # Project documentation
│   ├── design.md           Architecture and design notes
│   └── architecture.png    System architecture diagram
│
├── openspec/             # Spec and change management (gitignored — local only)
│
├── gradle/               # Gradle Wrapper (DO NOT move)
├── build.gradle.kts      # Root build script (centralizes build output to build/modules/)
├── settings.gradle.kts   # Multi-module include config
├── gradlew / gradlew.bat # Wrapper executables
├── build/                # All build output (gitignored, centralized)
│   └── modules/             Per-module artifacts (libs/, classes/, test-results/)
├── CHANGELOG.md
├── LICENSE / NOTICE
└── README.md
```

## Rules

### 1. One Directory = One Clear Responsibility

Each top-level directory has exactly one job. No mixing concerns.

| Directory     | Responsibility                              |
|---------------|---------------------------------------------|
| `modules/`    | Java/Gradle source code only                |
| `tools/`      | External interfaces, launchers, scripts     |
| `test/`       | Test data, fixtures, contracts, reports     |
| `examples/`   | User-facing demo packs                      |
| `docs/`       | Documentation (markdown, diagrams)          |
| `openspec/`   | Spec/change management (tool internal)      |

### 2. No Single-File Directories

A directory MUST contain at least 2 meaningful items. If a file is alone, merge it into the nearest parent with matching responsibility.

- `scripts/download-test-corpus.ps1` → lives in `tools/scripts/` (not a standalone `scripts/` directory)
- `bin/owl4agents-mcp.cmd` → lives in `tools/bin/` (not a standalone `bin/` directory)

### 3. English-Only File and Directory Names

All file and directory names MUST use ASCII characters (letters, digits, hyphens, dots, underscores).

- `architecture.png` ✓ — not `架构图.png` ✗
- `design.md` ✓ — not `设计文档.md` ✗

### 4. Gitignored Content Does NOT Occupy Root Level

Directories whose content is entirely gitignored MUST be nested under a tracked parent.

- `test/reports/` ✓ — nested under tracked `test/`
- `reports/` ✗ — was a gitignored root directory

### 5. Gradle Module Naming

All Gradle submodules use the `ontology-` prefix: `modules/ontology-{name}/`.

To add a new module:
1. Create `modules/ontology-{name}/`
2. Add to `settings.gradle.kts`: `"modules:ontology-{name}"`
3. Follow standard Gradle `src/main/java` + `src/test/java` layout

### 6. Test Code Lives Inside Modules

Unit and integration test code stays in each module's `src/test/java/` (Gradle convention). The root `test/` directory holds **test data only** (fixtures, corpus, contracts), not test code.

### 7. Example Pack Structure

Each example in `examples/` follows this layout:

```text
examples/{name}/
├── README.md          # What it demonstrates, commands, expected output
├── example.yaml       # Declarative step definitions
├── run.sh             # Bash runner (Linux/macOS)
└── expected/          # Expected output snapshots (optional)
```

### 8. Path References

All CLI invocations use `node tools/npm/bin/owl4agents.js <command>` as the entry point. All path references in documentation and config files MUST be relative to the project root.

## Adding New Content

| What to add               | Where it goes                    |
|---------------------------|----------------------------------|
| New Java module           | `modules/ontology-{name}/`       |
| New CLI/MCP tool          | `modules/ontology-{name}/`       |
| New npm launcher feature  | `tools/npm/`                     |
| New platform script       | `tools/bin/` or `tools/scripts/` |
| New agent skill pack      | `tools/skills/`                |
| New test fixture          | `test/fixtures/v{X}/`            |
| New test ontology         | `test/corpus/{category}/`        |
| New test contract         | `test/contracts/{name}/`         |
| New example demo          | `examples/{name}/`               |
| New documentation         | `docs/`                          |
| New spec or change        | `openspec/`                      |

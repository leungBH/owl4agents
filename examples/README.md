# owl4agents Example Packs

Runnable demo packs that show what owl4agents does and how to connect it to an agent.

## Prerequisites

- Java 22+ installed and on PATH
- Node.js 18+ installed and on PATH
- Project cloned from GitHub with Gradle wrapper present
- Shadow jar built: `.\gradlew.bat :modules:ontology-cli:shadowJar` (Windows) or `./gradlew :modules:ontology-cli:shadowJar` (Linux/macOS)

## Quick Start

Run any example from the repository root using the npm launcher:

```bash
# Windows PowerShell
node tools/npm/bin/owl4agents.js <command>

# Linux / macOS
node tools/npm/bin/owl4agents.js <command>
```

**Important:** Example scripts use `node tools/npm/bin/owl4agents.js` as the CLI entry point. Do not use `java -jar` directly in examples — it produces an `ACCESS_VIOLATION` crash on some Windows setups.

## Example Packs

| Example | What it demonstrates | Interface | Fixture | Run time |
| --- | --- | --- | --- | --- |
| [claim-verification](claim-verification/) | Supported, contradicted, unknown, and out_of_scope claim checking | CLI | `test/corpus/golden/v0.3-claim-verification.owl` | ~5 seconds |
| [pizza-reasoning](pizza-reasoning/) | Ontology import, summary, hierarchy, restrictions, classification | CLI | `test/corpus/smoke/pizza.owl` | ~10 seconds |
| [agent-mcp](agent-mcp/) | MCP client configuration, readonly startup, tool list, tool-call samples | MCP | None (starts MCP server) | ~5 seconds |
| [biomedical-grounding](biomedical-grounding/) | Biomedical-style grounding using a project-owned golden fixture | CLI | `test/corpus/golden/v0.4-biomedical-grounding.owl` | ~5 seconds |

## Workspace Behavior

Examples create temporary workspaces under your OS temp directory by default. To use a specific workspace:

```bash
node tools/npm/bin/owl4agents.js smoke --workspace <home-directory>
```

The `--workspace` parameter accepts a home directory path. If the path already ends with `workspaces/<name>`, the parent directory is used automatically.

## Validation Command

To validate all required examples:

```bash
.\gradlew.bat buildVerification --no-daemon   # Windows
./gradlew buildVerification --no-daemon         # Linux/macOS
```

Example validation is wired into `buildVerification` and runs as part of CI.

## Example Policy

- Required examples MUST pass in CI before any release tag.
- Example manifests (`example.yaml`) describe commands, fixtures, and expected outputs.
- Expected outputs use schema/field assertions, not exact byte-for-byte snapshots.
- All committed outputs are sanitized: no local usernames, absolute private paths, tokens, or private ontology names.
- Optional large fixtures are documented but not required for default CI.

## Troubleshooting

- **Missing fixture:** Check that `test/corpus/` contains the required fixture file. Run `.\gradlew.bat :modules:ontology-cli:shadowJar` first if the jar is missing.
- **ACCESS_VIOLATION on Windows:** Use the npm launcher (`node tools/npm/bin/owl4agents.js`) instead of `java -jar`. See root README for details.
- **MCP startup failure on Windows:** Use the Windows wrapper `tools/bin\owl4agents-mcp.cmd` or `node tools/npm/bin/owl4agents.js mcp`. Both use `java -cp` mode.
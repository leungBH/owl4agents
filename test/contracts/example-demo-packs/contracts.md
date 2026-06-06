# Example Demo Packs Contracts (v0.4)

## Principle

v0.4 introduces public, runnable example packs under `examples/`. Each pack has a manifest, README, scripts, and expected outputs. Example packs are release assets — they must be committed, validated, and synchronized with CLI/MCP contracts before any v0.4 release tag.

## Required Example IDs

| ID | Directory | Interface | CI required | Purpose |
| --- | --- | --- | --- | --- |
| `claim-verification` | `examples/claim-verification/` | CLI | Yes | Supported, contradicted, unknown, and out_of_scope claim verification |
| `pizza-reasoning` | `examples/pizza-reasoning/` | CLI | Yes | Ontology import, summary, class context, property context, classification, inferred knowledge |
| `agent-mcp` | `examples/agent-mcp/` | MCP | Yes | MCP client configuration, readonly startup, tools/list, sanitized tool-call transcripts |
| `biomedical-grounding` | `examples/biomedical-grounding/` | CLI | Yes | Small biomedical-style grounding using project-owned golden fixture |

Optional example:

| ID | Directory | Interface | CI required | Purpose |
| --- | --- | --- | --- | --- |
| `research-context` | `examples/research-context/` | CLI | Manual | BFO/OBI-style context; requires fixture size and license review before inclusion |

## Manifest Schema

Each `examples/<id>/example.yaml` MUST include:

```yaml
id: string          # required — matches one of the IDs above
title: string       # required — human-readable title
description: string # required — what the example demonstrates
interfaces:         # required — list of "cli" | "mcp" | "docs-only"
  - cli
fixtures:           # required — list of fixture dependencies
  - path: string    # relative path from repository root
    required: boolean # true for CI-required, false for optional/manual
    attribution: string # source and license if third-party, or "project" if project-owned
commands:           # required — list of commands to execute
  - step: string    # description of this step
    exec: string    # command string using "node npm/bin/owl4agents.js" as entry point
    exitCode: integer # expected exit code (0 for success paths)
    prerequisite: string | null # e.g., "reason must be run first" — required for contradicted/unknown claims
expectedOutputs:    # required — list of expected output assertions
  - field: string   # JSON field name to check
    present: boolean # whether the field must exist in output
    value: string | null # exact value if deterministic, null for any-non-empty
    snippet: string | null # sample output snippet for documentation
ciRequired: boolean # required — whether this example must pass in CI
platformLimitations: # optional — known platform caveats
  - platform: string
    note: string
attribution:        # required — overall attribution summary
  source: string
  license: string
```

Required fields MUST NOT be empty. Placeholder values such as `TODO`, `stub`, `not implemented`, or fake evidence MUST NOT appear in committed manifests.

## README and Link Requirements

- Each required example directory MUST contain `README.md` with: what the example demonstrates, prerequisites, step-by-step commands, expected output snippets, troubleshooting, and fixture attribution.
- `examples/README.md` MUST list all example packs, prerequisites, and the validation command.
- Root `README.md` MUST link every required example pack in a showcase table or equivalent section.
- All relative links in READMEs MUST resolve to checked-in files; broken links fail validation.

## Fixture Policy

- Required public fixtures MUST exist under `test/corpus/` or `test/fixtures/` before example validation runs.
- Missing required fixtures MUST fail validation with the exact missing path.
- Optional or manual fixtures MUST NOT cause CI failure when absent.
- Large or private fixtures MUST be marked `required: false` in the manifest and documented with download instructions in the example README.
- Fixture attribution and license MUST be documented in the manifest `attribution` field and in `test/corpus/README.md`.

## CLI Discovery Policy

v0.4 examples are script-driven. The project MUST NOT provide or advertise a CLI `examples` or `demo` discovery command in v0.4. Examples are executed through documented commands in README files and manifest files, using `node npm/bin/owl4agents.js` as the entry point.

A CLI discovery command may be added in a future version as a usability enhancement, but it is explicitly outside v0.4 scope.

## Sanitized Output Policy

Committed expected outputs and transcripts under `examples/` MUST NOT contain:

- Absolute local paths (e.g., `C:\Users\...`, `/home/...`, `/Users/...`)
- Local usernames
- Bearer tokens, API keys, passwords, or secret-like fields
- Private ontology IRIs or filenames that are not in the public fixture set
- Machine-specific metadata (hostname, process IDs)

Validation MUST reject any committed output that matches these patterns. Sanitization MUST be applied before committing any transcript or expected output sample.
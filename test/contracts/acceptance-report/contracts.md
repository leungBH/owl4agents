# Acceptance Reporting and Tester Work Standard

This contract defines how owl4agents acceptance testing MUST be planned, executed, reported, and handed off. It applies to every version, OpenSpec change, retest, and release-readiness gate.

Acceptance reports are local evidence stored under `reports/acceptance/`. This contract is public and versioned so developers, testers, and agents can follow the same standard.

## Contract: Tester responsibilities

The tester SHALL validate behavior against the active OpenSpec change, public test contracts, README release claims, and the implemented runtime.

Testers MUST:

- Run real commands, not only inspect source code.
- Verify exit codes, stdout, stderr, generated files, and persisted workspace state where relevant.
- Use required fixtures from `test/corpus/` and fail loudly when required fixtures are missing.
- Distinguish required, optional, skipped, deferred, failed, and blocked scenarios.
- Record every command used for acceptance, including command text, working directory, exit code, duration, and result.
- Record defects with root cause, impact, fix expectation, retest command, and final retest status.
- Produce a timestamped report before declaring acceptance.

Testers MUST NOT:

- Mark a gate passed because implementation exists.
- Treat empty success payloads as valid unless the contract explicitly defines an empty result.
- Treat placeholder payloads as valid behavior.
- Accept arbitrary nonzero exits as successful error handling.
- Hide missing fixtures behind silent skips.
- Use source-code string checks as a substitute for runtime behavior tests.
- Publish local-only reports, private ontology inputs, local paths, or machine-specific details to GitHub unless sanitized and explicitly requested.

## Contract: Report file location and naming

Acceptance reports SHALL be written under:

```text
reports/acceptance/
```

Report filenames SHALL use this pattern:

```text
YYYY-MM-DD-HHMMSS-<version-or-change>-acceptance-report.md
```

Examples:

```text
2026-06-02-103835-v0.2.1-acceptance-report.md
2026-06-02-151200-add-v0-3-claim-verification-acceptance-report.md
```

The report MUST be encoded as UTF-8 without mojibake. Prefer ASCII status words (`PASS`, `FAIL`, `SKIP`, `BLOCKED`) over symbols so reports render correctly across Windows terminals, GitHub, and agent tools.

## Contract: Required report sections

Every acceptance report SHALL include these sections in order:

1. Header
2. Verdict Summary
3. Scope
4. Environment
5. Fixture Inventory
6. Commands Executed
7. Gate Results
8. CLI/MCP Parity Results, when applicable
9. Safety and Negative Tests
10. Defect Ledger
11. Skipped or Deferred Scenarios
12. Retest Notes
13. Evidence Artifacts
14. Final Acceptance Decision

### Header

The header SHALL include:

- report title
- report timestamp with timezone
- tested version or OpenSpec change
- git commit hash or working tree status
- tester name or role
- final verdict

### Scope

The scope SHALL identify:

- what is being accepted
- what is explicitly out of scope
- source contracts used for acceptance
- related OpenSpec change path, if any
- README claims checked, if any

### Environment

The environment SHALL include:

- OS and architecture
- Java version
- Gradle wrapper version
- Node.js version
- npm version, when launcher tests are run
- `OWL4AGENTS_HOME` used for tests, or state that default workspace was used
- whether the run used a clean workspace

### Fixture Inventory

The fixture inventory SHALL list every required and optional fixture used by the report.

For each fixture, include:

| Field | Required |
| --- | --- |
| Fixture ID | Yes |
| Relative path | Yes |
| Required or optional | Yes |
| Purpose | Yes |
| Present | Yes |
| Used by gates | Yes |

Missing required fixtures MUST make the report verdict `FAIL` or `BLOCKED`.

### Commands Executed

Every command SHALL be recorded with:

| Field | Required |
| --- | --- |
| Scenario or gate ID | Yes |
| Working directory | Yes |
| Command | Yes |
| Exit code | Yes |
| Duration | Yes |
| stdout summary | Yes |
| stderr summary | Yes |
| Result | Yes |

For real process tests, the report MUST state whether stderr contained crash text, access violation text, stack traces, unhandled exceptions, or native process failure text.

### Gate Results

Gate results SHALL be reported in a table:

| Gate ID | Contract | Interface | Required | Result | Evidence |
| --- | --- | --- | --- | --- | --- |

Valid result values:

- `PASS`
- `FAIL`
- `SKIP`
- `BLOCKED`
- `DEFERRED`

`SKIP` is only allowed for optional scenarios or scenarios explicitly deferred by the active spec. Required scenarios cannot be skipped.

### Defect Ledger

Every failed or blocked gate SHALL have a defect entry:

| Field | Required |
| --- | --- |
| Defect ID | Yes |
| Related gate ID | Yes |
| Severity | Yes |
| Symptom | Yes |
| Expected behavior | Yes |
| Actual behavior | Yes |
| Root cause | Required after investigation |
| Impact | Yes |
| Required fix | Yes |
| Retest command | Yes |
| Retest status | Required before closure |
| Prevention test | Required before closure |

Severity values:

- `S0 Release Blocker`
- `S1 Critical`
- `S2 Major`
- `S3 Minor`
- `S4 Documentation`

## Contract: Verdict rules

The final verdict SHALL be:

- `PASS` only if all required gates pass, no release blockers remain open, required fixtures are present, and the report contains complete evidence.
- `FAIL` if any required gate fails.
- `BLOCKED` if testing cannot complete because the build, fixture setup, runtime, or environment prevents required gates from running.
- `CONDITIONAL PASS` only when the active OpenSpec explicitly permits it and all conditions are listed with owners and due dates.

A report MUST NOT use `PASS` when:

- required commands were not run
- required fixtures were missing
- CLI/MCP parity was not checked for overlapping capabilities
- launcher/runtime tests accepted arbitrary nonzero exits
- crash text appeared in stderr
- output was empty where real payloads are required
- placeholder payloads were accepted
- report encoding is corrupted enough to obscure evidence

## Contract: Retest discipline

When a defect is fixed, the tester SHALL:

- rerun the original failing command
- rerun the nearest regression gate
- record the before/after result
- record the new or changed test that prevents recurrence
- keep the original failure visible in the report or link to the earlier report

## Contract: Markdown report template

Use this template for new reports:

```markdown
# <version-or-change> Acceptance Report

Report Time: <YYYY-MM-DD HH:mm:ss +08:00>
Tested Target: <version or OpenSpec change>
Git Commit: <commit hash or working tree status>
Tester: <name or role>
Verdict: <PASS | FAIL | BLOCKED | CONDITIONAL PASS>

## Verdict Summary

<One paragraph explaining the decision and the main evidence.>

## Scope

- In scope:
- Out of scope:
- Contracts used:
- OpenSpec change:
- README claims checked:

## Environment

| Item | Value |
| --- | --- |
| OS / Arch | |
| Java | |
| Gradle Wrapper | |
| Node.js | |
| npm | |
| OWL4AGENTS_HOME | |
| Workspace state | clean / reused |

## Fixture Inventory

| Fixture ID | Path | Required | Present | Purpose | Gates |
| --- | --- | --- | --- | --- | --- |

## Commands Executed

| Gate ID | Working Directory | Command | Exit Code | Duration | stdout Summary | stderr Summary | Result |
| --- | --- | --- | --- | --- | --- | --- | --- |

## Gate Results

| Gate ID | Contract | Interface | Required | Result | Evidence |
| --- | --- | --- | --- | --- | --- |

## CLI/MCP Parity Results

| Operation | Fixture | CLI Evidence | MCP Evidence | Equivalent | Differences |
| --- | --- | --- | --- | --- | --- |

## Safety and Negative Tests

| Scenario | Expected Rejection | Actual Result | Workspace Untouched | Result |
| --- | --- | --- | --- | --- |

## Defect Ledger

| Defect ID | Gate | Severity | Symptom | Root Cause | Required Fix | Retest Command | Retest Status | Prevention Test |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

## Skipped or Deferred Scenarios

| Scenario | Status | Reason | Allowed By | Owner / Next Step |
| --- | --- | --- | --- | --- |

## Retest Notes

<Record before/after evidence for fixed defects.>

## Evidence Artifacts

- Logs:
- Generated files:
- Reports:

## Final Acceptance Decision

<PASS/FAIL/BLOCKED decision with concise rationale.>
```
